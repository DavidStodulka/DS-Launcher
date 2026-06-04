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
//  CodingHistoryEntity.kt — Stores every ECU coding change made through CarOS
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "coding_history",
    indices = [
        Index("ecu_address"),
        Index("timestamp")
    ]
)
data class CodingHistoryEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Unix epoch millis when the coding change was applied. */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    /**
     * UDS/KWP ECU address in hex notation, e.g. "01" (engine ECU), "19" (instrument cluster).
     * Matches VCDS address coding.
     */
    @ColumnInfo(name = "ecu_address")
    val ecuAddress: String,

    /**
     * VCDS-style channel identifier, e.g. "Byte 0 Bit 3" or "Long coding byte 2".
     * Free-form text describing which channel/bit/byte was modified.
     */
    @ColumnInfo(name = "channel")
    val channel: String,

    /** The value before this change was applied (as String to support hex / decimal / binary). */
    @ColumnInfo(name = "old_value")
    val oldValue: String,

    /** The value after this change was applied. */
    @ColumnInfo(name = "new_value")
    val newValue: String,

    /** Human-readable description of what the coding change enables/disables. */
    @ColumnInfo(name = "description")
    val description: String,

    /**
     * Whether this change can be automatically reverted by CarOS.
     * Changes to safety-critical ECUs (ABS, airbags) should be marked false.
     */
    @ColumnInfo(name = "can_be_reverted")
    val canBeReverted: Boolean = true
)

// ─────────────────────────────────────────────────────────────────────────────
//  DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface CodingHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CodingHistoryEntity): Long

    @Update
    suspend fun update(entry: CodingHistoryEntity)

    @Delete
    suspend fun delete(entry: CodingHistoryEntity)

    @Query("SELECT * FROM coding_history ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<CodingHistoryEntity>>

    @Query("SELECT * FROM coding_history WHERE ecu_address = :ecuAddress ORDER BY timestamp DESC")
    fun getEntriesForEcu(ecuAddress: String): Flow<List<CodingHistoryEntity>>

    @Query("SELECT * FROM coding_history WHERE id = :id")
    suspend fun getById(id: Long): CodingHistoryEntity?

    @Query("SELECT * FROM coding_history WHERE can_be_reverted = 1 ORDER BY timestamp DESC")
    fun getRevertibleEntries(): Flow<List<CodingHistoryEntity>>

    @Query("SELECT * FROM coding_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<CodingHistoryEntity>

    @Query("SELECT COUNT(*) FROM coding_history")
    suspend fun count(): Int

    @Query("DELETE FROM coding_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM coding_history")
    suspend fun deleteAll()
}
