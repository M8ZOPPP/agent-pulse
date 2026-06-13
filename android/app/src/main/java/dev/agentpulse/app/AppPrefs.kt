package dev.agentpulse.app

import android.content.Context

object AppPrefs {
    private const val FILE = "agent_pulse"
    private const val URL = "relay_url"
    private const val TOKEN = "relay_token"
    private const val ENABLED = "push_enabled"
    private const val STATUS = "connection_status"
    private const val LAST_ERROR = "last_error"
    private const val LAST_EVENT_TIME = "last_event_time"
    private const val FIREBASE_APP_ID = "firebase_app_id"
    private const val FIREBASE_API_KEY = "firebase_api_key"
    private const val FIREBASE_PROJECT_ID = "firebase_project_id"
    private const val FIREBASE_SENDER_ID = "firebase_sender_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun relayUrl(context: Context) = prefs(context).getString(URL, "") ?: ""
    fun token(context: Context) = prefs(context).getString(TOKEN, "") ?: ""
    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, false)
    fun status(context: Context) = prefs(context).getString(STATUS, "Not paired") ?: "Not paired"
    fun lastError(context: Context) = prefs(context).getString(LAST_ERROR, "") ?: ""
    fun lastEventTime(context: Context) = prefs(context).getLong(LAST_EVENT_TIME, 0L)

    fun saveConnection(context: Context, url: String, token: String) {
        prefs(context).edit()
            .putString(URL, url.trim().trimEnd('/'))
            .putString(TOKEN, token.trim())
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply()
    }

    fun setStatus(context: Context, status: String, error: String = "") {
        prefs(context).edit()
            .putString(STATUS, status)
            .putString(LAST_ERROR, error)
            .apply()
    }

    fun setLastEventTime(context: Context, timestamp: Long) {
        if (timestamp > lastEventTime(context)) {
            prefs(context).edit().putLong(LAST_EVENT_TIME, timestamp).apply()
        }
    }

    fun saveFirebaseConfig(
        context: Context,
        appId: String,
        apiKey: String,
        projectId: String,
        senderId: String
    ) {
        prefs(context).edit()
            .putString(FIREBASE_APP_ID, appId)
            .putString(FIREBASE_API_KEY, apiKey)
            .putString(FIREBASE_PROJECT_ID, projectId)
            .putString(FIREBASE_SENDER_ID, senderId)
            .apply()
    }

    fun firebaseConfig(context: Context): Map<String, String> = mapOf(
        "appId" to (prefs(context).getString(FIREBASE_APP_ID, "") ?: ""),
        "apiKey" to (prefs(context).getString(FIREBASE_API_KEY, "") ?: ""),
        "projectId" to (prefs(context).getString(FIREBASE_PROJECT_ID, "") ?: ""),
        "senderId" to (prefs(context).getString(FIREBASE_SENDER_ID, "") ?: "")
    )

    fun eventEnabled(context: Context, type: String): Boolean =
        prefs(context).getBoolean("event_$type", true)

    fun setEventEnabled(context: Context, type: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("event_$type", enabled).apply()
    }
}
