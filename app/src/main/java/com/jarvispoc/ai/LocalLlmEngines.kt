package com.jarvispoc.ai

import android.content.Context

/**
 * Process-scoped holder for the local LLM engine.
 */
object LocalLlmEngines {

    @Volatile
    private var instance: LocalLlmEngine? = null

    fun shared(context: Context): LocalLlmEngine =
        instance ?: synchronized(this) {
            instance ?: LocalLlmEngine(context.applicationContext).also { instance = it }
        }
}
