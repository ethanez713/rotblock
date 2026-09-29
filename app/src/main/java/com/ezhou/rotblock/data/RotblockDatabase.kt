package com.ezhou.rotblock.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.policy.Policy

/**
 * Adds the schedule a rule is live for.
 *
 * Both columns default to the empty string, which [com.ezhou.rotblock.policy.Schedule.decode]
 * reads as always-on -- so every rule written before schedules existed keeps behaving exactly as
 * it did, and a partly-applied upgrade cannot leave a rule dormant.
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE policies ADD COLUMN schedule TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE pending_changes ADD COLUMN schedule TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * Adds the members a group rule covers.
 *
 * Empty on every existing row, which [com.ezhou.rotblock.policy.Policy.decodeMembers] reads as no
 * members -- and no rule written before groups existed is a group, so every one of them keeps
 * behaving exactly as it did.
 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE policies ADD COLUMN members TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE pending_changes ADD COLUMN members TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * Marks sessions recorded during a pause so they can feed the interstitial mirror
 * without counting toward quota. Existing rows default to counting, which is what
 * they already were.
 */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE usage_sessions ADD COLUMN countsTowardQuota INTEGER NOT NULL DEFAULT 1",
        )
    }
}

/**
 * Adds the daily pool. 0 on every existing row and on every queued edit, which is "no pool" --
 * exactly what those rules already were.
 */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE policies ADD COLUMN poolSeconds INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE pending_changes ADD COLUMN poolSeconds INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Whole-phone per-day usage. Empty on upgrade: there is nothing to backfill from, and a day that
 * was never rolled is the same gap as one Android had already pruned.
 */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_usage` (" +
                "`dayStartMs` INTEGER NOT NULL, " +
                "`key` TEXT NOT NULL, " +
                "`ms` INTEGER NOT NULL, " +
                "PRIMARY KEY(`dayStartMs`, `key`))",
        )
    }
}

@Database(
    entities = [
        Policy::class,
        UsageSessionEntity::class,
        AuditEvent::class,
        PendingChange::class,
        DailyUsage::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RotblockDatabase : RoomDatabase() {
    abstract fun policyDao(): PolicyDao
    abstract fun usageDao(): UsageDao
    abstract fun auditDao(): AuditDao
    abstract fun pendingChangeDao(): PendingChangeDao
    abstract fun dailyUsageDao(): DailyUsageDao

    companion object {
        @Volatile private var instance: RotblockDatabase? = null

        fun get(context: Context): RotblockDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RotblockDatabase::class.java,
                "rotblock.db",
            )
                // No destructive migration: the audit log is the one thing in this app that is
                // genuinely irreplaceable, so a schema mistake should fail loudly at build time
                // rather than silently wipe months of history on the phone.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                .also { instance = it }
        }
    }
}
