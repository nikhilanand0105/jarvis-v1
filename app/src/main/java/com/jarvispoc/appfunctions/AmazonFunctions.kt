package com.jarvispoc.appfunctions

import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionSerializable
import com.jarvispoc.flows.AmazonOrderFlow
import com.jarvispoc.service.JarvisAccessibilityService

/**
 * Functions for interacting with Amazon.
 */
class AmazonFunctions {

    /**
     * Searches Amazon for a product and returns its details.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getProductDetails(
        context: AppFunctionContext,
        query: String
    ): AmazonProductResponse? {
        val service = JarvisAccessibilityService.instance ?: return null
        val details = AmazonOrderFlow("").scrapeProductDetails(service.executor, query) ?: return null
        
        return AmazonProductResponse(
            title = details.title,
            price = details.price,
            rating = details.rating
        )
    }
}

/**
 * Amazon product details.
 */
@AppFunctionSerializable
data class AmazonProductResponse(
    val title: String,
    val price: String?,
    val rating: String?
)
