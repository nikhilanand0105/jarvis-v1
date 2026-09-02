package com.jarvispoc.flows

import com.jarvispoc.core.FlowResult
import com.jarvispoc.service.ActionExecutor
import kotlinx.coroutines.delay
import java.net.URLEncoder

/**
 * Legacy wrapper for Amazon shopping flow, now powered by [EcommerceOrderFlow].
 */
class AmazonOrderFlow(private val searchQuery: String) : Flow {

    override val name: String = "Amazon Add to Cart"

    private val delegate = EcommerceOrderFlow(searchQuery, EcommerceConfig.AMAZON)

    override suspend fun run(x: ActionExecutor, autoConfirm: Boolean): FlowResult {
        return delegate.run(x, autoConfirm)
    }

    data class ProductDetails(
        val title: String,
        val price: String?,
        val rating: String?,
    )

    /**
     * Scrape is still needed for AppFunctions.
     */
    suspend fun scrapeProductDetails(x: ActionExecutor, query: String): ProductDetails? {
        val config = EcommerceConfig.AMAZON
        val pkg = config.packages.firstOrNull { x.isInstalled(it) } ?: return null
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = String.format(config.searchUrlTemplate, encoded)
        if (!x.launchUri(searchUrl, pkg)) return null
        if (!x.awaitPackage(pkg)) return null
        delay(2000)
        x.dismissInterstitials()

        var matchedTitle: String? = null
        val maxScanScrolls = 2
        for (scroll in 0..maxScanScrolls) {
            val snapshot = x.snapshot()
            // Using internal helpers via reflection or just copying them back if needed.
            // Actually, I'll just make the helpers in EcommerceOrderFlow public.
            val match = EcommerceOrderFlow.findMatchingProductCard(snapshot, query, config.platformName)
            if (match != null) {
                matchedTitle = match.second
                x.tap(match.first)
                break
            }
            if (scroll < maxScanScrolls) {
                x.swipe(500, 1800, 500, 700)
                delay(1_200)
            }
        }

        if (matchedTitle == null) return null
        delay(2000)

        val snapshot = x.snapshot()
        var price: String? = null
        var rating: String? = null

        for (node in snapshot) {
            val text = node.label.trim()
            if (price == null && (text.contains("₹") || text.contains("Rs."))) {
                price = text
            }
            if (rating == null && (text.contains("out of 5 stars") || text.contains("★"))) {
                rating = text
            }
        }

        return ProductDetails(matchedTitle, price, rating)
    }
}
