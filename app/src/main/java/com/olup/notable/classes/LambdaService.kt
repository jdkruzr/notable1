package com.olup.notable.classes

import android.content.Context
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.lambdainvoker.LambdaFunction
import com.amazonaws.mobileconnectors.lambdainvoker.LambdaInvokerFactory
import com.amazonaws.regions.Regions

interface SentenceProcessor {
    @LambdaFunction
    fun processSentence(sentence: String): String
}

class LambdaService(context: Context) {
    private val credentialsProvider = CognitoCachingCredentialsProvider(
        context,
        context.getString(R.string.aws_cognito_identity_pool_id),
        Regions.fromName(context.getString(R.string.aws_region))
    )

    private val factory = LambdaInvokerFactory(
        context,
        Regions.fromName(context.getString(R.string.aws_region)),
        credentialsProvider
    )

    private val processor: SentenceProcessor = factory.build(SentenceProcessor::class.java)

    suspend fun sendSentence(sentence: String): String {
        return try {
            processor.processSentence(sentence)
        } catch (e: Exception) {
            "Error processing sentence: ${e.message}"
        }
    }
}
