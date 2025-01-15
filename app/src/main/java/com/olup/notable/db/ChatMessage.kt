package com.olup.notable.db

import androidx.room.*
import java.util.*

@Entity(
    tableName = "ChatMessage",
    foreignKeys = [ForeignKey(
        entity = Page::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("pageId"),
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("pageId")]
)
data class ChatMessage(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val pageId: String,
    val role: String, // "system", "user", or "assistant"
    val content: String,
    @ColumnInfo(defaultValue = "CURRENT_TIMESTAMP") val createdAt: Date = Date(),
    val order: Int // To maintain message sequence
)

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM ChatMessage WHERE pageId = :pageId ORDER BY `order` ASC")
    fun getMessagesForPage(pageId: String): List<ChatMessage>

    @Insert
    fun create(message: ChatMessage)

    @Query("SELECT COUNT(*) FROM ChatMessage WHERE pageId = :pageId")
    fun getMessageCount(pageId: String): Int

    @Query("DELETE FROM ChatMessage WHERE pageId = :pageId AND `order` = (SELECT MIN(`order`) FROM ChatMessage WHERE pageId = :pageId AND role != 'system')")
    fun deleteOldestNonSystemMessage(pageId: String)

    @Transaction
    fun addMessageWithLimit(message: ChatMessage, maxMessages: Int) {
        val currentCount = getMessageCount(message.pageId)
        if (currentCount >= maxMessages) {
            deleteOldestNonSystemMessage(message.pageId)
        }
        create(message)
    }
}
