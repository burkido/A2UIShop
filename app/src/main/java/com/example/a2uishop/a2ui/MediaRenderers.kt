package com.example.a2uishop.a2ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.a2ui.catalog.A2uiAudioPlayerRenderer
import androidx.compose.material3.a2ui.catalog.A2uiImageRenderer
import androidx.compose.material3.a2ui.catalog.A2uiVideoRenderer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Loads the agent's images with Coil.
 *
 * A missing image (offline, bad URL) is a normal situation on a phone, so we show a quiet
 * placeholder instead of reporting an error back to the agent.
 */
object CoilImageRenderer : A2uiImageRenderer {
    @Composable
    override fun Image(
        url: String,
        contentDescription: String?,
        contentScale: ContentScale,
        modifier: Modifier,
        onError: (throwable: Throwable?) -> Unit,
    ) {
        var failed by remember(url) { mutableStateOf(false) }
        val background = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
        if (failed) {
            Box(modifier.then(background), contentAlignment = Alignment.Center) {
                Text(
                    text = contentDescription ?: "Image unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = modifier.then(background),
                onError = { failed = true },
            )
        }
    }
}

/** This demo doesn't play media. A real app would plug in Media3 here. */
object NoMediaRenderer : A2uiVideoRenderer, A2uiAudioPlayerRenderer {
    @Composable
    override fun Video(url: String, modifier: Modifier, onError: (throwable: Throwable?) -> Unit) {
        Text("Video isn't supported in this demo", modifier, style = MaterialTheme.typography.bodySmall)
    }

    @Composable
    override fun AudioPlayer(
        url: String,
        contentDescription: String?,
        modifier: Modifier,
        onError: (throwable: Throwable?) -> Unit,
    ) {
        Text("Audio isn't supported in this demo", modifier, style = MaterialTheme.typography.bodySmall)
    }
}
