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
        private const val API_ENDPOINT = "https://wy81l8o885.execute-api.ap-southeast-2.amazonaws.com/prod/chat/completions"
    }

    private val chatRepository: ChatMessageRepository

    init {
        chatRepository = ChatMessageRepository(context)
    }

    suspend fun getCompletion(pageId: String, prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_ENDPOINT)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // Log the connection details
            Log.d(TAG, "Making request to: $API_ENDPOINT")

            // Initialize system message if needed
            chatRepository.initializeSystemMessage(pageId)

            // Add user message
            chatRepository.addMessage(pageId, "user", prompt)

            // Get all messages for the request
            val messages = chatRepository.getMessagesAsJson(pageId)

            val requestBody = JSONObject().apply {
                put("model", "deepseek-chat")
                put("messages", JSONArray(messages))
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
                Log.e(TAG, "Error reading response", e)
                return@withContext "Error reading response: ${e.message}"
            }

            Log.d(TAG, "Response code: $responseCode")
            Log.d(TAG, "Raw response: $responseText")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext try {
                    val errorJson = JSONObject(responseText)
                    "Error: ${errorJson.optString("error", "Unknown error")}"
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing error response", e)
                    "Error: HTTP $responseCode - $responseText"
                }
            }

            try {
                val jsonResponse = JSONObject(responseText)
                Log.d(TAG, "Parsed response: $jsonResponse")

                if (jsonResponse.has("error")) {
                    val error = jsonResponse.getJSONObject("error")
                    return@withContext "Error: ${error.optString("message", "Unknown error")}"
                }

                val choices = jsonResponse.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val firstChoice = choices.getJSONObject(0)
                    val message = firstChoice.optJSONObject("message")
                    if (message != null) {
                        // Add assistant's response to history
                        chatRepository.addMessage(pageId, "assistant", message.optString("content"))
                        return@withContext message.optString("content", "No content in response")
                    }
                }

                Log.e(TAG, "Unexpected response format: $responseText")
                return@withContext "Error: Unexpected response format"
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing response", e)
                return@withContext "Error parsing response: ${e.message}"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error calling Lambda", e)
            return@withContext "Error: ${e.message}"
        }
    }
}
