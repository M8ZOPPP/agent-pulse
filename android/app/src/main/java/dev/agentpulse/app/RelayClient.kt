package dev.agentpulse.app

import android.app.Application
import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object RelayClient {
    data class Result(val ok: Boolean, val message: String)

    fun pair(application: Application, callback: (Result) -> Unit) {
        Thread {
            try {
                val response = request(application, "GET", "/v1/config")
                val firebase = response.getJSONObject("firebase")
                AppPrefs.saveFirebaseConfig(
                    application,
                    firebase.getString("appId"),
                    firebase.getString("apiKey"),
                    firebase.getString("projectId"),
                    firebase.getString("senderId")
                )
                if (!FirebaseRuntime.initialize(application)) error("Firebase initialization failed")
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { fcmToken ->
                        Thread {
                            try {
                                registerDevice(application, fcmToken)
                                syncEvents(application)
                                AppPrefs.setEnabled(application, true)
                                AppPrefs.setStatus(application, "Push active")
                                callback(Result(true, "Phone paired and push is active"))
                            } catch (error: Exception) {
                                pairFailed(application, error, callback)
                            }
                        }.start()
                    }
                    .addOnFailureListener { error -> pairFailed(application, error, callback) }
            } catch (error: Exception) {
                pairFailed(application, error, callback)
            }
        }.start()
    }

    private fun pairFailed(application: Application, error: Throwable, callback: (Result) -> Unit) {
        val message = error.message ?: "Pairing failed"
        AppPrefs.setStatus(application, "Pairing failed", message)
        callback(Result(false, message))
    }

    fun registerDevice(context: Context, fcmToken: String) {
        val body = JSONObject()
            .put("fcmToken", fcmToken)
            .put("platform", "android")
            .put("appVersion", "1.0.0")
        request(context, "POST", "/v1/devices", body)
    }

    fun syncEvents(context: Context): Int {
        if (AppPrefs.relayUrl(context).isBlank() || AppPrefs.token(context).isBlank()) return 0
        val response = request(context, "GET", "/v1/events?since=${AppPrefs.lastEventTime(context)}")
        val events = response.optJSONArray("events") ?: JSONArray()
        for (index in 0 until events.length()) {
            EventStore.add(context, AgentEvent.fromJson(events.getJSONObject(index)))
        }
        return events.length()
    }

    fun sendTest(context: Context): Result = try {
        request(context, "POST", "/v1/test", JSONObject())
        Result(true, "Test notification sent")
    } catch (error: Exception) {
        Result(false, error.message ?: "Test failed")
    }

    private fun request(
        context: Context,
        method: String,
        path: String,
        body: JSONObject? = null
    ): JSONObject {
        val base = AppPrefs.relayUrl(context)
        val token = AppPrefs.token(context)
        require(base.startsWith("https://")) { "Relay URL must use HTTPS" }
        require(token.length >= 16) { "Pairing token must be at least 16 characters" }

        val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).use(BufferedReader::readText) } ?: ""
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
            error(message?.ifBlank { null } ?: "Relay returned HTTP $code")
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
