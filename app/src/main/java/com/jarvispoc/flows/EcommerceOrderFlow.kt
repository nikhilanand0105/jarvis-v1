package com.jarvispoc.flows

import com.jarvispoc.core.AgentLog
import com.jarvispoc.core.FlowResult
import com.jarvispoc.core.Selector
import com.jarvispoc.core.UiNode
import com.jarvispoc.core.query
import com.jarvispoc.service.ActionExecutor
import kotlinx.coroutines.delay
import java.net.URLEncoder

/**
 * A generic flow for ecommerce platforms (Search -> Add to Cart -> Checkout).
 */
class EcommerceOrderFlow(
    private val searchQuery: String,
    private val config: EcommerceConfig
) : Flow {

    override val name: String = "${config.platformName} Shopping"

    override suspend fun run(x: ActionExecutor, autoConfirm: Boolean): FlowResult {
        val pkg = config.packages.firstOrNull { x.isInstalled(it) }
            ?: return FlowResult.Failed("launch", "no ${config.platformName} app installed")
        AgentLog.info("using ${config.platformName} package $pkg")

        val query = searchQuery.trim().take(MAX_QUERY_LENGTH)
        if (query.isBlank()) {
            return FlowResult.Failed("search", "search query is empty")
        }
        AgentLog.info("searching ${config.platformName} for: \"$query\"")

        // 1 — Deep-link straight into in-app search results
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = String.format(config.searchUrlTemplate, encoded)
        if (!x.launchUri(searchUrl, pkg)) {
            return FlowResult.Failed("search", "could not open the search deep link")
        }
        if (!x.awaitPackage(pkg)) {
            return FlowResult.Failed(
                "search",
                "${config.platformName} never came to the foreground",
            )
        }
        delay(SETTLE_MS)
        x.dismissInterstitials()

        // 1b — If the app opened search suggestions, tap the first one to load results
        config.searchSuggestion?.let { q ->
            val suggestion = x.awaitNode(q, timeoutMs = 2_500)
            if (suggestion != null) {
                AgentLog.info("Tapping search suggestion '${suggestion.label}'...")
                x.tap(suggestion)
                delay(SETTLE_MS + 500)
                x.dismissInterstitials()
            }
        }

        // 2 — Find matching product and open it
        var matchedProductNode: UiNode? = null
        var matchedTitle: String = ""

        val maxScanScrolls = 4
        for (scroll in 0..maxScanScrolls) {
            x.dismissInterstitials(rounds = 1)
            val snapshot = x.snapshot()
            val match = findMatchingProductCard(snapshot, query, config.platformName)
            if (match != null) {
                matchedProductNode = match.first
                matchedTitle = match.second
                AgentLog.success("found matching product: \"$matchedTitle\"")
                break
            }
            if (scroll < maxScanScrolls) {
                AgentLog.info("no match on visible screen (scroll $scroll/$maxScanScrolls) — scrolling down")
                x.scrollForward()
                delay(1_200)
            }
        }

        if (matchedProductNode == null) {
            AgentLog.error("no search result reasonably matched \"$query\"")
            return FlowResult.Failed("search_match", "no matching product found for \"$query\"")
        }

        AgentLog.step("Opening product: \"$matchedTitle\"")
        if (!x.tapAt(matchedProductNode.centerX, matchedProductNode.centerY) && !x.tap(matchedProductNode)) {
            return FlowResult.Failed("results", "could not tap product card")
        }
        delay(SETTLE_MS + 500)
        x.dismissInterstitials()

        // 3 — Verify and Add to Cart
        AgentLog.step("Locating product actions ('${config.addToCart.label}' or '${config.buyNow.label}')...")
        var addToCart = x.scrollUntilVisible(config.addToCart, maxScrolls = 8, settleMs = 1200)
        var buyNow: UiNode? = null
        var skippedCart = false
        
        if (addToCart == null) {
            AgentLog.info("'${config.addToCart.label}' not found — checking for '${config.buyNow.label}'")
            buyNow = x.scrollUntilVisible(config.buyNow, maxScrolls = 2) // already scrolled once
        }

        if (addToCart == null && buyNow == null && config.platformName == "Amazon") {
            AgentLog.info("Actions not found — checking for 'See all buying options' fallback")
            val buyingOptions = query(
                "buying options",
                Selector(text = "See all buying options"),
                Selector(textContains = "buying options"),
                Selector(textContains = "See all options")
            )
            val optionsNode = x.scrollUntilVisible(buyingOptions, maxScrolls = 3)
            if (optionsNode != null) {
                AgentLog.step("Found 'buying options' — tapping to reveal specific sellers")
                x.tap(optionsNode)
                delay(2000)
                x.dismissInterstitials()
                addToCart = x.scrollUntilVisible(config.addToCart, maxScrolls = 4)
                if (addToCart == null) {
                    buyNow = x.scrollUntilVisible(config.buyNow, maxScrolls = 2)
                }
            }
        }

        val actionNode = addToCart ?: buyNow
        if (actionNode == null) {
            return FlowResult.Failed("product", "neither '${config.addToCart.label}' nor '${config.buyNow.label}' found")
        }

        if (buyNow != null) {
            skippedCart = true
            AgentLog.step("Using 'Buy Now' path (skipping cart)...")
        }

        if (!x.tapAt(actionNode.centerX, actionNode.centerY) && !x.tap(actionNode)) {
            return FlowResult.Failed("product", "could not tap '${actionNode.label}'")
        }
        delay(SETTLE_MS)
        x.dismissInterstitials(rounds = 2)

        // 4 — Go to Cart
        if (!skippedCart) {
            AgentLog.step("Navigating to cart...")
            val proceedToBuy = x.awaitNode(config.proceedToBuy, timeoutMs = 4_000)
                ?: x.scrollUntilVisible(config.proceedToBuy, maxScrolls = 4)

            if (proceedToBuy != null) {
                if (!x.tapAt(proceedToBuy.centerX, proceedToBuy.centerY) && !x.tap(proceedToBuy)) {
                    return FlowResult.Failed("cart", "could not tap '${config.proceedToBuy.label}'")
                }
            } else if (config.cartUrl != null) {
                x.launchUri(config.cartUrl, pkg)
            } else {
                return FlowResult.Failed("cart", "could not find way to cart/checkout")
            }

            if (!x.awaitPackage(pkg)) {
                return FlowResult.Failed("cart", "left ${config.platformName} on the way to cart")
            }
            delay(SETTLE_MS + 1_000)
            x.dismissInterstitials()
        } else {
            AgentLog.info("'Buy Now' used — assuming direct navigation to checkout")
            delay(SETTLE_MS + 1_000)
            x.dismissInterstitials()
        }

        // 5 — Checkout and COD
        var codSelected = false
        var continueTapped = false

        for (step in 1..MAX_CHECKOUT_STEPS) {
            x.dismissInterstitials(rounds = 1)
            
            val cod = x.awaitNode(config.codOption, timeoutMs = 1_500)
                ?: if (x.awaitNode(config.paymentScreen, timeoutMs = 1_500) != null) {
                    x.scrollUntilVisible(config.codOption, maxScrolls = 6)
                } else null

            if (cod != null) {
                if (cod.checked || cod.selected) {
                    AgentLog.info("COD option is already selected.")
                } else {
                    AgentLog.step("Found COD option — selecting it")
                    x.tapAt(cod.centerX, cod.centerY)
                    x.tap(cod)
                    delay(800)
                }
                codSelected = true

                // If on Amazon, ensure Pay Balance is not overriding us
                if (config.platformName == "Amazon") {
                    val payBalance = x.snapshot().firstOrNull { 
                        it.text.contains("Amazon Pay balance", ignoreCase = true) ||
                        it.contentDescription.contains("Amazon Pay balance", ignoreCase = true)
                    }
                    if (payBalance != null && (payBalance.checked || payBalance.selected)) {
                        AgentLog.warn("Amazon Pay balance is checked — might conflict with COD")
                        // If it's a checkbox next to the balance, tapping it might toggle it off.
                        // For now just logging it to see if it's the culprit.
                    }
                }

                config.codSuboption?.let { sub ->
                    val subNode = x.awaitNode(sub, timeoutMs = 1_500)
                    if (subNode != null) {
                        x.tapAt(subNode.centerX, subNode.centerY)
                        x.tap(subNode)
                        delay(600)
                    }
                }
                codSelected = true

                val paymentAdvance = x.awaitNode(config.paymentAdvance, timeoutMs = 2_500)
                    ?: x.scrollUntilVisible(config.paymentAdvance, maxScrolls = 6)

                if (paymentAdvance != null) {
                    AgentLog.step("Tapping payment advance: '${paymentAdvance.label}'")
                    x.tapAt(paymentAdvance.centerX, paymentAdvance.centerY)
                    x.tap(paymentAdvance)
                    continueTapped = true
                    delay(SETTLE_MS + 1_000)
                    x.dismissInterstitials(rounds = 2)
                    // Continue to next loop iteration to verify final review screen
                    continue
                }
            }

            // Check if we are on the final review screen
            val placeOrder = x.awaitNode(config.placeOrder, timeoutMs = 1_500)
            if (placeOrder != null) {
                if (codSelected && continueTapped) {
                    AgentLog.success("Reached final review screen with COD selected.")
                    return FlowResult.AwaitingUser("Parked on final review screen with COD selected.")
                } else {
                    AgentLog.warn("Reached final review screen but COD status is incomplete (selected=$codSelected, continue=$continueTapped)")
                }
            }

            val advance = x.awaitNode(config.checkoutAdvance, timeoutMs = 2_000)
            if (advance != null) {
                AgentLog.step("Advancing through checkout step: '${advance.label}'")
                x.tapAt(advance.centerX, advance.centerY)
                x.tap(advance)
                delay(SETTLE_MS + 1_000)
                x.dismissInterstitials()
                continue
            }

            AgentLog.info("Checkout step $step: searching for payment or advance buttons...")
            delay(1000)
        }

        return if (codSelected) {
            FlowResult.AwaitingUser("COD selected, but could not reach final review screen after $MAX_CHECKOUT_STEPS steps. Please review manually.")
        } else {
            FlowResult.Failed("checkout", "could not complete COD checkout — never found or could not select COD option")
        }
    }

    companion object {
        const val MAX_CHECKOUT_STEPS = 10
        const val MAX_QUERY_LENGTH = 80
        const val SETTLE_MS = 1_800L

        private val STOP_WORDS = setOf(
            "a", "an", "the", "for", "in", "to", "and", "with", "of", "on", "me",
            "add", "cart", "buy", "order", "please", "my", "from", "item", "product",
            "under", "below", "which", "is", "colour", "color"
        )

        private val OPTIONAL_ATTRIBUTES = setOf(
            "white", "black", "blue", "red", "green", "yellow", "pink", "grey", "gray",
            "large", "small", "medium", "xl", "xxl", "cotton", "silk", "leather",
            "fast", "quick", "original", "genuine", "best"
        )

        fun findMatchingProductCard(nodes: List<UiNode>, query: String, platform: String): Pair<UiNode, String>? {
            val sortedNodes = nodes
                .filter {
                    it.bounds.height() > 20 && it.bounds.width() > 20 &&
                        it.bounds.top >= 360 && it.bounds.bottom <= 2900 &&
                        !it.viewId.contains("search", ignoreCase = true) &&
                        !it.viewId.contains("chrome", ignoreCase = true) &&
                        !it.viewId.contains("suggestion", ignoreCase = true) &&
                        !it.viewId.contains("autocomplete", ignoreCase = true)
                }
                .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))

            for (node in sortedNodes) {
                val label = node.label.trim()
                if (label.length < 5) continue
                if (label.startsWith("http") || label.contains("Search $platform") || label.contains("Deliver to")) continue
                if (label.equals("Sponsored", ignoreCase = true) || label.equals("Results", ignoreCase = true) || label.contains("filters", ignoreCase = true)) continue

                if (isReasonableMatch(label, query)) {
                    return Pair(node, label)
                }
            }
            return null
        }

        /**
         * Checks if [candidate] title is a reasonable match for [query].
         * 
         * Logic:
         * 1. Extract keywords from query (excluding stop words).
         * 2. Split into 'Mandatory' (core nouns) and 'Optional' (descriptive adjectives).
         * 3. Must match ALL Mandatory keywords.
         * 4. Descriptive words improve the score but aren't required to trigger a click,
         *    trusting that the platform's search results are already filtered for them.
         */
        fun isReasonableMatch(candidate: String, query: String): Boolean {
            val allTokens = extractKeywords(query)
            if (allTokens.isEmpty()) return false

            val mandatory = allTokens.filter { it !in OPTIONAL_ATTRIBUTES }
            val optional = allTokens.filter { it in OPTIONAL_ATTRIBUTES }

            val candidateNormalized = " " + normalize(candidate) + " "
            
            // 1. Must match at least most of the mandatory keywords (core product name)
            var mandatoryMatched = 0
            for (token in mandatory) {
                if (candidateNormalized.contains(token)) {
                    mandatoryMatched++
                }
            }
            
            val minMandatory = when {
                mandatory.size <= 2 -> mandatory.size
                else -> (mandatory.size * 3) / 4
            }
            
            if (mandatoryMatched < minMandatory) return false

            // 2. Optional attributes (color, speed) are a bonus. 
            // If the query was EXCLUSIVELY optional attributes (e.g. "something white"), 
            // then we need at least one match.
            if (mandatory.isEmpty()) {
                return optional.any { candidateNormalized.contains(it) }
            }

            return true
        }

        private fun extractKeywords(text: String): List<String> {
            val cleaned = text.replace("(?i)\\b(under|below)\\s*\\d+\\b".toRegex(), " ")
            return normalize(cleaned)
                .split("\\s+".toRegex())
                .filter { it.length >= 1 && it !in STOP_WORDS }
        }

        private fun normalize(text: String): String {
            return text.lowercase()
                .replace("[^a-z0-9\\s]".toRegex(), " ")
                .trim()
        }
    }
}
