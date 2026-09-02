package com.jarvispoc.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.jarvispoc.core.AgentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalLlmEngine(private val context: Context) {
    private val mutex = Mutex()

    data class StructuredIntent(
        val targetApp: String,
        val product: String?,
        val priceLimit: String?,
        val recipient: String?,
        val time: String?,
        val tone: String?,
        val domain: String?,
        val goal: String?,
        val target: String?,
        val raw: String
    )

    @Volatile
    private var engine: LlmInference? = null

    suspend fun reply(incomingMessage: String): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val llm = engine ?: createEngine().also { engine = it }

                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(TOP_K)
                    .setTemperature(TEMPERATURE)
                    .build()

                LlmInferenceSession.createFromOptions(llm, sessionOptions).use { session ->
                    val prompt = buildPrompt(incomingMessage)
                    session.addQueryChunk(prompt)

                    val started = System.currentTimeMillis()
                    val raw = session.generateResponse()
                    AgentLog.info("reply generated in ${System.currentTimeMillis() - started}ms")
                    tidy(raw)
                }
            }.onFailure {
                AgentLog.error("reply failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    suspend fun parseIntent(query: String): Result<StructuredIntent> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val llm = engine ?: createEngine().also { engine = it }

                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(TOP_K)
                    .setTemperature(0.1f)
                    .build()

                LlmInferenceSession.createFromOptions(llm, sessionOptions).use { session ->
                    val prompt = buildIntentPrompt(query)
                    session.addQueryChunk(prompt)

                    val started = System.currentTimeMillis()
                    val raw = session.generateResponse()
                    AgentLog.info("intent parsed in ${System.currentTimeMillis() - started}ms")
                    parseStructuredIntent(raw, query)
                }
            }.onFailure {
                AgentLog.error("intent parsing failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    private fun createEngine(): LlmInference {
        val model = ModelLocator.resolve(context)
            ?: error("Model not found.")

        AgentLog.info("loading ${model.name} for chat")
        val started = System.currentTimeMillis()

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.absolutePath)
            .setMaxTopK(TOP_K)
            .build()

        return LlmInference.createFromOptions(context, options).also {
            AgentLog.success("chat model loaded in ${System.currentTimeMillis() - started}ms")
        }
    }

    private fun buildPrompt(msg: String): String = """
        You are a helpful assistant replying to a message from a friend.
        Friend says: "$msg"
        Write a short, natural, and friendly reply.
        No quotation marks, no preamble. Just the reply.
    """.trimIndent()

    private fun buildIntentPrompt(query: String): String = """
        Extract parameters from the user request.
        Request: "$query"
        
        Output format:
        target_app: <Amazon|Flipkart|Blinkit|Instagram|Telegram|Chain|Alarm|Timer|Music|Call|CyberSec|Unknown>
        product: <value or null>
        price_limit: <value or null>
        recipient: <value or null, e.g., name or number>
        time: <value or null, e.g., 7:30 AM or 10 minutes>
        tone: <value or null>
        domain: <value or null, e.g. CYBERSEC>
        goal: <value or null, e.g. INVESTIGATE_ALERT, ESCALATE_INCIDENT>
        target: <value or null, e.g. HIGHEST_RISK, 198.51.100.1, INC-999>
        
        Use 'Chain' if the request involves multiple steps, like searching then drafting or sharing.
        Use 'Alarm' for setting alarms, 'Timer' for countdowns, and 'Music' for playing songs or artists.
        For 'Music', put the song name or artist in the 'product' field.
        Use 'Call' for making phone calls.
    """.trimIndent()

    private fun parseStructuredIntent(raw: String, originalQuery: String): StructuredIntent {
        val cleanRaw = raw.replace("*", "").replace("_", "").replace("#", "").replace("`", "")
        val lines = cleanRaw.lines()
        val map = lines.associate { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim().lowercase().replace(" ", "_")
                val value = parts[1].trim().removePrefix("<").removeSuffix(">")
                key to value
            } else {
                "" to ""
            }
        }

        fun getOrNull(key: String): String? = map[key]?.takeIf { it != "null" && it.isNotBlank() }

        return StructuredIntent(
            targetApp = getOrNull("target_app") ?: "Unknown",
            product = getOrNull("product"),
            priceLimit = getOrNull("price_limit"),
            recipient = getOrNull("recipient"),
            time = getOrNull("time"),
            tone = getOrNull("tone"),
            domain = getOrNull("domain"),
            goal = getOrNull("goal"),
            target = getOrNull("target"),
            raw = originalQuery
        )
    }

    private fun tidy(raw: String): String {
        return raw.substringBefore("<end_of_turn>")
            .substringBefore("<eos>")
            .substringBefore("<start_of_turn>")
            .trim()
            .removeSurrounding("\"")
            .removePrefix("Reply:")
            .trim()
    }

    fun close() {
        if (!mutex.isLocked) {
            runCatching { engine?.close() }
            engine = null
        }
    }

    companion object {
        const val TOP_K = 40
        const val TEMPERATURE = 0.8f
    }
}
