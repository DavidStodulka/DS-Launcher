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
//  RaceSessionEntity.kt — Performance measurement / drag-run session record
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "race_sessions",
    indices = [
        Index("date"),
        Index("measurement_type")
    ]
)
data class RaceSessionEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Unix epoch millis at which the measurement was performed. */
    @ColumnInfo(name = "date")
    val date: Long,

    /**
     * Location description, e.g. "Circuit de Barcelona-Catalunya",
     * "Motorway A7 km 12 north", or "Carpark drag strip".
     */
    @ColumnInfo(name = "location")
    val location: String? = null,

    /**
     * Type of measurement, e.g. "0-100", "0-200", "80-120", "quarter_mile",
     * "lap_time", "0-100-0".
     */
    @ColumnInfo(name = "measurement_type")
    val measurementType: String,

    /** Measured result in seconds (use fractional precision, e.g. 7.342). */
    @ColumnInfo(name = "result_seconds")
    val resultSeconds: Double,

    /** Maximum speed achieved during this run in km/h. */
    @ColumnInfo(name = "max_speed_kmh")
    val maxSpeedKmh: Float? = null,

    /** Average longitudinal acceleration (positive = forward) in m/s². */
    @ColumnInfo(name = "avg_acceleration_ms2")
    val avgAccelerationMs2: Float? = null,

    /**
     * Environmental / track conditions description,
     * e.g. "dry, 28°C, tarmac", "damp, 14°C, concrete".
     */
    @ColumnInfo(name = "conditions")
    val conditions: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
//  DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface RaceSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: RaceSessionEntity): Long

    @Update
    suspend fun update(session: RaceSessionEntity)

    @Delete
    suspend fun delete(session: RaceSessionEntity)

    @Query("SELECT * FROM race_sessions ORDER BY date DESC")
    fun getAllSessions(): Flow<List<RaceSessionEntity>>

    @Query("SELECT * FROM race_sessions WHERE measurement_type = :type ORDER BY result_seconds ASC")
    fun getSessionsByType(type: String): Flow<List<RaceSessionEntity>>

    @Query("SELECT * FROM race_sessions WHERE measurement_type = :type ORDER BY result_seconds ASC LIMIT 1")
    suspend fun getBestForType(type: String): RaceSessionEntity?

    @Query("SELECT * FROM race_sessions WHERE id = :id")
    suspend fun getById(id: Long): RaceSessionEntity?

    @Query("SELECT * FROM race_sessions ORDER BY date DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<RaceSessionEntity>

    @Query("SELECT MIN(result_seconds) FROM race_sessions WHERE measurement_type = :type")
    suspend fun bestTimeForType(type: String): Double?

    @Query("SELECT COUNT(*) FROM race_sessions")
    suspend fun count(): Int

    @Query("DELETE FROM race_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM race_sessions")
    suspend fun deleteAll()
}
