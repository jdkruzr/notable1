package com.olup.notable.classes

import android.content.Context
import kotlinx.coroutines.delay

class LambdaService(context: Context) {
    suspend fun sendSentence(sentence: String): String {
        // Simulate network delay
        delay(500)
        // Return mock response
        return """{"status":"success","processed_sentence":"$sentence"}"""
    }
}
