package com.example.a2uishop.a2ui

import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.a2uishop.ui.components.CheckoutButton
import com.example.a2uishop.ui.components.CheckoutButtonStyle

/**
 * Replaces the Basic Catalog's Button with the shop's own button.
 *
 * The agent still sends a normal `Button`. The interface already defines its name, description,
 * and schema, and passes us validated values, so all we write is the UI. That includes the
 * `checks`: they arrive already evaluated, and showing them is now our job.
 */
object CheckoutButtonComponent : A2uiBasicCatalogV1.Button {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        childId: String,
        variant: A2uiBasicCatalogV1.Button.Variant,
        action: Map<String, Any?>,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>,
        modifier: Modifier,
    ) {
        CheckoutButton(
            onClick = { dispatchAction(action) }, // goes to processor.outboundEvents
            style = when (variant) {
                A2uiBasicCatalogV1.Button.Variant.Primary -> CheckoutButtonStyle.Primary
                A2uiBasicCatalogV1.Button.Variant.Borderless -> CheckoutButtonStyle.Text
                else -> CheckoutButtonStyle.Outlined
            },
            // The first failing check disables the button and explains why.
            errorMessage = checks.firstOrNull { !it.condition }?.message,
            modifier = modifier,
        ) {
            // The label is another component (usually Text) that the agent chose. Render it as is.
            val child = observeA2uiComponentState(childId)
            if (child is A2uiComponentState.Success) {
                A2uiComponent(child.component)
            }
        }
    }
}
