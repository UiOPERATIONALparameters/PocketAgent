package com.pocketagent.storage

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    /**
     * H17 FIX: Real DB migration instead of fallbackToDestructiveMigration().
     *
     * The v1 schema (version 1) is the original. v2 adds:
     *   - messages.isStreaming column (already existed in v1, but with the H18 fix we
     *     now reset it on every app launch — see the callback below)
     *
     * Future migrations should add Migration_N_to_N+1 objects here.
     * NEVER use fallbackToDestructiveMigration() in production — it wipes user data.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // No schema changes needed for v2 — the v1 schema already has all columns.
            // This migration exists to bump the version so that future schema changes
            // have a proper migration path.
            // We do NOT drop or recreate any tables.
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PocketDatabase =
        Room.databaseBuilder(
            context,
            PocketDatabase::class.java,
            PocketDatabase.NAME
        )
            .addMigrations(MIGRATION_1_2)
            // H18 FIX: on first open after a crash, reset all isStreaming=true messages
            // to isStreaming=false. Without this, crashed streams stay "spinning" forever.
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("UPDATE messages SET isStreaming = 0 WHERE isStreaming = 1")
                }
            })
            .build()

    @Provides
    fun provideConversationDao(db: PocketDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: PocketDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideToolRunDao(db: PocketDatabase): ToolRunDao = db.toolRunDao()
}
