package com.boxagent.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** Saved skill (see com.boxagent.app.skills.Skill for field meanings). */
@Entity(tableName = "skills", indices = [Index(value = ["name"], unique = true)])
data class SkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val title: String,
    val description: String,
    val instructions: String,
    /** Packages, newline-separated. */
    val apps: String,
    val paramsJson: String,
    val stepsJson: String,
    val enabled: Boolean,
    val draft: Boolean,
    val source: String,
    val uses: Int,
    val runs: Int,
    val successes: Int,
    val lastUsedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
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

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills")
    fun all(): Flow<List<SkillEntity>>
    @Query("SELECT * FROM skills")
    suspend fun list(): List<SkillEntity>
    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun byId(id: Long): SkillEntity?
    @Query("SELECT * FROM skills WHERE name = :name")
    suspend fun byName(name: String): SkillEntity?
    @Insert suspend fun insert(s: SkillEntity): Long
    @Update suspend fun update(s: SkillEntity)
    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: Long)
    @Query(
        "UPDATE skills SET uses = uses + 1, runs = runs + :run, " +
            "successes = successes + :ok, lastUsedAt = :ts WHERE name = :name",
    )
    suspend fun recordUse(name: String, run: Int, ok: Int, ts: Long = System.currentTimeMillis())
}

@Database(
    entities = [
        Conversation::class, Message::class, ToolCallRecord::class, AuditEntry::class,
        SkillEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun toolCalls(): ToolCallDao
    abstract fun audit(): AuditDao
    abstract fun skills(): SkillDao

    companion object {
        /** v2: skills table. SQL mirrors Room's generated schema exactly
         *  (it validates migrated tables against it). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach(db::execSQL)
            }
        }
        internal val MIGRATION_1_2_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `skills` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `instructions` TEXT NOT NULL, `apps` TEXT NOT NULL, `paramsJson` TEXT NOT NULL, `stepsJson` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `draft` INTEGER NOT NULL, `source` TEXT NOT NULL, `uses` INTEGER NOT NULL, `runs` INTEGER NOT NULL, `successes` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_skills_name` ON `skills` (`name`)",
        )

        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class.java,
                    "boxagent.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
