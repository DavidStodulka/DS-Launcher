package com.caros.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "route_predictions")
data class RoutePredictionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Calendar.DAY_OF_WEEK: 1=Sun, 2=Mon … 7=Sat */
    val dayOfWeek: Int,
    val hourOfDay: Int,
    val destLat: Double,
    val destLon: Double,
    val destLabel: String,
    val tripCount: Int = 1,
    val lastUsedMs: Long
)

@Dao
interface RoutePredictionDao {

    @Query("SELECT * FROM route_predictions ORDER BY tripCount DESC")
    fun getAll(): Flow<List<RoutePredictionEntity>>

    @Query(
        """SELECT * FROM route_predictions
           WHERE dayOfWeek = :dow
           AND hourOfDay BETWEEN :hourMin AND :hourMax
           ORDER BY tripCount DESC LIMIT 1"""
    )
    suspend fun findBestMatch(dow: Int, hourMin: Int, hourMax: Int): RoutePredictionEntity?

    /** Spatial + temporal lookup for deduplication: ~300 m radius, ±1 h window. */
    @Query(
        """SELECT * FROM route_predictions
           WHERE abs(destLat - :lat) < 0.003
           AND abs(destLon - :lon) < 0.003
           AND dayOfWeek = :dow
           AND abs(hourOfDay - :hour) <= 1
           LIMIT 1"""
    )
    suspend fun findNearby(lat: Double, lon: Double, dow: Int, hour: Int): RoutePredictionEntity?

    @Upsert
    suspend fun upsert(entity: RoutePredictionEntity)

    @Query("UPDATE route_predictions SET tripCount = tripCount + 1, lastUsedMs = :now WHERE id = :id")
    suspend fun incrementCount(id: Long, now: Long)

    @Query("DELETE FROM route_predictions WHERE lastUsedMs < :beforeMs")
    suspend fun deleteOld(beforeMs: Long)
}
