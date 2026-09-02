package com.jarvispoc.appfunctions

import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionSerializable
import com.jarvispoc.ai.LocalLlmEngine

/**
 * Functions for parsing natural language intents into structured data.
 */
class IntentFunctions(private val llmEngine: LocalLlmEngine) {

    /**
     * Parses a natural language request into structured parameters.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun parseStructuredIntent(
        context: AppFunctionContext,
        query: String
    ): StructuredIntentResponse {
        val result = llmEngine.parseIntent(query).getOrNull()
        return StructuredIntentResponse(
            targetApp = result?.targetApp ?: "Unknown",
            product = result?.product,
            priceLimit = result?.priceLimit,
            recipient = result?.recipient,
            tone = result?.tone,
            domain = result?.domain,
            goal = result?.goal,
            target = result?.target
        )
    }
}

/**
 * Structured representation of a user's intent.
 */
@AppFunctionSerializable
data class StructuredIntentResponse(
    val targetApp: String,
    val product: String?,
    val priceLimit: String?,
    val recipient: String?,
    val tone: String?,
    val domain: String?,
    val goal: String?,
    val target: String?
)
