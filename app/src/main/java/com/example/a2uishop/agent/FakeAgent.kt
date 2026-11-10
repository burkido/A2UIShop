package com.example.a2uishop.agent

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject

/**
 * An agent that runs inside the app. It plays scripted replies from `assets/scripts`, using
 * the same scripts and rules as the fake backend, so the demo works without a server.
 */
class FakeAgent(private val assets: AssetManager) : Agent {

    override fun reply(input: AgentInput): Flow<String> = flow {
        val (script, variables) = pickScript(input)
        for (step in loadScript(script, variables)) {
            if (step.has("delayMs")) delay(step.getLong("delayMs")) else emit(step.toString())
        }
    }.flowOn(Dispatchers.IO)

    /** A stand-in for the LLM: picks a reply based on keywords or on the client message. */
    private fun pickScript(input: AgentInput): Pair<String, Map<String, Any?>> = when (input) {
        is AgentInput.UserPrompt -> {
            val prompt = input.text.lowercase()
            when {
                prompt.hasAny("what size", "which size", "size guide", "my size") -> "size_guide_broken" to emptyMap()
                // These two change UI that is already on screen, so they check that it's there.
                prompt.hasAny("under", "cheap", "budget", "less than") ->
                    if ("sneaker-results" in input.activeSurfaces) "filter_results" to emptyMap()
                    else "no_results" to emptyMap()
                "pair" in prompt && "checkout" in input.activeSurfaces -> "update_quantity" to emptyMap()
                prompt.hasAny("sneaker", "shoe", "trainer", "runner") -> "search_results" to emptyMap()
                "pair" in prompt -> "no_checkout" to emptyMap()
                else -> "fallback" to emptyMap()
            }
        }
        is AgentInput.ClientMessage -> {
            val message = JSONObject(input.json)
            val action = message.optJSONObject("action")
            val context = action?.optJSONObject("context").toMap()
            val error = message.optJSONObject("error")
            when {
                // formatCurrency only accepts a double, and a whole price like 69 arrives as an int.
                action?.optString("name") == "selectProduct" ->
                    "checkout" to context + ("price" to (context["price"] as? Number)?.toDouble())
                action?.optString("name") == "placeOrder" -> "order_placed" to context + mapOf(
                    "delivery" to if (context["express"] == true) "Express: arrives tomorrow" else "Standard: arrives in 3–5 days",
                )
                // Self-correction only makes sense for an invalid UI, not for e.g. a failed image.
                error?.optString("surfaceId") == "size-guide" && error.optString("code") == "VALIDATION_FAILED" ->
                    "size_guide_fixed" to emptyMap()
                error != null -> "error_ack" to mapOf("message" to error.optString("message"))
                else -> "fallback" to emptyMap()
            }
        }
    }

    /**
     * Fills `{{name}}` placeholders. A placeholder that is a whole JSON string (`"{{price}}"`)
     * keeps the value's type, so numbers stay numbers. Inside text, it becomes readable text.
     */
    private fun loadScript(name: String, variables: Map<String, Any?>): List<JSONObject> {
        var raw = assets.open("scripts/$name.json").bufferedReader().use { it.readText() }
        variables.forEach { (key, value) ->
            raw = raw.replace("\"{{$key}}\"", toJsonLiteral(value))
            raw = raw.replace("{{$key}}", JSONObject.quote(display(value)).removeSurrounding("\""))
        }
        val steps = JSONArray(raw)
        return List(steps.length()) { steps.getJSONObject(it) }
    }

    private fun String.hasAny(vararg words: String) = words.any { it in this }

    private fun JSONObject?.toMap(): Map<String, Any?> =
        this?.keys()?.asSequence()?.associateWith { get(it) } ?: emptyMap()

    private fun toJsonLiteral(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString() // Number, Boolean, JSONArray and JSONObject print as JSON.
    }

    /** Readable text for a context value: 2.0 -> "2", ["42"] -> "42". */
    private fun display(value: Any?): String = when (value) {
        is Number -> if (value.toDouble() % 1.0 == 0.0) value.toLong().toString() else value.toString()
        is JSONArray -> List(value.length()) { display(value.get(it)) }.joinToString(", ")
        else -> value.toString()
    }
}
