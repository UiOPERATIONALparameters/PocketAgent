package com.pocketagent.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ToolRunEntity::class
    ],
    version = 3,  // v6: bumped from 2 to 3 for subagent support (parentId, isSubagent, status, summary)
    exportSchema = true
)
abstract class PocketDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun toolRunDao(): ToolRunDao

    companion object {
        const val NAME = "pocketagent.db"

        /** v6 migration: add subagent columns to conversations table. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN parentId TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN isSubagent INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN status TEXT NOT NULL DEFAULT 'active'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN summary TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_parentId ON conversations(parentId)")
            }
        }
    }
}
