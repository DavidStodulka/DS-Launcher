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
//  ProfileEntity.kt — User profile / personalisation settings
// ─────────────────────────────────────────────────────────────────────────────

@Entity(
    tableName = "profiles",
    indices = [Index("is_active"), Index("name")]
)
data class ProfileEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Display name for this profile, e.g. "Driver 1", "Sport mode". */
    @ColumnInfo(name = "name")
    val name: String,

    /**
     * Equaliser settings serialised as JSON.
     * Example: {"bass": 2, "mid": 0, "treble": -1, "preset": "custom"}
     * Deserialise using kotlinx.serialization or Gson in the repository layer.
     */
    @ColumnInfo(name = "eq_settings_json")
    val eqSettingsJson: String? = null,

    /** Screen brightness level [0–255]. */
    @ColumnInfo(name = "brightness")
    val brightness: Int = 128,

    /** Media volume level [0–100]. */
    @ColumnInfo(name = "volume")
    val volume: Int = 50,

    /**
     * Whether this profile is currently active.
     * Only one profile should have is_active = true at any time.
     * Enforce this via the repository (deactivate others before activating one).
     */
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────
//  DAO
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface ProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Delete
    suspend fun delete(profile: ProfileEntity)

    @Query("SELECT * FROM profiles ORDER BY name ASC")
    fun getAllProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: Long): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE is_active = 1 LIMIT 1")
    fun getActiveProfile(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE is_active = 1 LIMIT 1")
    suspend fun getActiveProfileOnce(): ProfileEntity?

    /** Deactivates all profiles — call before activating a new one. */
    @Query("UPDATE profiles SET is_active = 0")
    suspend fun deactivateAll()

    @Query("UPDATE profiles SET is_active = 1 WHERE id = :id")
    suspend fun activateById(id: Long)

    @Query("UPDATE profiles SET brightness = :brightness WHERE id = :id")
    suspend fun updateBrightness(id: Long, brightness: Int)

    @Query("UPDATE profiles SET volume = :volume WHERE id = :id")
    suspend fun updateVolume(id: Long, volume: Int)

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM profiles")
    suspend fun deleteAll()
}
