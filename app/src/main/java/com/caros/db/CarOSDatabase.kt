package com.caros.db

// ─────────────────────────────────────────────────────────────────────────────
//  CarOSDatabase.kt — Room database definition for the CarOS automotive OS
//
//  Entities:
//    • TelemetrySessionEntity  — one row per drive session
//    • TelemetryFrameEntity    — one row per CAN snapshot within a session
//    • CodingHistoryEntity     — ECU coding change log
//    • ServiceHistoryEntity    — maintenance service records
//    • RaceSessionEntity       — performance measurement runs
//    • RouteEntity             — saved GPX routes
//    • ProfileEntity           — user personalisation profiles
//    • TripEntity              — high-level ACC-on/off trip summaries
//
//  Type converters (defined below):
//    • StringListConverter     — List<String> ↔ comma-separated String
//    • BooleanConverter        — Boolean ↔ Int (0/1) for legacy SQLite compat
//    • ServiceTypeConverter    — ServiceType ↔ String  (in ServiceHistoryEntity.kt)
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
//  Type converters
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Converts [List]<[String]> to/from a comma-separated String for Room storage.
 * Elements must not contain commas — use JSON serialisation for complex data.
 */
class StringListConverter {
    @TypeConverter
    fun fromStringList(list: List<String>?): String? =
        list?.joinToString(",")

    @TypeConverter
    fun toStringList(value: String?): List<String>? =
        value?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
}

/**
 * Converts [Boolean] to/from Int (0/1) — belt-and-suspenders for older SQLite
 * versions that may not honour Room's built-in Boolean affinity.
 */
class BooleanConverter {
    @TypeConverter
    fun fromBoolean(value: Boolean?): Int? = value?.let { if (it) 1 else 0 }

    @TypeConverter
    fun toBoolean(value: Int?): Boolean? = value?.let { it != 0 }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Database
// ─────────────────────────────────────────────────────────────────────────────

private const val DB_NAME    = "caros_database"
private const val DB_VERSION = 3

@Database(
    entities = [
        TelemetrySessionEntity::class,
        TelemetryFrameEntity::class,
        CodingHistoryEntity::class,
        ServiceHistoryEntity::class,
        RaceSessionEntity::class,
        RouteEntity::class,
        ProfileEntity::class,
        TripEntity::class,
        AudioProfileEntity::class,
        RoutePredictionEntity::class
    ],
    version      = DB_VERSION,
    exportSchema = true
)
@TypeConverters(
    StringListConverter::class,
    BooleanConverter::class,
    ServiceTypeConverter::class
)
abstract class CarOSDatabase : RoomDatabase() {

    // ── DAO accessors ─────────────────────────────────────────────────────────

    abstract fun telemetrySessionDao(): TelemetrySessionDao
    abstract fun telemetryFrameDao():   TelemetryFrameDao
    abstract fun codingHistoryDao():    CodingHistoryDao
    abstract fun serviceHistoryDao():   ServiceHistoryDao
    abstract fun raceSessionDao():      RaceSessionDao
    abstract fun routeDao():            RouteDao
    abstract fun profileDao():          ProfileDao
    abstract fun tripDao():             TripDao
    abstract fun audioProfileDao():        AudioProfileDao
    abstract fun routePredictionDao():     RoutePredictionDao

    // ── Companion / singleton factory ─────────────────────────────────────────

    companion object {

        @Volatile
        private var INSTANCE: CarOSDatabase? = null

        /**
         * Returns the singleton [CarOSDatabase] instance, creating it on first call.
         * In DI-managed code, prefer injecting via [DatabaseModule] (Hilt).
         */
        fun getInstance(context: Context): CarOSDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }

        private fun buildDatabase(context: Context): CarOSDatabase {
            Timber.i("CarOSDatabase: building '%s' v%d", DB_NAME, DB_VERSION)
            return Room.databaseBuilder(
                context.applicationContext,
                CarOSDatabase::class.java,
                DB_NAME
            )
                .addCallback(DatabaseCallback())
                .addMigrations(*ALL_MIGRATIONS)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }

