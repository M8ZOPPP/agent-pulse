package dev.agentpulse.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {
    private const val URGENT_CHANNEL = "agent_pulse_urgent"
    private const val UPDATES_CHANNEL = "agent_pulse_updates"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    URGENT_CHANNEL,
                    "Action required",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Permission requests and questions from coding agents"
                    enableVibration(true)
                    lightColor = Color.rgb(251, 191, 36)
                    enableLights(true)
                },
                NotificationChannel(
                    UPDATES_CHANNEL,
                    "Task updates",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Completed tasks and errors"
                }
            )
        )
    }

    fun showEvent(context: Context, event: AgentEvent) {
        val urgent = event.type == "approval" || event.type == "question"
        val notification = NotificationCompat.Builder(
            context,
            if (urgent) URGENT_CHANNEL else UPDATES_CHANNEL
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title)
            .setContentText(event.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.message))
            .setSubText(
                listOf(event.provider.replaceFirstChar { it.uppercase() }, event.project)
                    .filter { it.isNotBlank() }
                    .joinToString(" / ")
            )
            .setPriority(
                if (urgent) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(
                if (urgent) NotificationCompat.CATEGORY_REMINDER
                else NotificationCompat.CATEGORY_STATUS
            )
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(event.id.hashCode(), notification)
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
