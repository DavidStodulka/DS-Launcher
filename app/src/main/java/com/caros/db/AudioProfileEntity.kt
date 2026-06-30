package com.caros.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_profiles")
data class AudioProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val jsonData: String,  // serialized AudioProfile as JSON
    val lastModified: Long = System.currentTimeMillis()
)
