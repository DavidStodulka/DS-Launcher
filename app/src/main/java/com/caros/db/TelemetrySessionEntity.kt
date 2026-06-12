package com.caros.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
//  TelemetrySessionEntity.kt — Room entity for a single drive session
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "telemetry_sessions")
data class TelemetrySessionEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Unix epoch millis when the session started (ACC on / first frame). */
    @ColumnInfo(name = "start_time")
    val startTime: Long,

    /** Unix epoch millis when the session ended (ACC off / service stopped). Null if in progress. */
    @ColumnInfo(name = "end_time")
    val endTime: Long? = null,

    /** Total driving distance in km for this session. */
    @ColumnInfo(name = "distance_km")
    val distanceKm: Double = 0.0,

    /** GPS latitude of the session start point. */
    @ColumnInfo(name = "start_lat")
    val startLat: Double? = null,

    /** GPS longitude of the session start point. */
    @ColumnInfo(name = "start_lon")
    val startLon: Double? = null,

    /** Free-form notes added by the user. */
    @ColumnInfo(name = "notes")
    val notes: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
//  DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface TelemetrySessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: TelemetrySessionEntity): Long

    @Update
    suspend fun update(session: TelemetrySessionEntity)

    @Delete
    suspend fun delete(session: TelemetrySessionEntity)

    @Query("SELECT * FROM telemetry_sessions ORDER BY start_time DESC")
    fun getAllSessions(): Flow<List<TelemetrySessionEntity>>

    @Query("SELECT * FROM telemetry_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): TelemetrySessionEntity?

    /** Returns the most recently started session (latest start_time). */
    @Query("SELECT * FROM telemetry_sessions ORDER BY start_time DESC LIMIT 1")
    suspend fun getLatestSession(): TelemetrySessionEntity?

    /** Sessions that have no end_time are considered in-progress. */
    @Query("SELECT * FROM telemetry_sessions WHERE end_time IS NULL ORDER BY start_time DESC LIMIT 1")
    suspend fun getActiveSession(): TelemetrySessionEntity?

    @Query("UPDATE telemetry_sessions SET end_time = :endTime, distance_km = :distanceKm WHERE id = :sessionId")
    suspend fun closeSession(sessionId: Long, endTime: Long, distanceKm: Double)

    @Query("SELECT COUNT(*) FROM telemetry_sessions")
    suspend fun count(): Int

    @Query("SELECT SUM(distance_km) FROM telemetry_sessions")
    suspend fun totalDistanceKm(): Double?

    @Query("DELETE FROM telemetry_sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: Long)

    @Query("DELETE FROM telemetry_sessions")
    suspend fun deleteAll()

    /** Prune finished sessions that started before [beforeMs]. Returns rows deleted. */
    @Query("DELETE FROM telemetry_sessions WHERE start_time < :beforeMs AND end_time IS NOT NULL")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
