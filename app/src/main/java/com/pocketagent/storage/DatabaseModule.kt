package com.pocketagent.storage

import android.content.Context
import androidx.room.Room
import com.pocketagent.storage.db.ConversationDao
import com.pocketagent.storage.db.MessageDao
import com.pocketagent.storage.db.PocketDatabase
import com.pocketagent.storage.db.ToolRunDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PocketDatabase =
        Room.databaseBuilder(
            context,
            PocketDatabase::class.java,
            PocketDatabase.NAME
        )
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides
    fun provideConversationDao(db: PocketDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: PocketDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideToolRunDao(db: PocketDatabase): ToolRunDao = db.toolRunDao()
}
