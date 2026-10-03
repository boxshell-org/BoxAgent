package com.boxagent.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,           // user | assistant | tool | system
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "tool_calls")
data class ToolCallRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val name: String,
    val argsJson: String,
    val resultJson: String,
    val risk: String,
    val durationMs: Long,
    val ok: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "audit_log")
data class AuditEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,           // tool_call | auth | daemon | lifecycle | error
    val detail: String,
    val ok: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface ConversationDao {
    @Insert suspend fun insert(c: Conversation): Long
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun all(): Flow<List<Conversation>>
    @Query("UPDATE conversations SET title = :title, updatedAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, title: String, ts: Long = System.currentTimeMillis())
    @Query("UPDATE conversations SET updatedAt = :ts WHERE id = :id")
    suspend fun bump(id: Long, ts: Long = System.currentTimeMillis())
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MessageDao {
    @Insert suspend fun insert(m: Message): Long
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY id")
    fun forConversation(cid: Long): Flow<List<Message>>
    @Query("DELETE FROM messages WHERE conversationId = :cid")
    suspend fun clear(cid: Long)
}

@Dao
interface ToolCallDao {
    @Insert suspend fun insert(t: ToolCallRecord): Long
    @Query("SELECT * FROM tool_calls WHERE conversationId = :cid ORDER BY id")
    fun forConversation(cid: Long): Flow<List<ToolCallRecord>>
}

@Dao
interface AuditDao {
    @Insert suspend fun insert(a: AuditEntry): Long
    @Query("SELECT * FROM audit_log ORDER BY id DESC LIMIT :limit")
    fun latest(limit: Int = 500): Flow<List<AuditEntry>>
    @Query("DELETE FROM audit_log")
    suspend fun clear()
    @Query("SELECT * FROM audit_log ORDER BY id")
    suspend fun exportAll(): List<AuditEntry>
}

@Database(
    entities = [Conversation::class, Message::class, ToolCallRecord::class, AuditEntry::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun toolCalls(): ToolCallDao
    abstract fun audit(): AuditDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class.java,
                    "boxagent.db",
                ).build().also { instance = it }
            }
    }
}
