package com.pocketagent.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ToolRunEntity::class
    ],
    version = 2,  // H17: bumped from 1 to 2 with proper migration (was fallbackToDestructiveMigration)
    exportSchema = true
)
abstract class PocketDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun toolRunDao(): ToolRunDao

    companion object {
        const val NAME = "pocketagent.db"
    }
}
