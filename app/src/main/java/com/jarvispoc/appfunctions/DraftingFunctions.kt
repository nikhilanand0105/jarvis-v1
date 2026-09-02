package com.jarvispoc.appfunctions

import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionSerializable
import com.jarvispoc.ai.LocalLlmEngine

/**
 * Functions for drafting content using AI.
 */
class DraftingFunctions(private val llmEngine: LocalLlmEngine) {

    /**
     * Drafts a comparison note for products.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun draftComparisonNote(
        context: AppFunctionContext,
        productA: String,
        priceA: String?,
        productB: String?,
        priceB: String?
    ): String {
        val prompt = if (productB != null) {
            "Draft a concise comparison note for these two products:\n1. $productA ($priceA)\n2. $productB ($priceB)\nKeep it short and friendly."
        } else {
            "Draft a concise summary for this product:\n$productA ($priceA)\nKeep it short and friendly."
        }
        
        return llmEngine.reply(prompt).getOrDefault("Could not draft note.")
    }
}
