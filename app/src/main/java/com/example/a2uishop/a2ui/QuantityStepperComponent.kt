package com.example.a2uishop.a2ui

import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.a2uishop.ui.components.QuantityStepper

/**
 * A brand-new component that isn't in the Basic Catalog: a −/+ stepper.
 *
 * The property definitions do two jobs. They become the JSON schema the agent reads, and they
 * extract typed values at runtime.
 */
object QuantityStepperComponent : A2uiComponent {

    // The agent reads the name and description to decide when to use this component.
    override val name = "QuantityStepper"
    override val description =
        "A compact minus/plus stepper for choosing a small whole number, such as how many " +
            "items to order. Bind 'value' to a data model path so the user can change it."

    /** Dynamic: a literal or a `{"path": ...}` binding into the surface's data model. */
    private val valueProperty = A2uiProperty.dynamicNumber(
        key = "value",
        required = true,
        description = "The current quantity. Bind it to a path to make it editable.",
    )

    /** Static: the agent sends the value inline. */
    private val minProperty = A2uiProperty.number(key = "min", defaultValue = 0, description = "Lowest value.")
    private val maxProperty = A2uiProperty.number(key = "max", defaultValue = 10, description = "Highest value.")

    override val properties = listOf(valueProperty, minProperty, maxProperty)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val min = properties[minProperty]?.toInt() ?: 0
        val max = properties[maxProperty]?.toInt() ?: 10

        // bind() reads the current value and recomposes whenever the data model changes.
        val value = properties.bind(valueProperty)?.toInt() ?: min
        // bindUpdater() writes back to the data model (two-way binding). It is null when the
        // agent sent a literal instead of a path, which makes the stepper read-only.
        val onValueChange = properties.bindUpdater(valueProperty)

        QuantityStepper(
            value = value.coerceIn(min, max),
            range = min..max,
            onValueChange = onValueChange?.let { update -> { newValue: Int -> update(newValue) } },
            modifier = modifier,
        )
    }
}
