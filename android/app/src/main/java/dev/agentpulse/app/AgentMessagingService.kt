package dev.agentpulse.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AgentMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (AppPrefs.relayUrl(this).isBlank() || AppPrefs.token(this).isBlank()) return
        Thread { runCatching { RelayClient.registerDevice(this, token) } }.start()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = message.data
        val event = AgentEvent(
            id = payload["id"] ?: message.messageId ?: System.nanoTime().toString(),
            type = payload["type"] ?: "completed",
            provider = payload["provider"] ?: "agent",
            title = payload["title"] ?: "Agent update",
            message = payload["message"] ?: "A task has an update.",
            project = payload["project"] ?: "",
            timestamp = payload["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
        )
        EventStore.add(this, event)
        if (AppPrefs.eventEnabled(this, event.type)) NotificationHelper.showEvent(this, event)
    }
}
