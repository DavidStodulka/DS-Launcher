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
import androidx.room.TypeConverter
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
//  ServiceHistoryEntity.kt — Maintenance service records for the vehicle
// ─────────────────────────────────────────────────────────────────────────────

/** Type of service interval tracked by CarOS. */
enum class ServiceType {
    OIL,
    DPF,
    DSG,
    COOLANT,
    AIR_FILTER,
    FUEL_FILTER,
    GLOW_PLUGS,
    TIMING_BELT
}

/** Room TypeConverter for [ServiceType] — stored as String name. */
class ServiceTypeConverter {
    @TypeConverter
    fun fromServiceType(value: ServiceType?): String? = value?.name

    @TypeConverter
    fun toServiceType(value: String?): ServiceType? =
        value?.let { runCatching { ServiceType.valueOf(it) }.getOrNull() }
}

@Entity(
    tableName = "service_history",
    indices = [
        Index("service_type"),
        Index("service_date")
    ]
)
data class ServiceHistoryEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Category of service performed. */
    @ColumnInfo(name = "service_type")
    val serviceType: ServiceType,

    /** Date the service was performed — stored as Unix epoch millis (start of day). */
    @ColumnInfo(name = "service_date")
    val serviceDate: Long,

    /** Odometer reading in km at the time of service. */
    @ColumnInfo(name = "mileage_at_service_km")
    val mileageAtServiceKm: Int,

    /** Odometer km at which the next service of this type is due. */
    @ColumnInfo(name = "next_due_km")
    val nextDueKm: Int? = null,

    /** Calendar date by which the next service is due — Unix epoch millis. */
    @ColumnInfo(name = "next_due_date")
    val nextDueDate: Long? = null,

    /** Optional free-form notes (product used, workshop, observations). */
    @ColumnInfo(name = "notes")
    val notes: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
//  DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface ServiceHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ServiceHistoryEntity): Long

    @Update
    suspend fun update(record: ServiceHistoryEntity)

    @Delete
    suspend fun delete(record: ServiceHistoryEntity)

    @Query("SELECT * FROM service_history ORDER BY service_date DESC")
    fun getAllRecords(): Flow<List<ServiceHistoryEntity>>

    @Query("SELECT * FROM service_history WHERE service_type = :type ORDER BY service_date DESC")
    fun getRecordsForType(type: ServiceType): Flow<List<ServiceHistoryEntity>>

    @Query("SELECT * FROM service_history WHERE service_type = :type ORDER BY service_date DESC LIMIT 1")
    suspend fun getLatestForType(type: ServiceType): ServiceHistoryEntity?

    @Query("SELECT * FROM service_history WHERE id = :id")
    suspend fun getById(id: Long): ServiceHistoryEntity?

    /**
     * Returns services whose next_due_km is at or below the given current odometer,
     * indicating they are overdue.
     */
    @Query("SELECT * FROM service_history WHERE next_due_km IS NOT NULL AND next_due_km <= :currentKm ORDER BY next_due_km ASC")
    suspend fun getOverdueByKm(currentKm: Int): List<ServiceHistoryEntity>

    /**
     * Returns services whose next_due_date is at or before the given timestamp.
     */
    @Query("SELECT * FROM service_history WHERE next_due_date IS NOT NULL AND next_due_date <= :nowMs ORDER BY next_due_date ASC")
    suspend fun getOverdueByDate(nowMs: Long): List<ServiceHistoryEntity>

    @Query("SELECT COUNT(*) FROM service_history")
    suspend fun count(): Int

    @Query("DELETE FROM service_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM service_history")
    suspend fun deleteAll()
}
