package com.example.a2uishop.ui

import androidx.a2ui.model.processor.A2uiSurfaceModel
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

// Things a shopper might actually type. The fake agent matches keywords, so you can also
// type your own variations ("cheaper ones?", "which size fits me?").
private val Suggestions = listOf(
    "I need new sneakers for everyday wear",
    "Anything under €100?",
    "Actually, I need two pairs",
    "What size should I get?",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val surfaces by viewModel.surfaces.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Follow the conversation: new items and replies that are still streaming in.
    LaunchedEffect(state.items) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("A2UI Shop") },
                actions = {
                    Text("Backend", style = MaterialTheme.typography.labelMedium)
                    Switch(
                        checked = state.useBackend,
                        onCheckedChange = viewModel::setUseBackend,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    IconButton(onClick = viewModel::clear) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear chat")
                    }
                },
            )
        },
        bottomBar = { PromptBar(onSend = viewModel::sendPrompt) },
    ) { padding ->
        if (state.items.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(padding))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.items, key = { it.id }) { item ->
                when (item) {
                    is ChatItem.UserMessage -> UserBubble(item.text)
                    is ChatItem.AgentReply -> AgentReplyItem(
                        reply = item,
                        surfaces = surfaces.filter { it.id in item.surfaceIds },
                    )
                    is ChatItem.ClientEvent -> ClientEventItem(item)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "Ask the shopping assistant for sneakers.\nIts answers are rendered as native Compose UI.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AgentReplyItem(reply: ChatItem.AgentReply, surfaces: List<A2uiSurfaceModel>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reply.text.isNotEmpty()) {
            Text(reply.text, style = MaterialTheme.typography.bodyLarge)
        }
        // The agent's UI, rendered as native Compose with the Material 3 Basic Catalog.
        surfaces.forEach { surface ->
            A2uiSurface(
                surfaceModel = surface,
                // The agent may send a lazy List, so the surface needs a bounded height.
                modifier = Modifier.fillMaxWidth().heightIn(max = 900.dp),
            )
        }
        if (reply.isStreaming) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (reply.a2uiMessages.isNotEmpty()) {
            JsonToggle(
                showLabel = "Show A2UI JSON (${reply.a2uiMessages.size})",
                json = reply.a2uiMessages.joinToString("\n\n"),
            )
        }
    }
}

@Composable
private fun ClientEventItem(event: ChatItem.ClientEvent) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Text(
            text = (if (event.isError) "⚠️ " else "➤ ") + event.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (event.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        JsonToggle(showLabel = "Show sent JSON", json = event.json)
    }
}

@Composable
private fun JsonToggle(showLabel: String, json: String) {
    var visible by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { visible = !visible }, contentPadding = PaddingValues(0.dp)) {
            Text(if (visible) "Hide JSON" else showLabel)
        }
        if (visible) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = json,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun PromptBar(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(12.dp)) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Suggestions.forEach { suggestion ->
                    AssistChip(onClick = { onSend(suggestion) }, label = { Text(suggestion) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Ask the agent…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = send, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
