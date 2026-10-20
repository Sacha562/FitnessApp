package dev.fitnessapp.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.fitnessapp.data.db.dao.ChallengeDefinitionDao
import dev.fitnessapp.data.db.dao.ChallengeEnrollmentDao
import dev.fitnessapp.data.db.dao.ChallengeEventDao
import dev.fitnessapp.data.db.dao.ChallengeRuleEventDao
import dev.fitnessapp.data.db.dao.CrashLogDao
import dev.fitnessapp.data.db.dao.DeadLetterDao
import dev.fitnessapp.data.db.dao.ExportDao
import dev.fitnessapp.data.db.dao.GymDao
import dev.fitnessapp.data.db.dao.GymEquipmentDao
import dev.fitnessapp.data.db.dao.MetricObservationDao
import dev.fitnessapp.data.db.dao.OutboxDao
import dev.fitnessapp.data.db.dao.ProfileDao
import dev.fitnessapp.data.db.dao.RestTimerDao
import dev.fitnessapp.data.db.dao.ScheduledAlarmDao
import dev.fitnessapp.data.db.dao.SessionBlockDao
import dev.fitnessapp.data.db.dao.SessionEventDao
import dev.fitnessapp.data.db.dao.SessionExerciseDao
import dev.fitnessapp.data.db.dao.SetLogDao
import dev.fitnessapp.data.db.dao.SyncCursorDao
import dev.fitnessapp.data.db.dao.UserExercisePrefDao
import dev.fitnessapp.data.db.dao.WellnessLogDao
import dev.fitnessapp.data.db.dao.WorkoutSessionDao
import dev.fitnessapp.data.db.entity.ChallengeDefinitionEntity
import dev.fitnessapp.data.db.entity.ChallengeEnrollmentEntity
import dev.fitnessapp.data.db.entity.ChallengeEventEntity
import dev.fitnessapp.data.db.entity.ChallengeRuleEventEntity
import dev.fitnessapp.data.db.entity.ExportEntity
import dev.fitnessapp.data.db.entity.GymEntity
import dev.fitnessapp.data.db.entity.GymEquipmentEntity
import dev.fitnessapp.data.db.entity.MetricObservationEntity
import dev.fitnessapp.data.db.entity.ProfileEntity
import dev.fitnessapp.data.db.entity.SessionBlockEntity
import dev.fitnessapp.data.db.entity.SessionEventEntity
import dev.fitnessapp.data.db.entity.SessionExerciseEntity
import dev.fitnessapp.data.db.entity.SetLogEntity
import dev.fitnessapp.data.db.entity.UserExercisePrefEntity
import dev.fitnessapp.data.db.entity.WellnessLogEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.db.local.CrashLogEntity
import dev.fitnessapp.data.db.local.DeadLetterEntity
import dev.fitnessapp.data.db.local.OutboxEntity
import dev.fitnessapp.data.db.local.RestTimerEntity
import dev.fitnessapp.data.db.local.ScheduledAlarmEntity
import dev.fitnessapp.data.db.local.SyncCursorEntity
import java.io.File

/**
 * The phone's source of truth (ANDROID.md 8). Version 1 holds the synced tables that logging needs in M1a
 * (configuration, sessions and sets, wellness, metrics, telemetry) plus the local-only tables of 8.3; version 2
 * (M1b) adds `user_exercise_prefs` and `exports`; version 3 (M2b) the challenge tables (definitions, enrollments,
 * events, rule audit). Later milestones add tables through numbered migrations, never through a destructive
 * fallback (8.5).
 */
@Database(
    version = FitnessDatabase.VERSION,
    exportSchema = true,
    entities = [
        ProfileEntity::class, GymEntity::class, GymEquipmentEntity::class,
        WorkoutSessionEntity::class, SessionBlockEntity::class, SessionExerciseEntity::class, SetLogEntity::class,
        WellnessLogEntity::class, MetricObservationEntity::class, SessionEventEntity::class,
        OutboxEntity::class, SyncCursorEntity::class, DeadLetterEntity::class, RestTimerEntity::class,
        ScheduledAlarmEntity::class, CrashLogEntity::class,
        // v2 (M1b)
        UserExercisePrefEntity::class, ExportEntity::class,
        // v3 (M2b)
        ChallengeDefinitionEntity::class, ChallengeEnrollmentEntity::class, ChallengeEventEntity::class,
        ChallengeRuleEventEntity::class,
    ],
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Converters::class)
abstract class FitnessDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun gyms(): GymDao
    abstract fun gymEquipment(): GymEquipmentDao
    abstract fun sessions(): WorkoutSessionDao
    abstract fun blocks(): SessionBlockDao
    abstract fun sessionExercises(): SessionExerciseDao
    abstract fun setLogs(): SetLogDao
    abstract fun wellness(): WellnessLogDao
    abstract fun metrics(): MetricObservationDao
    abstract fun events(): SessionEventDao
    abstract fun outbox(): OutboxDao
    abstract fun cursors(): SyncCursorDao
    abstract fun deadLetters(): DeadLetterDao
    abstract fun restTimers(): RestTimerDao
    abstract fun alarms(): ScheduledAlarmDao
    abstract fun crashes(): CrashLogDao
    abstract fun exercisePrefs(): UserExercisePrefDao
    abstract fun exports(): ExportDao
    abstract fun challengeDefinitions(): ChallengeDefinitionDao
    abstract fun enrollments(): ChallengeEnrollmentDao
    abstract fun challengeEvents(): ChallengeEventDao
    abstract fun ruleEvents(): ChallengeRuleEventDao

    companion object {
        const val VERSION = 3
        const val FILE = "fitness.db"
        private const val BACKUPS_KEPT = 3

        /**
         * Hand-written migrations of every version; [NoDestructiveMigrationTest] checks the builder uses only these.
         * 1 -> 2 and 2 -> 3 only add tables and are [AutoMigration]s declared on the class.
         */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()

        /** The production builder: no destructive fallback in any build type (8.5). */
        fun builder(context: Context): Builder<FitnessDatabase> =
            Room.databaseBuilder(context.applicationContext, FitnessDatabase::class.java, FILE)
                .addMigrations(*MIGRATIONS)

        fun open(context: Context, nowMs: Long): FitnessDatabase {
            backupBeforeMigration(context, nowMs)
            return builder(context).build()
        }

        /** Copies the file before Room opens it when its user_version is older than [VERSION] (8.5). */
        private fun backupBeforeMigration(context: Context, nowMs: Long) {
            val db = context.getDatabasePath(FILE)
            if (!db.exists()) return
            val old = try {
                SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
            } catch (e: Exception) {
                return
            }
            if (old >= VERSION) return
            val dir = File(context.filesDir, "db-backups").apply { mkdirs() }
            for (suffix in listOf("", "-wal", "-shm")) {
                val src = File(db.path + suffix)
                if (src.exists()) src.copyTo(File(dir, "fitness-v$old-$nowMs.db$suffix"), overwrite = true)
            }
            dir.listFiles { f -> f.name.matches(Regex("fitness-v\\d+-\\d+\\.db")) }
                ?.sortedByDescending { it.name.substringAfterLast('-').removeSuffix(".db").toLongOrNull() ?: 0L }
                ?.drop(BACKUPS_KEPT)
                ?.forEach { f -> listOf("", "-wal", "-shm").forEach { File(f.path + it).delete() } }
        }
    }
}
