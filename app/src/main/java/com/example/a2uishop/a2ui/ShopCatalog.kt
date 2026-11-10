package com.example.a2uishop.a2ui

import android.content.Context
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1

/** The catalog ID the agent uses in `createSurface`. Any unique URI works. */
const val SHOP_CATALOG_ID = "https://example.com/a2ui/shop/v1/catalog.json"

/**
 * The contract with the agent: the only components and functions it can use to build UI.
 *
 * Starts from the Material 3 Basic Catalog, swaps in our own Button, and adds two components
 * the agent didn't know about before.
 */
fun shopCatalog(context: Context): A2uiCatalog {
    val basicCatalog = materialA2uiBasicCatalogV1(
        // A2UI doesn't ship media libraries, so we plug in our own.
        image = MaterialA2uiBasicCatalogV1Defaults.image(CoilImageRenderer),
        video = MaterialA2uiBasicCatalogV1Defaults.video(NoMediaRenderer),
        audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer(NoMediaRenderer),
        // Client-side functions: formatString, formatCurrency, required, email, openUrl, ...
        functions = A2uiBasicCatalogV1.Functions(context),
        // Replace a Basic Catalog component with our own design.
        button = CheckoutButtonComponent,
    )
    return A2uiCatalog(
        catalogId = SHOP_CATALOG_ID,
        // Extend the catalog with custom components.
        components = basicCatalog.components + ProductCardComponent + QuantityStepperComponent,
        functions = basicCatalog.functions,
    )
}
