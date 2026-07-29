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

@Database(
    entities = [Policy::class, UsageSessionEntity::class, AuditEvent::class, PendingChange::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RotblockDatabase : RoomDatabase() {
    abstract fun policyDao(): PolicyDao
    abstract fun usageDao(): UsageDao
    abstract fun auditDao(): AuditDao
    abstract fun pendingChangeDao(): PendingChangeDao

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
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
