package com.pocketagent.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ToolRunEntity::class
    ],
    version = 1,
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
