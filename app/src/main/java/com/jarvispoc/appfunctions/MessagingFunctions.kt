package com.jarvispoc.appfunctions

import android.content.Context
import android.content.Intent
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionContext

/**
 * Functions for interacting with messaging apps.
 */
class MessagingFunctions(private val androidContext: Context) {

    /**
     * Opens the messaging composer with the provided text.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun openMessagingComposer(
        context: AppFunctionContext,
        text: String,
        recipient: String?
    ): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        return try {
            androidContext.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
