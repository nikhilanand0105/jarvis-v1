package com.jarvispoc.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.jarvispoc.core.AgentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device captioning via MediaPipe's LLM Inference API and Gemma-3n E2B.
 *
 * The [LlmInference] handle is expensive to build (multi-GB weights) so it is
 * created once and kept warm; a fresh [LlmInferenceSession] is used per call
 * so context never leaks between photos. A mutex enforces single-flight —
 * concurrent calls into the same handle are not safe.
 *
 * NOTE: MediaPipe's GenAI task is in maintenance mode upstream; LiteRT-LM is
 * its successor. Fine for a POC, but expect to migrate this one class.
 */
class GemmaCaptionEngine(private val context: Context) : CaptionEngine {

    private val mutex = Mutex()

    @Volatile
    private var engine: LlmInference? = null

    override fun status(): String = ModelLocator.describe(context)

    override suspend fun caption(bitmap: Bitmap, style: String): Result<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val llm = engine ?: createEngine().also { engine = it }

                    val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(TOP_K)
                        .setTemperature(TEMPERATURE)
                        .setGraphOptions(
                            GraphOptions.builder()
                                .setEnableVisionModality(true)
                                .build()
                        )
                        .build()

                    LlmInferenceSession.createFromOptions(llm, sessionOptions).use { session ->
                        session.addQueryChunk(buildPrompt(style))
                        session.addImage(BitmapImageBuilder(downscale(bitmap)).build())

                        val started = System.currentTimeMillis()
                        val raw = session.generateResponse()
                        AgentLog.info("caption generated in ${System.currentTimeMillis() - started}ms")
                        tidy(raw)
                    }
                }.onFailure {
                    AgentLog.error("caption failed: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }

    private fun createEngine(): LlmInference {
        val model = ModelLocator.resolve(context)
            ?: error("Model not found. adb push the .litertlm to ${ModelLocator.preferredPath(context)}")

        AgentLog.info("loading ${model.name} (${model.length() / (1024 * 1024)} MB) — this takes a while")
        val started = System.currentTimeMillis()

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.absolutePath)
            .setMaxTopK(TOP_K)
            .setMaxNumImages(1)
            .build()

        return LlmInference.createFromOptions(context, options).also {
            AgentLog.success("model loaded in ${System.currentTimeMillis() - started}ms")
        }
    }

    private fun buildPrompt(style: String): String = """
        Write an Instagram caption for the attached photo.
        Tone: $style
        Rules:
        - One or two short sentences.
        - Then 3 to 5 relevant hashtags.
        - Do not narrate or literally describe the photo.
        - No quotation marks, no preamble, no explanation.
        Output only the caption text.
    """.trimIndent()

    /**
     * Keeps the vision encoder's input modest. Also forces a software-backed
     * bitmap: MediaPipe cannot read pixels out of a HARDWARE-config bitmap.
     */
    private fun downscale(source: Bitmap): Bitmap {
        val longestEdge = maxOf(source.width, source.height)
        val scaled = if (longestEdge <= MAX_EDGE_PX) {
            source
        } else {
            val ratio = MAX_EDGE_PX.toFloat() / longestEdge
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
        return if (scaled.config == Bitmap.Config.ARGB_8888) {
            scaled
        } else {
            scaled.copy(Bitmap.Config.ARGB_8888, false) ?: scaled
        }
    }

    private fun tidy(raw: String): String {
        val cleaned = raw
            .substringBefore("<end_of_turn>")
            .substringBefore("<eos>")
            .substringBefore("<start_of_turn>")
            .trim()
            .removeSurrounding("\"")
            .removePrefix("Caption:")
            .trim()
        return cleaned
    }

    /**
     * Best-effort teardown.
     *
     * Closing the native handle while [caption] is mid-`generateResponse` is a
     * hard crash, not an exception, so a generation in flight wins: we leak the
     * handle rather than take the process down. In practice this only runs at
     * process teardown, where the leak is irrelevant.
     */
    override fun close() {
        if (mutex.isLocked) {
            AgentLog.warn("caption in flight — leaving the model handle open rather than risking a native crash")
            return
        }
        runCatching { engine?.close() }
        engine = null
    }

    private companion object {
        const val TOP_K = 64
        const val TEMPERATURE = 0.8f
        const val MAX_EDGE_PX = 768
    }
}