        // ── Migrations ────────────────────────────────────────────────────────
        // Add Migration objects here as the schema evolves. Example:
        //
        //   val MIGRATION_1_2 = object : Migration(1, 2) {
        //       override fun migrate(db: SupportSQLiteDatabase) {
        //           db.execSQL("ALTER TABLE trips ADD COLUMN notes TEXT")
        //       }
        //   }
        //
        // Then add it to ALL_MIGRATIONS below.

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `audio_profiles` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `jsonData` TEXT NOT NULL,
                        `lastModified` INTEGER NOT NULL
                    )"""
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `route_predictions` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `dayOfWeek` INTEGER NOT NULL,
                        `hourOfDay` INTEGER NOT NULL,
                        `destLat` REAL NOT NULL,
                        `destLon` REAL NOT NULL,
                        `destLabel` TEXT NOT NULL,
                        `tripCount` INTEGER NOT NULL DEFAULT 1,
                        `lastUsedMs` INTEGER NOT NULL
                    )"""
                )
            }
        }

        private val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }

    // ── Database lifecycle callback ───────────────────────────────────────────

    private class DatabaseCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            Timber.i("CarOSDatabase: first create — seeding default profile")
            db.execSQL(
                """INSERT INTO profiles (name, eq_settings_json, brightness, volume, is_active)
                   VALUES ('Default', '{"bass":0,"mid":0,"treble":0,"preset":"flat"}', 180, 60, 1)"""
            )
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            // WAL mode: better concurrent read throughput
            db.execSQL("PRAGMA journal_mode=WAL")
            // Room does NOT enforce foreign keys by default — enable explicitly
            db.execSQL("PRAGMA foreign_keys=ON")
            Timber.d("CarOSDatabase: opened, WAL + FK enforcement active")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Hilt module — provides the database and all DAOs as singletons
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CarOSDatabase =
        CarOSDatabase.getInstance(context)

    @Provides
    fun provideTelemetrySessionDao(db: CarOSDatabase): TelemetrySessionDao =
        db.telemetrySessionDao()

    @Provides
    fun provideTelemetryFrameDao(db: CarOSDatabase): TelemetryFrameDao =
        db.telemetryFrameDao()

    @Provides
    fun provideCodingHistoryDao(db: CarOSDatabase): CodingHistoryDao =
        db.codingHistoryDao()

    @Provides
    fun provideServiceHistoryDao(db: CarOSDatabase): ServiceHistoryDao =
        db.serviceHistoryDao()

    @Provides
    fun provideRaceSessionDao(db: CarOSDatabase): RaceSessionDao =
        db.raceSessionDao()

    @Provides
    fun provideRouteDao(db: CarOSDatabase): RouteDao =
        db.routeDao()

    @Provides
    fun provideProfileDao(db: CarOSDatabase): ProfileDao =
        db.profileDao()

    @Provides
    fun provideTripDao(db: CarOSDatabase): TripDao =
        db.tripDao()

    @Provides
    fun provideAudioProfileDao(db: CarOSDatabase): AudioProfileDao =
        db.audioProfileDao()

    @Provides
    fun provideRoutePredictionDao(db: CarOSDatabase): RoutePredictionDao =
        db.routePredictionDao()
}

// ─────────────────────────────────────────────────────────────────────────────
//  AudioProfileDao
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface AudioProfileDao {
    @Query("SELECT * FROM audio_profiles ORDER BY lastModified DESC")
    fun getAllProfiles(): Flow<List<AudioProfileEntity>>

    @Upsert
    suspend fun upsert(profile: AudioProfileEntity)

    @Delete
    suspend fun delete(profile: AudioProfileEntity)

    @Query("SELECT * FROM audio_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AudioProfileEntity?
}
