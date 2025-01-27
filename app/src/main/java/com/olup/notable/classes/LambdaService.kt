package com.olup.notable

import android.util.Log
import com.olup.notable.db.ChatMessageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class LambdaService(private val context: android.content.Context) {
    companion object {
        private const val TAG = "LambdaService"
        private const val API_ENDPOINT = "https://wdrgmh7px4.execute-api.ap-southeast-2.amazonaws.com/api/deepseek/chat/completions"
        private const val BACKUP_API_ENDPOINT = "https://wdrgmh7px4.execute-api.ap-southeast-2.amazonaws.com/api/openrouter/chat/completions"
    }

    private val chatRepository: ChatMessageRepository

    init {
        chatRepository = ChatMessageRepository(context)
    }

    private suspend fun makeApiRequest(endpoint: String, pageId: String, messages: List<JSONObject>): String {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        Log.d(TAG, "url: $url")
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        
        when (endpoint) {
            BACKUP_API_ENDPOINT -> {
                connection.setRequestProperty("HTTP-Referer", "android-app://com.olup.notable")
                connection.setRequestProperty("X-Title", "Notable App")
            }
        }
        
        connection.doOutput = true
        val timeoutMillis = when (endpoint) {
            API_ENDPOINT -> 10000 // 10 seconds for primary endpoint
            BACKUP_API_ENDPOINT -> 30000 // 30 seconds for backup endpoint
            else -> 10000 // default to 10 seconds
        }
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis

        Log.d(TAG, "Making request to: $endpoint")

        // Prepare request body based on endpoint
        val requestBody = JSONObject().apply {
            when (endpoint) {
                API_ENDPOINT -> {
                    put("model", "deepseek-chat")
                    put("messages", JSONArray(messages.map { it }))
                    put("stream", false)
                }
                BACKUP_API_ENDPOINT -> {
                    put("model", "anthropic/claude-3.5-sonnet")
                    put("messages", JSONArray(messages.map { it }))
                    put("stream", false)
                    // Add any additional parameters required by the backup API
                    put("temperature", 0.7)
                    put("max_tokens", 1000)
                }
                else -> throw Exception("Unknown endpoint: $endpoint")
            }
        }

        val requestBodyString = requestBody.toString()
        Log.d(TAG, "Request body: $requestBodyString")

        // Send request
        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(requestBodyString)
            writer.flush()
        }

        // Check response code
        val responseCode = connection.responseCode
        Log.d(TAG, "Response code: $responseCode")

        // Read response
        val responseText = try {
            if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading response from $endpoint", e)
            throw Exception("Connection error for $endpoint: ${e.message}")
        }

        Log.d(TAG, "Raw response: $responseText")

        if (responseCode != HttpURLConnection.HTTP_OK) {
            val errorMessage = try {
                val errorJson = JSONObject(responseText)
                "Error: ${errorJson.optString("error", "Unknown error")}"
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing error response", e)
                "Error: HTTP $responseCode - $responseText"
            }
            throw Exception(errorMessage)
        }

        val jsonResponse = JSONObject(responseText)
        Log.d(TAG, "Parsed response: $jsonResponse")

        if (jsonResponse.has("error")) {
            val error = jsonResponse.getJSONObject("error")
            throw Exception("Error: ${error.optString("message", "Unknown error")}")
        }

        val choices = jsonResponse.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.optJSONObject("message")
            if (message != null) {
                // Handle both standard content and potential refusal field
                val content = message.optString("content")
                val refusal = message.optString("refusal")
                return if (refusal.isNotEmpty()) refusal else content.ifEmpty { "No content in response" }
            }
        }

        throw Exception("Error: Unexpected response format")
    }

    suspend fun getCompletion(pageId: String, prompt: String): String = withContext(Dispatchers.IO) {
        try {
            // Initialize system message if needed
            chatRepository.initializeSystemMessage(pageId)

            // Add user message
            chatRepository.addMessage(pageId, "user", prompt)

            // Get all messages for the request
            val messages = chatRepository.getMessagesAsJson(pageId)

            // Try primary endpoint first
            try {
                val response = makeApiRequest(API_ENDPOINT, pageId, messages)
                chatRepository.addMessage(pageId, "assistant", response)
                return@withContext response
            } catch (e: Exception) {
                Log.e(TAG, "Primary endpoint failed, trying backup endpoint", e)
                
                // Try backup endpoint
                try {
                    val response = makeApiRequest(BACKUP_API_ENDPOINT, pageId, messages)
                    chatRepository.addMessage(pageId, "assistant", response)
                    return@withContext response
                } catch (e2: Exception) {
                    Log.e(TAG, "Backup endpoint also failed", e2)
                    return@withContext "Error: Both endpoints failed - ${e2.message}"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getCompletion", e)
            return@withContext "Error: ${e.message}"
        }
    }
}
