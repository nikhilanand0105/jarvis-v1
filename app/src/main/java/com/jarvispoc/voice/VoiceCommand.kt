package com.jarvispoc.voice

import com.jarvispoc.ai.LocalLlmEngine

/**
 * What the user asked for, extracted from a spoken phrase.
 *
 * Keyword matching, not an LLM. Same reasoning as the scripted flows: the open
 * question in this POC is whether we can drive real apps reliably, and a parser
 * whose behaviour you can predict from reading it keeps that question isolated.
 * Gemma is expensive enough already without spending a second inference pass on
 * a sentence we can classify with `contains`.
 */
data class VoiceCommand(
    val raw: String,
    val target: Target,
    val useMostRecentPhoto: Boolean,
    val autoCaption: Boolean,
    val tone: String,
    /** Product to search for, when the phrase named one. Null otherwise. */
    val searchQuery: String?,
    /** Time for alarm or timer, when specified. */
    val time: String? = null,
    /** Recipient for call or message. */
    val recipient: String? = null,
) {
    enum class Target { INSTAGRAM, AMAZON, FLIPKART, BLINKIT, CHAIN, ALARM, TIMER, MUSIC, CALL, UNKNOWN }

    /** One-line description of what we understood, for the trace. */
    val summary: String
        get() = "target=$target, mostRecentPhoto=$useMostRecentPhoto, " +
            "autoCaption=$autoCaption, tone='$tone', product=${searchQuery?.let { "'$it'" } ?: "none"}"

    companion object {

        private const val DEFAULT_TONE = "warm, understated, a little witty"

        // No bare "gram": it is already covered by "instagram" and would also
        // fire on program / telegram / grammar.
        private val INSTAGRAM_WORDS = listOf("instagram", "insta", " ig ")
        private val AMAZON_WORDS = listOf("amazon")
        private val FLIPKART_WORDS = listOf("flipkart", "flip kart")
        private val BLINKIT_WORDS = listOf("blinkit", "blink it")
        private val ALARM_WORDS = listOf("alarm", "wake me", "wake up", "remind me", "alert", "set a", "set an")
        private val TIMER_WORDS = listOf("timer", "countdown", "tier", "time me", "stopwatch", "start a", "start an")
        private val MUSIC_WORDS = listOf("music", "play", "song", "artist", "beats", "tune", "spotify", "youtube")
        private val CALL_WORDS = listOf("call", "dial", "phone", "contact", "ring")
        private val SHOPPING_WORDS = listOf("order", "buy", "purchase", "cart", "search", "find", "get", "shop")
        
        private val RECENT_WORDS = listOf(
            "most recent", "latest", "last photo", "last picture", "newest",
            "recent photo", "recent picture", "just took", "just clicked",
        )
        private val CAPTION_WORDS = listOf("caption", "description", "write something")

        /** Spoken tone adjectives mapped to a prompt fragment. */
        private val TONES = listOf(
            "funny" to "funny and playful",
            "witty" to "dry and witty",
            "professional" to "professional and restrained",
            "formal" to "professional and restrained",
            "casual" to "casual and conversational",
            "poetic" to "lyrical and image-led",
            "short" to "very short, under ten words",
            "minimal" to "very short, under ten words",
            "excited" to "upbeat and enthusiastic",
        )

        /** Longest first, so "order me a" wins over "order". */
        private val PRODUCT_TRIGGERS = listOf(
            "search for", "search",
            "order me a", "order me an", "order me", "order a", "order an", "order the", "order",
            "buy me a", "buy me an", "buy me", "buy a", "buy an", "buy the", "buy",
            "purchase a", "purchase an", "purchase the", "purchase",
            "get me a", "get me an", "get me the", "get me",
            "add a", "add an", "add",
        ).sortedByDescending { it.length }

        /** Trailing noise to strip off an extracted product. */
        private val TRAILING_NOISE = listOf(
            "on amazon india", "from amazon india", "on the amazon app",
            "to my amazon cart", "to my cart", "to the cart", "to cart",
            "on amazon", "from amazon", "in amazon", "on amazon in",
            "on flipkart", "from flipkart", "in flipkart", "on flip kart", "from flip kart",
            "on blinkit", "from blinkit", "in blinkit", "on blink it", "from blink it",
            "for me", "please", "now",
        )

        private val LEADING_ARTICLES = listOf("a ", "an ", "the ", "some ", "me ")

        /**
         * Placeholders that parse as a product but name nothing. Searching
         * Amazon for "something" and walking it to checkout is worse than
         * asking again.
         */
        private val VAGUE_PRODUCTS = setOf(
            "something", "anything", "stuff", "things", "it", "that", "this", "one",
        )

        /** Shorter than this is almost certainly a mis-transcription, not a product. */
        private const val MIN_PRODUCT_LENGTH = 3

        private val PRICE_PATTERN = Regex(
            """(?i)\b(under|below)\s*(?:₹|rs\.?|inr)?\s*(\d[\d,]*(?:\.\d+)?)\s*(?:rupees|rs\.?|inr)?"""
        )

        private fun cleanProductString(raw: String): String {
            var rest = raw.trim()
            var changed = true
            while (changed) {
                changed = false
                for (noise in TRAILING_NOISE) {
                    if (rest.endsWith(noise)) {
                        rest = rest.removeSuffix(noise).trim()
                        changed = true
                    }
                }
                for (article in LEADING_ARTICLES) {
                    if (rest.startsWith(article)) {
                        rest = rest.removePrefix(article).trim()
                        changed = true
                    }
                }
            }
            return rest.trim(' ', ',', '.', '!', '?')
        }

        /**
         * Pulls the product out of e.g. "order a usb c cable on amazon".
         * If a price limit is specified (e.g. "under ₹1000", "below 1500"),
         * extracts the product and price limit into "<product> under <price>".
         *
         * Returns null when nothing survives — "place an order on amazon" names
         * no product, and guessing one when money is involved is not a service.
         */
        private fun extractProduct(text: String): String? {
            val trigger = PRODUCT_TRIGGERS.firstOrNull { text.contains(" $it ") } ?: return null
            var rest = text.substringAfter(" $trigger ").trim()

            rest = cleanProductString(rest)

            val priceMatch = PRICE_PATTERN.find(rest)
            if (priceMatch != null) {
                val rawProduct = rest.substring(0, priceMatch.range.first)
                val product = cleanProductString(rawProduct)
                val price = priceMatch.groupValues[2].replace(",", "").trim()
                return if (product.length >= MIN_PRODUCT_LENGTH && product !in VAGUE_PRODUCTS && price.isNotBlank()) {
                    "$product under $price"
                } else {
                    null
                }
            }

            val product = cleanProductString(rest)
            return product.takeIf { it.length >= MIN_PRODUCT_LENGTH && it !in VAGUE_PRODUCTS }
        }

        fun fromStructuredIntent(intent: LocalLlmEngine.StructuredIntent): VoiceCommand {
            val target = when (intent.targetApp.lowercase()) {
                "instagram" -> Target.INSTAGRAM
                "amazon" -> Target.AMAZON
                "flipkart" -> Target.FLIPKART
                "blinkit" -> Target.BLINKIT
                "chain", "composite" -> Target.CHAIN
                "alarm" -> Target.ALARM
                "timer" -> Target.TIMER
                "music" -> Target.MUSIC
                "call" -> Target.CALL
                else -> Target.UNKNOWN
            }

            val query = if (intent.product != null) {
                if (intent.priceLimit != null) "${intent.product} under ${intent.priceLimit}"
                else intent.product
            } else if (target == Target.MUSIC) extractMusicQuery(intent.raw)
            else null

            return VoiceCommand(
                raw = intent.raw,
                target = target,
                useMostRecentPhoto = intent.raw.lowercase().contains("recent") || intent.raw.lowercase().contains("latest"),
                autoCaption = intent.raw.lowercase().contains("caption") || target == Target.INSTAGRAM,
                tone = intent.tone ?: DEFAULT_TONE,
                searchQuery = query,
                time = intent.time,
                recipient = intent.recipient
            )
        }

        fun parse(spoken: String): VoiceCommand {
            // Pad so " ig " can match at either end without a word-boundary regex.
            val text = " ${spoken.lowercase().trim()} "

            val target = when {
                INSTAGRAM_WORDS.any { text.contains(it) } -> Target.INSTAGRAM
                FLIPKART_WORDS.any { text.contains(it) } -> Target.FLIPKART
                BLINKIT_WORDS.any { text.contains(it) } -> Target.BLINKIT
                ALARM_WORDS.any { text.contains(it) } -> Target.ALARM
                TIMER_WORDS.any { text.contains(it) } -> Target.TIMER
                MUSIC_WORDS.any { text.contains(it) } -> Target.MUSIC
                CALL_WORDS.any { text.contains(it) } -> Target.CALL
                AMAZON_WORDS.any { text.contains(it) } -> Target.AMAZON
                SHOPPING_WORDS.any { text.contains(it) } -> Target.AMAZON // Default shopping to Amazon if no platform named
                else -> Target.UNKNOWN
            }

            val tone = TONES.firstOrNull { text.contains(it.first) }?.second ?: DEFAULT_TONE

            val isShopping = target in listOf(Target.AMAZON, Target.FLIPKART, Target.BLINKIT)

            val query = if (isShopping) extractProduct(text)
            else if (target == Target.MUSIC) extractMusicQuery(spoken.trim())
            else null

            return VoiceCommand(
                raw = spoken.trim(),
                target = target,
                useMostRecentPhoto = RECENT_WORDS.any { text.contains(it) },
                // "post it on instagram" implies a caption is wanted even when
                // the word itself is never spoken.
                autoCaption = CAPTION_WORDS.any { text.contains(it) } ||
                    target == Target.INSTAGRAM,
                tone = tone,
                searchQuery = query,
                time = if (target == Target.ALARM || target == Target.TIMER) extractTimeFallback(text) else null,
                recipient = if (target == Target.CALL) extractRecipientFallback(text) else null
            )
        }

        private fun extractMusicQuery(spoken: String): String {
            val lower = spoken.lowercase()
            var clean = lower
            val triggers = listOf("play music by", "play music", "play some", "play a", "play the", "play")
            
            for (trigger in triggers) {
                if (lower.startsWith(trigger)) {
                    clean = lower.removePrefix(trigger).trim()
                    break
                }
            }
            
            return clean.removeSuffix("on spotify").removeSuffix("on youtube").trim()
        }

        private fun extractTimeFallback(text: String): String? {
            // Expanded extraction for the regex fallback
            // Handles: "7:30 am", "8pm", "10 minutes", "5 min", "30 seconds", "at 7", "for 8"
            val timeRegex = Regex("""(\d{1,2}(?::\d{2})?\s*(?:am|pm|minutes?|min|seconds?|sec))""", RegexOption.IGNORE_CASE)
            val simpleDigitRegex = Regex("""\b(\d{1,2})\b""")
            
            return timeRegex.find(text)?.value ?: simpleDigitRegex.find(text)?.value
        }

        private fun extractRecipientFallback(text: String): String? {
            // Simple recipient extraction: anything after "call" or "dial"
            val triggers = listOf("call", "dial")
            for (trigger in triggers) {
                if (text.contains(" $trigger ")) {
                    return text.substringAfter(" $trigger ").trim().split(" ").firstOrNull()
                }
            }
            return null
        }
    }
}
