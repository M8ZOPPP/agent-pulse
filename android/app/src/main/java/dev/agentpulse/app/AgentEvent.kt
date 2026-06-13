package dev.agentpulse.app

import org.json.JSONObject

data class AgentEvent(
    val id: String,
    val type: String,
    val provider: String,
    val title: String,
    val message: String,
    val project: String,
    val timestamp: Long
) {
    fun toJson() = JSONObject()
        .put("id", id)
        .put("type", type)
        .put("provider", provider)
        .put("title", title)
        .put("message", message)
        .put("project", project)
        .put("timestamp", timestamp)

    companion object {
        fun fromJson(json: JSONObject) = AgentEvent(
            id = json.optString("id", System.nanoTime().toString()),
            type = json.optString("type", "completed"),
            provider = json.optString("provider", "agent"),
            title = json.optString("title", "Agent update"),
            message = json.optString("message", "A task has an update."),
            project = json.optString("project", ""),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }
}
