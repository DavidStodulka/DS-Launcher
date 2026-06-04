package com.caros.vcds

// ─────────────────────────────────────────────────────────────────────────────
//  CodingHistory.kt — Read-only view over the ECU coding change log stored in
//  Room.  Provides convenience wrappers around [CodingHistoryDao].
// ─────────────────────────────────────────────────────────────────────────────

import com.caros.db.CarOSDatabase
import com.caros.db.CodingHistoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CodingHistory @Inject constructor(
    private val db: CarOSDatabase
) {
    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Observe all coding history entries, newest first.
     * Returns a [Flow] that emits whenever the table is modified.
     */
    fun getAllHistory(): Flow<List<CodingHistoryEntity>> =
        db.codingHistoryDao().getAllEntries()

    /**
     * Observe only entries that can be automatically reverted by CarOS.
     */
    fun getRevertibleHistory(): Flow<List<CodingHistoryEntity>> =
        db.codingHistoryDao().getRevertibleEntries()

    /**
     * Observe all history entries for a specific ECU address string (e.g. "01", "09").
     */
    fun getHistoryForEcu(ecuAddressHex: String): Flow<List<CodingHistoryEntity>> =
        db.codingHistoryDao().getEntriesForEcu(ecuAddressHex)

    /**
     * Get a snapshot of the [limit] most recent history entries.
     * This is a suspend function (single shot, not a Flow).
     */
    suspend fun getRecentSnapshot(limit: Int = 50): List<CodingHistoryEntity> =
        db.codingHistoryDao().getRecent(limit)

    /**
     * Delete all entries from the coding history table.
     */
    suspend fun clearHistory() =
        db.codingHistoryDao().deleteAll()

    /**
     * Delete a single history entry by its database row ID.
     */
    suspend fun deleteById(id: Long) =
        db.codingHistoryDao().deleteById(id)

    /**
     * Total number of coding changes recorded.
     */
    suspend fun count(): Int =
        db.codingHistoryDao().count()
}
