package com.tfournet.treadspan.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ─── Entities ────────────────────────────────────────────────────────────────

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: String,
    val endedAt: String? = null,
)

@Entity(
    tableName = "readings",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
    )],
    indices = [Index("sessionId")],
)
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: String,
    val sessionId: Long,
    val rawSteps: Int,
    val rawTimeSecs: Int,
    val speed: Double,
    val distance: Double,
    val deltaSteps: Int,
    val deltaTimeSecs: Int,
)

@Entity(
    tableName = "step_intervals",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
    )],
    indices = [Index("synced"), Index("sessionId")],
)
data class StepIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val periodStart: String,
    val periodEnd: String,
    val stepCount: Int,
    val synced: Int = 0,
    val syncedAt: String? = null,
)

// ─── DAO ─────────────────────────────────────────────────────────────────────

@Dao
interface TreadmillDao {

    // Sessions

    @Insert
    suspend fun startSession(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAt = :endedAt WHERE id = :sessionId")
    suspend fun endSession(sessionId: Long, endedAt: String)

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun getActiveSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY id DESC LIMIT 1")
    fun getActiveSessionFlow(): Flow<SessionEntity?>

    // Readings

    @Insert
    suspend fun insertReading(reading: ReadingEntity): Long

    @Query("SELECT * FROM readings ORDER BY id DESC LIMIT 1")
    suspend fun getLastReadingAnySession(): ReadingEntity?

    @Query("SELECT * FROM readings WHERE sessionId = :sessionId ORDER BY id DESC LIMIT 1")
    suspend fun getLastReading(sessionId: Long): ReadingEntity?

    // Step intervals

    @Insert
    suspend fun enqueueInterval(interval: StepIntervalEntity): Long

    @Query("""
        SELECT * FROM step_intervals
        WHERE synced = 0 AND stepCount > 0
        ORDER BY periodStart
        LIMIT :limit
    """)
    suspend fun getPendingIntervals(limit: Int = 50): List<StepIntervalEntity>

    @Query("""
        SELECT * FROM step_intervals
        WHERE periodStart >= :today AND stepCount > 0
        ORDER BY periodStart
    """)
    suspend fun getTodayIntervals(today: String): List<StepIntervalEntity>

    @Query("""
        SELECT * FROM step_intervals
        WHERE periodStart >= :today AND stepCount > 0
        ORDER BY periodStart
    """)
    fun getTodayIntervalsFlow(today: String): Flow<List<StepIntervalEntity>>

    @Query("UPDATE step_intervals SET synced = 1, syncedAt = :syncedAt WHERE id = :intervalId")
    suspend fun markSynced(intervalId: Long, syncedAt: String)
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [SessionEntity::class, ReadingEntity::class, StepIntervalEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TreadmillDatabase : RoomDatabase() {

    abstract fun dao(): TreadmillDao

    companion object {
        @Volatile private var instance: TreadmillDatabase? = null

        fun getInstance(context: Context): TreadmillDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TreadmillDatabase::class.java,
                    "treadmill.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
