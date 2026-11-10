package com.example.a2uishop.ui

import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.model.processor.A2uiSurfaceModel
import androidx.a2ui.model.processor.processInput
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiDeleteSurfaceMessage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.a2uishop.BuildConfig
import com.example.a2uishop.a2ui.shopCatalog
import com.example.a2uishop.a2ui.toJson
import com.example.a2uishop.agent.Agent
import com.example.a2uishop.agent.AgentInput
import com.example.a2uishop.agent.BackendAgent
import com.example.a2uishop.agent.FakeAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * @param catalog The components and functions the agent may use.
 * @param fakeAgent The built-in agent that replays canned replies.
 * @param backendAgent The agent that talks to the real server.
 */
class ChatViewModel(
    catalog: A2uiCatalog,
    private val fakeAgent: Agent,
    private val backendAgent: BackendAgent,
) : ViewModel() {

    // Processor: parses messages, validates them against the catalog, and keeps surface state.
    private val processor = A2uiMessageProcessor(catalogs = listOf(catalog))
    private val parser = A2uiMessageParser()

    // Surfaces: the UI rendered with A2uiSurface.
    val surfaces: StateFlow<List<A2uiSurfaceModel>> = processor.activeSurfaces

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    init {
        // Process queued messages off the main thread for as long as the ViewModel lives.
        viewModelScope.launch(Dispatchers.Default) { processor.collectMessages() }

        // Two-way communication: user actions and validation errors go back to the agent.
        viewModelScope.launch {
            processor.outboundEvents.collect { event ->
                val json = event.toJson()
                val label = when (event) {
                    is A2uiClientEventMessage -> "Action: ${event.type}"
                    is A2uiClientErrorMessage -> "Error: ${event.code}"
                }
                val isError = event is A2uiClientErrorMessage
                // Safety net: an agent that answers an error with another broken UI would loop
                // forever. After a couple of automatic retries, wait for the user instead.
                val autoRetry = !isError || autoErrorReplies < MAX_AUTO_ERROR_REPLIES
                addItem(ChatItem.ClientEvent(nextId(), if (autoRetry) label else "$label (not sent)", json, isError))
                if (!autoRetry) return@collect
                if (isError) autoErrorReplies++ else autoErrorReplies = 0
                talkToAgent(AgentInput.ClientMessage(json, liveSurfaceIds.toList()))
            }
        }
    }

    fun sendPrompt(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        autoErrorReplies = 0
        addItem(ChatItem.UserMessage(nextId(), prompt))
        talkToAgent(AgentInput.UserPrompt(prompt, liveSurfaceIds.toList()))
    }

    fun setUseBackend(useBackend: Boolean) = _state.update { it.copy(useBackend = useBackend) }

    fun clear() {
        // Stop replies that are still streaming, or they would put UI back after the clear.
        replyJobs.toList().forEach { it.cancel() }
        replyJobs.clear()
        surfaces.value.forEach { processor.processMessage(A2uiDeleteSurfaceMessage(it.id)) }
        liveSurfaceIds.clear()
        autoErrorReplies = 0
        backendAgent.newSession()
        _state.update { it.copy(items = emptyList()) }
    }

    private fun talkToAgent(input: AgentInput) {
        val agent = if (_state.value.useBackend) backendAgent else fakeAgent
        val replyId = nextId()
        addItem(ChatItem.AgentReply(replyId, isStreaming = true))
        val job = viewModelScope.launch {
            try {
                agent.reply(input).collect { line -> onAgentLine(replyId, line) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val hint = if (_state.value.useBackend) {
                    "Couldn't reach the backend. Is the server running? You can also switch Backend off to use the built-in agent."
                } else {
                    "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
                }
                updateReply(replyId) { it.copy(text = (it.text + "\n⚠️ $hint").trim()) }
            } finally {
                updateReply(replyId) { it.copy(isStreaming = false) }
            }
        }
        replyJobs += job
        job.invokeOnCompletion { replyJobs -= job }
    }

    /** Each streamed line is either chat text or an A2UI message for the processor. */
    private fun onAgentLine(replyId: Long, line: String) {
        // One malformed line shouldn't break the rest of the reply.
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (!json.has("version")) {
            updateReply(replyId) { it.copy(text = it.text + json.optString("text")) }
            return
        }
        val createdSurfaceId = json.optJSONObject("createSurface")?.optString("surfaceId")
        if (createdSurfaceId != null && !liveSurfaceIds.add(createdSurfaceId)) {
            // The agent reused a surface ID (for example after a second "Choose size"). The newest
            // UI wins: remove the old surface, and take it out of the reply that showed it.
            processor.processMessage(A2uiDeleteSurfaceMessage(createdSurfaceId))
            _state.update { state ->
                state.copy(items = state.items.map {
                    if (it is ChatItem.AgentReply) it.copy(surfaceIds = it.surfaceIds - createdSurfaceId) else it
                })
            }
        }
        json.optJSONObject("deleteSurface")?.optString("surfaceId")?.let { liveSurfaceIds.remove(it) }

        // Parses the message and queues it. Invalid messages become outbound errors.
        processor.processInput(parser, line)

        // Remember which surfaces this reply created, so they show up under it in the chat.
        updateReply(replyId) {
            it.copy(
                a2uiMessages = it.a2uiMessages + json.toString(2),
                surfaceIds = it.surfaceIds + listOfNotNull(createdSurfaceId),
            )
        }
    }

    private fun addItem(item: ChatItem) = _state.update { it.copy(items = it.items + item) }

    private fun updateReply(id: Long, transform: (ChatItem.AgentReply) -> ChatItem.AgentReply) =
        _state.update { state ->
            state.copy(items = state.items.map { if (it is ChatItem.AgentReply && it.id == id) transform(it) else it })
        }

    // Surfaces the agent has created, so it can be told what is on screen.
    private val liveSurfaceIds = mutableSetOf<String>()
    private val replyJobs = mutableSetOf<Job>()
    private var autoErrorReplies = 0

    private var lastId = 0L
    private fun nextId() = ++lastId

    companion object {
        /** Builds the dependencies from the Application, so the ViewModel never holds a Context. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[APPLICATION_KEY])
                ChatViewModel(
                    catalog = shopCatalog(application),
                    fakeAgent = FakeAgent(application.assets),
                    backendAgent = BackendAgent(BuildConfig.AGENT_BASE_URL),
                )
            }
        }
    }
}

private const val MAX_AUTO_ERROR_REPLIES = 2

data class ChatState(
    val items: List<ChatItem> = emptyList(),
    val useBackend: Boolean = false,
)

sealed interface ChatItem {
    val id: Long

    data class UserMessage(override val id: Long, val text: String) : ChatItem

    data class AgentReply(
        override val id: Long,
        val text: String = "",
        val surfaceIds: List<String> = emptyList(),
        val a2uiMessages: List<String> = emptyList(),
        val isStreaming: Boolean = false,
    ) : ChatItem

    /** A message the client sent back to the agent (an action or an error). */
    data class ClientEvent(
        override val id: Long,
        val label: String,
        val json: String,
        val isError: Boolean,
    ) : ChatItem
}
