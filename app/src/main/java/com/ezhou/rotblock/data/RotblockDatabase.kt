package com.ezhou.rotblock.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ezhou.rotblock.audit.AuditEvent
import com.ezhou.rotblock.policy.Policy

@Database(
    entities = [Policy::class, UsageSessionEntity::class, AuditEvent::class, PendingChange::class],
    version = 1,
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
                .build()
                .also { instance = it }
        }
    }
}
