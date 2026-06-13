package dev.agentpulse.app

import android.content.Context
import org.json.JSONArray

object EventStore {
    private const val FILE = "agent_pulse"
    private const val KEY = "recent_events"
    private const val LIMIT = 40

    @Synchronized
    fun add(context: Context, event: AgentEvent) {
        val current = get(context).filterNot { it.id == event.id }.toMutableList()
        current.add(0, event)
        val json = JSONArray()
        current.sortedByDescending { it.timestamp }.take(LIMIT).forEach { json.put(it.toJson()) }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, json.toString())
            .apply()
        AppPrefs.setLastEventTime(context, event.timestamp)
    }

    @Synchronized
    fun get(context: Context): List<AgentEvent> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    add(AgentEvent.fromJson(json.getJSONObject(index)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .remove("last_event_time")
            .apply()
    }
}
