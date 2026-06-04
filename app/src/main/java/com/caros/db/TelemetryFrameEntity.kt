package com.caros.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
//  TelemetryFrameEntity.kt — One row per CAN snapshot during a session
//
//  Foreign key → telemetry_sessions.id with CASCADE delete so frames are
//  automatically removed when their parent session is deleted.
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "telemetry_frames",
    foreignKeys = [
        ForeignKey(
            entity        = TelemetrySessionEntity::class,
            parentColumns = ["id"],
            childColumns  = ["session_id"],
            onDelete      = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("session_id"),
        Index("timestamp")
    ]
)
data class TelemetryFrameEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Unix epoch millis — primary sort key. */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    /** Vehicle speed in km/h. */
    @ColumnInfo(name = "speed_kmh")
    val speedKmh: Float? = null,

    /** Engine RPM. */
    @ColumnInfo(name = "rpm")
    val rpm: Int? = null,

    /** Throttle / accelerator pedal position in percent [0–100]. */
    @ColumnInfo(name = "throttle_pct")
    val throttlePct: Float? = null,

    /** Coolant temperature in °C. */
    @ColumnInfo(name = "coolant_temp")
    val coolantTemp: Float? = null,

    /** Engine oil temperature in °C. */
    @ColumnInfo(name = "oil_temp")
    val oilTemp: Float? = null,

    /** Turbocharger boost pressure in kPa. */
    @ColumnInfo(name = "boost_kpa")
    val boostKpa: Float? = null,

    /** Mass Air Flow in g/s. */
    @ColumnInfo(name = "maf_gs")
    val mafGs: Float? = null,

    /** Gear label: "P", "R", "N", "D", or "1"–"6". */
    @ColumnInfo(name = "gear")
    val gear: String? = null,

    /** Short-term fuel trim in percent. */
    @ColumnInfo(name = "fuel_trim_short")
    val fuelTrimShort: Float? = null,

    /** Long-term fuel trim in percent. */
    @ColumnInfo(name = "fuel_trim_long")
    val fuelTrimLong: Float? = null,

    /** DPF soot load in percent [0–100]. */
    @ColumnInfo(name = "dpf_load_pct")
    val dpfLoadPct: Float? = null,

    /** Battery / alternator voltage in V. */
    @ColumnInfo(name = "voltage")
    val voltage: Float? = null,

    /** GPS latitude in decimal degrees. */
    @ColumnInfo(name = "gps_lat")
    val gpsLat: Double? = null,

    /** GPS longitude in decimal degrees. */
    @ColumnInfo(name = "gps_lon")
    val gpsLon: Double? = null,

    /** GPS altitude in meters above sea level. */
    @ColumnInfo(name = "gps_alt")
    val gpsAlt: Double? = null,

    /** GPS-reported speed in km/h (may differ from CAN speed). */
    @ColumnInfo(name = "gps_speed")
    val gpsSpeed: Float? = null,

    /** Lateral g-force (positive = right). */
    @ColumnInfo(name = "lateral_g")
    val lateralG: Float? = null,

    /** Longitudinal g-force (positive = forward). */
    @ColumnInfo(name = "longitudinal_g")
    val longitudinalG: Float? = null,

    /** Parent session ID — indexed, cascades on delete. */
    @ColumnInfo(name = "session_id")
    val sessionId: Long
)

// ─────────────────────────────────────────────────────────────────────────────
//  DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface TelemetryFrameDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(frame: TelemetryFrameEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(frames: List<TelemetryFrameEntity>)

    @Delete
    suspend fun delete(frame: TelemetryFrameEntity)

    @Query("SELECT * FROM telemetry_frames WHERE session_id = :sessionId ORDER BY timestamp ASC")
    fun getFramesForSession(sessionId: Long): Flow<List<TelemetryFrameEntity>>

    @Query("SELECT * FROM telemetry_frames WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getFramesForSessionOnce(sessionId: Long): List<TelemetryFrameEntity>

    @Query("SELECT * FROM telemetry_frames WHERE session_id = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestFrameForSession(sessionId: Long): TelemetryFrameEntity?

    @Query("""
        SELECT * FROM telemetry_frames
        WHERE session_id = :sessionId
          AND timestamp BETWEEN :fromMs AND :toMs
        ORDER BY timestamp ASC
    """)
    suspend fun getFramesInTimeRange(
        sessionId: Long,
        fromMs: Long,
        toMs: Long
    ): List<TelemetryFrameEntity>

    @Query("SELECT MAX(speed_kmh) FROM telemetry_frames WHERE session_id = :sessionId")
    suspend fun getMaxSpeed(sessionId: Long): Float?

    @Query("SELECT AVG(speed_kmh) FROM telemetry_frames WHERE session_id = :sessionId AND speed_kmh > 0")
    suspend fun getAvgSpeed(sessionId: Long): Float?

    @Query("SELECT MAX(rpm) FROM telemetry_frames WHERE session_id = :sessionId")
    suspend fun getMaxRpm(sessionId: Long): Int?

    @Query("SELECT MAX(boost_kpa) FROM telemetry_frames WHERE session_id = :sessionId")
    suspend fun getMaxBoost(sessionId: Long): Float?

    @Query("SELECT COUNT(*) FROM telemetry_frames WHERE session_id = :sessionId")
    suspend fun countForSession(sessionId: Long): Int

    @Query("DELETE FROM telemetry_frames WHERE session_id = :sessionId")
    suspend fun deleteAllForSession(sessionId: Long)

    @Query("DELETE FROM telemetry_frames WHERE timestamp < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
