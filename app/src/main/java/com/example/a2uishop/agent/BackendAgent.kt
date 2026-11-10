package com.example.a2uishop.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Talks to the agent backend (`POST /agent`) and streams its NDJSON reply line by line.
 * The backend runs either scripted replies or a real LLM.
 */
class BackendAgent(private val baseUrl: String) : Agent {

    // An LLM agent remembers the conversation. One session = one chat.
    private var sessionId = UUID.randomUUID().toString()

    /** Starts a new chat, so the agent forgets the old one. */
    fun newSession() {
        sessionId = UUID.randomUUID().toString()
    }

    override fun reply(input: AgentInput): Flow<String> = flow {
        val body = when (input) {
            is AgentInput.UserPrompt -> JSONObject().put("prompt", input.text)
            is AgentInput.ClientMessage -> JSONObject().put("clientMessage", JSONObject(input.json))
        }.put("activeSurfaces", JSONArray(input.activeSurfaces)).put("sessionId", sessionId)
        val connection = (URL("$baseUrl/agent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            // A model can think for a while before its next line.
            readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { emit(it) }
            }
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}
