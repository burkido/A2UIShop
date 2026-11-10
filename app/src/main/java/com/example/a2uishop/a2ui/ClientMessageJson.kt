package com.example.a2uishop.a2ui

import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiClientToServerMessage
import androidx.a2ui.model.protocol.A2uiProtocolConstants
import org.json.JSONObject
import java.time.Instant

/**
 * Converts the processor's outbound events into the A2UI wire format, so we can send them to
 * the agent. Actions become `{"action": {...}}` and errors become `{"error": {...}}`.
 */
fun A2uiClientToServerMessage.toJson(): String {
    val json = JSONObject().put("version", A2uiProtocolConstants.PROTOCOL_VERSION)
    when (this) {
        is A2uiClientEventMessage -> json.put(
            "action",
            JSONObject()
                .put("name", type)
                .put("surfaceId", surfaceId)
                .put("sourceComponentId", componentId)
                .put("timestamp", Instant.ofEpochMilli(timestamp).toString())
                .put("context", JSONObject.wrap(context)),
        )
        is A2uiClientErrorMessage -> json.put(
            "error",
            JSONObject()
                .put("code", code)
                .put("surfaceId", surfaceId)
                .put("message", message)
                .apply { context.forEach { (key, value) -> put(key, JSONObject.wrap(value)) } },
        )
    }
    // org.json escapes "/" as "\/". That is valid JSON, but harder to read in the demo.
    return json.toString(2).replace("\\/", "/")
}
