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
//  RouteEntity.kt — Saved route with GPX track data
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "routes",
    indices = [Index("date"), Index("name")]
)
data class RouteEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** User-assigned name, e.g. "Morning commute" or "Mountain pass B20". */
    @ColumnInfo(name = "name")
    val name: String,

    /** Unix epoch millis when this route was first recorded / saved. */
    @ColumnInfo(name = "date")
    val date: Long,

    /** Total route distance in km. */
    @ColumnInfo(name = "distance_km")
    val distanceKm: Double,

    /** Total elevation gain in meters. */
    @ColumnInfo(name = "total_ascent_m")
    val totalAscentM: Double = 0.0,

    /** Total elevation loss in meters. */
    @ColumnInfo(name = "total_descent_m")
    val totalDescentM: Double = 0.0,

    /**
     * GPX XML as a String.
     * Stored inline for simplicity; for very long routes consider
     * externalising to /sdcard/CarOS/maps/<id>.gpx and storing only the path.
     */
    @ColumnInfo(name = "gpx_data")
    val gpxData: String? = null,

    /** Best elapsed time in seconds over this route. Null until driven once. */
    @ColumnInfo(name = "best_time_s")
    val bestTimeSeconds: Double? = null
)

// ─────────────────────────────────────────────────────────────────────────────
//  DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface RouteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(route: RouteEntity): Long

    @Update
    suspend fun update(route: RouteEntity)

    @Delete
    suspend fun delete(route: RouteEntity)

    @Query("SELECT * FROM routes ORDER BY date DESC")
    fun getAllRoutes(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun getById(id: Long): RouteEntity?

    @Query("SELECT * FROM routes WHERE name LIKE '%' || :query || '%' ORDER BY date DESC")
    fun searchByName(query: String): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes ORDER BY distance_km DESC")
    fun getByDistance(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes ORDER BY best_time_s ASC")
    fun getByBestTime(): Flow<List<RouteEntity>>

    @Query("""
        UPDATE routes
        SET best_time_s = :newTimeSeconds
        WHERE id = :routeId
          AND (best_time_s IS NULL OR :newTimeSeconds < best_time_s)
    """)
    suspend fun updateBestTimeIfImproved(routeId: Long, newTimeSeconds: Double)

    @Query("SELECT COUNT(*) FROM routes")
    suspend fun count(): Int

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM routes")
    suspend fun deleteAll()
}
