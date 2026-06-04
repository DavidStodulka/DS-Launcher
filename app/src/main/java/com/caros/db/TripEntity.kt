package com.caros.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
//  TripEntity.kt — High-level trip summary (ACC on → ACC off cycle)
//
//  A Trip is a lightweight summary record created when ACC goes off.
//  Detailed per-frame data lives in telemetry_frames / telemetry_sessions.
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "trips",
    indices = [Index("start_time"), Index("end_time")]
)
data class TripEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Unix epoch millis when ACC turned on (trip start). */
    @ColumnInfo(name = "start_time")
    val startTime: Long,

    /** Unix epoch millis when ACC turned off (trip end). Null if trip is in progress. */
    @ColumnInfo(name = "end_time")
    val endTime: Long? = null,

    /** Total distance driven in km during this trip. */
    @ColumnInfo(name = "distance_km")
    val distanceKm: Double = 0.0,

    /** Average speed in km/h (calculated over moving time, i.e. speed > 0). */
    @ColumnInfo(name = "avg_speed_kmh")
    val avgSpeedKmh: Float? = null,

    /** Maximum speed reached during this trip in km/h. */
    @ColumnInfo(name = "max_speed_kmh")
    val maxSpeedKmh: Float? = null,

    /**
     * Estimated fuel consumed in litres.
     * Calculated from MAF / fuel trim data if available; null otherwise.
     */
    @ColumnInfo(name = "fuel_used_litres")
    val fuelUsedLitres: Float? = null
)

// ─────────────────────────────────────────────────────────────────────────────
//  DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface TripDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Delete
    suspend fun delete(trip: TripEntity)

    @Query("SELECT * FROM trips ORDER BY start_time DESC")
    fun getAllTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getById(id: Long): TripEntity?

    @Query("SELECT * FROM trips ORDER BY start_time DESC LIMIT 1")
    suspend fun getLatestTrip(): TripEntity?

    @Query("SELECT * FROM trips WHERE end_time IS NULL LIMIT 1")
    suspend fun getActiveTrip(): TripEntity?

    @Query("SELECT * FROM trips ORDER BY start_time DESC LIMIT :limit")
    fun getRecentTrips(limit: Int = 30): Flow<List<TripEntity>>

    @Query("SELECT SUM(distance_km) FROM trips")
    suspend fun totalDistanceKm(): Double?

    @Query("SELECT SUM(fuel_used_litres) FROM trips WHERE fuel_used_litres IS NOT NULL")
    suspend fun totalFuelLitres(): Float?

    @Query("SELECT MAX(max_speed_kmh) FROM trips")
    suspend fun allTimeMaxSpeed(): Float?

    @Query("SELECT AVG(avg_speed_kmh) FROM trips WHERE avg_speed_kmh IS NOT NULL")
    suspend fun overallAvgSpeed(): Float?

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun count(): Int

    @Query("""
        UPDATE trips
        SET end_time       = :endTime,
            distance_km    = :distanceKm,
            avg_speed_kmh  = :avgSpeed,
            max_speed_kmh  = :maxSpeed,
            fuel_used_litres = :fuelUsed
        WHERE id = :tripId
    """)
    suspend fun closeTrip(
        tripId: Long,
        endTime: Long,
        distanceKm: Double,
        avgSpeed: Float?,
        maxSpeed: Float?,
        fuelUsed: Float?
    )

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM trips WHERE start_time < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int

    @Query("DELETE FROM trips")
    suspend fun deleteAll()
}
