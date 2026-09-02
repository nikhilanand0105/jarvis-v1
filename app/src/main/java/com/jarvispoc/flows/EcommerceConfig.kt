package com.jarvispoc.flows

import com.jarvispoc.core.Query
import com.jarvispoc.core.Selector
import com.jarvispoc.core.query

/**
 * Configuration for an ecommerce platform's automated flow.
 */
data class EcommerceConfig(
    val platformName: String,
    val packages: List<String>,
    val searchUrlTemplate: String, // e.g. "https://www.amazon.in/s?k=%s"
    val cartUrl: String?,
    val searchSuggestion: Query?,
    val addToCart: Query,
    val buyNow: Query,
    val proceedToBuy: Query,
    val paymentScreen: Query,
    val codOption: Query,
    val codSuboption: Query?,
    val checkoutAdvance: Query,
    val paymentAdvance: Query,
    val placeOrder: Query,
) {
    companion object {
        val AMAZON = EcommerceConfig(
            platformName = "Amazon",
            packages = listOf(
                "in.amazon.mShop.android.shopping",
                "com.amazon.mShop.android.shopping",
            ),
            searchUrlTemplate = "https://www.amazon.in/s?k=%s",
            cartUrl = "https://www.amazon.in/gp/cart/view.html",
            searchSuggestion = query(
                "search suggestion",
                Selector(id = "sac-suggestion-row-1"),
                Selector(id = "sac-suggestion-row-1-cell-1"),
                Selector(id = "sac-suggestion-row-2"),
                Selector(id = "sac-suggestion-row-2-cell-1"),
                Selector(id = "sac-suggestion-row-3"),
                Selector(id = "search_suggestions_frame_layout"),
            ),
            addToCart = query(
                "Add to Cart",
                Selector(id = "add_to_cart"),
                Selector(id = "add-to-cart-button"),
                Selector(id = "atcb-atc-btn"),
                Selector(id = "atc-button"),
                Selector(id = "add_to_basket"),
                Selector(text = "Add to Cart"),
                Selector(text = "Add to Basket"),
                Selector(text = "Pre-order now"),
                Selector(textContains = "Add to Cart"),
                Selector(textContains = "Add to cart"),
                Selector(textContains = "Add to Basket"),
                Selector(textContains = "Add to basket"),
                Selector(textContains = "Pre-order"),
                Selector(desc = "Add to Cart"),
                Selector(desc = "Add to cart"),
                Selector(desc = "Add to Basket"),
                Selector(desc = "Add to basket"),
                Selector(text = "Select options"),
                Selector(text = "Choose options"),
                Selector(textContains = "Select options"),
                Selector(textContains = "Choose options"),
            ),
            buyNow = query(
                "Buy Now",
                Selector(id = "buy_now_button"),
                Selector(id = "buyNow"),
                Selector(id = "buy-now-button"),
                Selector(text = "Buy Now"),
                Selector(textContains = "Buy Now"),
                Selector(textContains = "Buy now"),
            ),
            proceedToBuy = query(
                "Proceed to Buy",
                Selector(textContains = "Proceed to Buy"),
                Selector(textContains = "Proceed to checkout"),
                Selector(id = "proceed_to_checkout"),
                Selector(textContains = "Proceed to"),
            ),
            paymentScreen = query(
                "payment screen",
                Selector(textContains = "payment method"),
                Selector(textContains = "Select a payment"),
                Selector(textContains = "Other payment options"),
                Selector(textContains = "Net Banking"),
                Selector(textContains = "Credit or debit card"),
                Selector(textContains = "Credit/Debit"),
                Selector(textContains = "Amazon Pay balance"),
                Selector(textContains = "Pay on Delivery"),
                Selector(textContains = "Cash on Delivery"),
            ),
            codOption = query(
                "Cash / Pay on Delivery",
                Selector(id = "cod"),
                Selector(id = "pay_on_delivery"),
                Selector(textContains = "Pay on Delivery"),
                Selector(textContains = "Cash on Delivery"),
                Selector(textContains = "Cash/Card on Delivery"),
                Selector(desc = "Pay on Delivery"),
                Selector(desc = "Cash on Delivery"),
            ),
            codSuboption = query(
                "Cash sub-option",
                Selector(text = "Cash"),
                Selector(text = "Cash on Delivery"),
            ),
            checkoutAdvance = query(
                "checkout continue",
                Selector(textContains = "Deliver to this address"),
                Selector(textContains = "Use this address"),
                Selector(textContains = "Use this payment method"),
                Selector(textContains = "Choose your delivery"),
                Selector(text = "Continue"),
                Selector(textContains = "Continue"),
                Selector(text = "Proceed"),
                Selector(textContains = "Proceed"),
            ),
            paymentAdvance = query(
                "payment continue",
                Selector(textContains = "Use this payment method"),
                Selector(text = "Continue"),
                Selector(textContains = "Continue"),
                Selector(text = "Proceed"),
                Selector(textContains = "Proceed"),
            ),
            placeOrder = query(
                "Place your order",
                Selector(textContains = "Place your order"),
                Selector(textContains = "Place Your Order"),
                Selector(textContains = "Place order"),
            ),
        )

        val FLIPKART = EcommerceConfig(
            platformName = "Flipkart",
            packages = listOf("com.flipkart.android"),
            searchUrlTemplate = "https://www.flipkart.com/search?q=%s",
            cartUrl = "https://www.flipkart.com/viewcart",
            searchSuggestion = query(
                "search suggestion",
                Selector(textContains = "in All Categories"),
                Selector(id = "txt_title"), // Common suggestion ID
            ),
            addToCart = query(
                "Add to Cart",
                Selector(text = "Add to Cart"),
                Selector(text = "ADD TO CART"),
                Selector(desc = "Add to Cart"),
            ),
            buyNow = query(
                "Buy Now",
                Selector(text = "Buy Now"),
                Selector(text = "BUY NOW"),
                Selector(textContains = "Buy Now"),
                Selector(textContains = "Buy now"),
                Selector(desc = "Buy Now"),
                Selector(id = "buy_now"),
                Selector(id = "buyNow"),
            ),
            proceedToBuy = query(
                "Place Order",
                Selector(text = "Place Order"),
                Selector(text = "PLACE ORDER"),
                Selector(textContains = "Place Order"),
                Selector(textContains = "PLACE ORDER"),
            ),
            paymentScreen = query(
                "Payments",
                Selector(text = "Payments"),
                Selector(text = "PAYMENTS"),
                Selector(textContains = "Select Payment Method"),
            ),
            codOption = query(
                "Cash on Delivery",
                Selector(text = "Cash on Delivery"),
                Selector(textContains = "Cash on Delivery"),
            ),
            codSuboption = null,
            checkoutAdvance = query(
                "Continue",
                Selector(text = "Continue"),
                Selector(text = "CONTINUE"),
            ),
            paymentAdvance = query(
                "Continue",
                Selector(text = "Continue"),
                Selector(text = "CONTINUE"),
            ),
            placeOrder = query(
                "Place Order",
                Selector(text = "Place Order"),
                Selector(text = "PLACE ORDER"),
            ),
        )

        val BLINKIT = EcommerceConfig(
            platformName = "Blinkit",
            packages = listOf("com.grofers.customerapp"),
            searchUrlTemplate = "https://blinkit.com/s/?q=%s",
            cartUrl = null,
            searchSuggestion = null,
            addToCart = query(
                "Add",
                Selector(text = "ADD"),
                Selector(id = "tv_add"),
            ),
            buyNow = query(
                "Buy Now",
                Selector(textContains = "Buy Now"),
                Selector(textContains = "Buy now"),
            ),
            proceedToBuy = query(
                "View Cart",
                Selector(textContains = "Items in cart"),
                Selector(textContains = "View Cart"),
            ),
            paymentScreen = query(
                "Payment",
                Selector(text = "Payment"),
            ),
            codOption = query(
                "Cash on Delivery",
                Selector(text = "Cash on Delivery"),
            ),
            codSuboption = null,
            checkoutAdvance = query(
                "Proceed",
                Selector(text = "Proceed"),
            ),
            paymentAdvance = query(
                "Pay",
                Selector(textContains = "Pay"),
            ),
            placeOrder = query(
                "Place Order",
                Selector(text = "Place Order"),
            ),
        )
    }
}
