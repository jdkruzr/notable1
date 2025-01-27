package com.olup.notable.db

import android.content.Context
import org.json.JSONObject

class ChatMessageRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context).chatMessageDao()

    fun initializeSystemMessage(pageId: String) {
        val systemMessage = ChatMessage(
            pageId = pageId,
            role = "system",
            content = "This is handwritten text recognized from a tablet device. " +
                    "The text might contain recognition errors, so try your best to understand the context " +
                    "and interpret what the user is trying to write.",
            order = 0
        )
        if (db.getMessagesForPage(pageId).isEmpty()) {
            db.create(systemMessage)
        }
    }

    fun addMessage(pageId: String, role: String, content: String) {
        val messages = db.getMessagesForPage(pageId)
        val order = messages.maxOfOrNull { it.order }?.plus(1) ?: 0
        val message = ChatMessage(
            pageId = pageId,
            role = role,
            content = content,
            order = order
        )
        db.create(message)
    }

    fun getMessagesAsJson(pageId: String): List<JSONObject> {
        return db.getMessagesForPage(pageId).map { message ->
            JSONObject().apply {
                put("role", message.role)
                put("content", message.content)
            }
        }
    }
}
