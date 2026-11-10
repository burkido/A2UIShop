package com.example.a2uishop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class CheckoutButtonStyle { Primary, Outlined, Text }

/**
 * The shop's own button: full width, square-ish corners, and a cart icon on the primary style.
 * When [errorMessage] is set, the button is disabled and the message is shown below it.
 */
@Composable
fun CheckoutButton(
    onClick: () -> Unit,
    style: CheckoutButtonStyle,
    errorMessage: String?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val enabled = errorMessage == null
    val shape = RoundedCornerShape(12.dp)
    Column(modifier) {
        when (style) {
            CheckoutButtonStyle.Primary -> Button(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                content()
            }
            CheckoutButtonStyle.Outlined -> OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                modifier = Modifier.fillMaxWidth(),
                content = content,
            )
            CheckoutButtonStyle.Text -> TextButton(onClick = onClick, enabled = enabled, content = content)
        }
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}
