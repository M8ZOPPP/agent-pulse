package dev.agentpulse.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class AgentPulseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        FirebaseRuntime.initialize(this)
    }
}

object FirebaseRuntime {
    fun initialize(application: Application): Boolean {
        if (FirebaseApp.getApps(application).isNotEmpty()) return true
        val config = AppPrefs.firebaseConfig(application)
        if (config.values.any { it.isBlank() }) return false
        return runCatching {
            val options = FirebaseOptions.Builder()
                .setApplicationId(config.getValue("appId"))
                .setApiKey(config.getValue("apiKey"))
                .setProjectId(config.getValue("projectId"))
                .setGcmSenderId(config.getValue("senderId"))
                .build()
            FirebaseApp.initializeApp(application, options)
            true
        }.getOrDefault(false)
    }
}
