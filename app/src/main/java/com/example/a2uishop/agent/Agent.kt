package com.example.a2uishop.agent

import kotlinx.coroutines.flow.Flow

/** What we send to the agent in one turn. */
sealed interface AgentInput {
    /** IDs of the surfaces currently on screen, so the agent doesn't edit UI that isn't there. */
    val activeSurfaces: List<String>

    /** Something the user typed. */
    data class UserPrompt(val text: String, override val activeSurfaces: List<String> = emptyList()) : AgentInput

    /** An A2UI client-to-server message (an `action` or an `error`) as JSON. */
    data class ClientMessage(val json: String, override val activeSurfaces: List<String> = emptyList()) : AgentInput
}

/**
 * An AI agent that replies with a stream of NDJSON lines. Each line is either chat text
 * (`{"text": "..."}`) or an A2UI protocol message (`{"version": "v0.9", "createSurface": ...}`).
 */
fun interface Agent {
    fun reply(input: AgentInput): Flow<String>
}
