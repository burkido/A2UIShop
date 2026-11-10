package com.example.a2uishop.a2ui

import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.a2uishop.ui.components.ProductCard

/**
 * A domain component for the shop. One `ProductCard` replaces about ten Basic Catalog
 * components (Card, Column, Image, Row, Text, Button...), and the design rules live here,
 * not in the agent's JSON.
 */
object ProductCardComponent : A2uiComponent {

    override val name = "ProductCard"
    override val description =
        "A product tile with a photo, brand, name, price, rating and one button. Use it for " +
            "search results, usually inside a horizontal List with a template over the products."

    // Dynamic properties, so a template can bind them to each item, e.g. {"path": "name"}.
    private val productIdProperty = A2uiProperty.dynamicString(key = "productId", required = true, description = "Product ID.")
    private val nameProperty = A2uiProperty.dynamicString(key = "name", required = true, description = "Product name.")
    private val brandProperty = A2uiProperty.dynamicString(key = "brand", description = "Brand, shown above the name.")
    private val priceProperty = A2uiProperty.dynamicNumber(key = "price", required = true, description = "Price as a number.")
    private val imageProperty = A2uiProperty.dynamicString(key = "imageUrl", description = "Photo URL.")
    private val ratingProperty = A2uiProperty.dynamicNumber(key = "rating", description = "Average rating from 0 to 5.")
    private val badgeProperty = A2uiProperty.dynamicString(key = "badge", description = "Short label such as 'New'.")

    // Static properties, sent inline.
    private val currencyProperty = A2uiProperty.string(
        key = "currency",
        description = "ISO 4217 currency code. Defaults to EUR.",
    )
    private val actionLabelProperty = A2uiProperty.string(
        key = "actionLabel",
        description = "Button label. Defaults to 'Add to cart'.",
    )

    // An action, like a Button's.
    private val actionProperty = A2uiProperty.action(
        key = "action",
        required = true,
        description = "Sent to the agent when the user taps the button. The card adds productId, " +
            "name, price and imageUrl to the context, so there is no need to bind them.",
    )

    override val properties = listOf(
        productIdProperty, nameProperty, brandProperty, priceProperty, imageProperty, ratingProperty, badgeProperty,
        currencyProperty, actionLabelProperty, actionProperty,
    )

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val productId = properties.bind(productIdProperty)
        val name = properties.bind(nameProperty).orEmpty()
        val price = properties.bind(priceProperty)?.toDouble() ?: 0.0
        val imageUrl = properties.bind(imageProperty)
        val action = properties[actionProperty]?.withProduct(
            mapOf("productId" to productId, "name" to name, "price" to price, "imageUrl" to imageUrl),
        )
        ProductCard(
            name = name,
            brand = properties.bind(brandProperty),
            price = price,
            currency = properties[currencyProperty] ?: "EUR",
            imageUrl = imageUrl,
            rating = properties.bind(ratingProperty)?.toDouble(),
            badge = properties.bind(badgeProperty),
            actionLabel = properties[actionLabelProperty] ?: "Add to cart",
            onAction = { action?.let { dispatchAction(it) } },
            modifier = modifier,
        )
    }
}

/**
 * Adds the card's product to the action's event context.
 *
 * In 1.0.0-alpha02, a relative path such as `{"path": "id"}` in an action's context doesn't
 * resolve inside a template, and one unresolved value empties the whole context. The card already
 * knows its product through `bind`, which does see the template item, so it fills in the context
 * itself.
 */
private fun Map<String, Any?>.withProduct(product: Map<String, Any?>): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val event = this["event"] as? Map<String, Any?> ?: return this
    @Suppress("UNCHECKED_CAST")
    val context = event["context"] as? Map<String, Any?> ?: emptyMap()
    return this + ("event" to event + ("context" to context + product))
}
