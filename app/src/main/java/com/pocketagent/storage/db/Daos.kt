package com.pocketagent.storage.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE isSubagent = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    // v6: subagent queries
    @Query("SELECT * FROM conversations WHERE parentId = :parentId ORDER BY createdAt ASC")
    suspend fun getSubagents(parentId: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE parentId = :parentId ORDER BY createdAt ASC")
    fun observeSubagents(parentId: String): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET status = :status, summary = :summary, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSubagentStatus(id: String, status: String, summary: String?, updatedAt: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getForConversation(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}

@Dao
interface ToolRunDao {
    @Query("SELECT * FROM tool_runs WHERE messageId = :messageId ORDER BY createdAt ASC")
    fun observeForMessage(messageId: String): Flow<List<ToolRunEntity>>

    @Query("SELECT * FROM tool_runs WHERE messageId = :messageId ORDER BY createdAt ASC")
    suspend fun getForMessage(messageId: String): List<ToolRunEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: ToolRunEntity)

    @Query("DELETE FROM tool_runs WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)
}
