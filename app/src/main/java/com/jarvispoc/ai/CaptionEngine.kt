package com.jarvispoc.ai

import android.content.Context
import android.graphics.Bitmap

/**
 * Isolates caption generation from everything else.
 *
 * If on-device Gemma-3n turns out too slow to be usable, swapping to a hosted
 * model is a one-class change behind this interface — no flow, executor or UI
 * code needs to move.
 */
interface CaptionEngine {

    /** Human-readable description of what will actually run, for the UI. */
    fun status(): String

    suspend fun caption(bitmap: Bitmap, style: String): Result<String>

    fun close()
}

/**
 * Process-scoped holder for the engine.
 *
 * Deliberately NOT owned by the Activity: loading the weights costs several GB
 * and tens of seconds, and an Activity-scoped instance would be rebuilt from
 * scratch on every configuration change. The handle lives as long as the
 * process and is never closed from UI code — see [CaptionEngine.close].
 */
object CaptionEngines {

    @Volatile
    private var instance: CaptionEngine? = null

    fun shared(context: Context): CaptionEngine =
        instance ?: synchronized(this) {
            instance ?: GemmaCaptionEngine(context.applicationContext).also { instance = it }
        }
}
