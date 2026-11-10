package com.example.a2uishop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A minus/plus stepper. A null [onValueChange] makes it read-only. */
@Composable
fun QuantityStepper(
    value: Int,
    range: IntRange,
    onValueChange: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalIconButton(
            onClick = { onValueChange?.invoke(value - 1) },
            enabled = onValueChange != null && value > range.first,
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }

        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 24.dp),
        )

        FilledTonalIconButton(
            onClick = { onValueChange?.invoke(value + 1) },
            enabled = onValueChange != null && value < range.last,
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}
