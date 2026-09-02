package com.jarvispoc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Out-of-app signalling.
 *
 * A flow halts or fails while the *target* app owns the screen, so the in-app
 * trace is invisible at precisely the moment the user needs to know something
 * happened. Both channels are used deliberately:
 *
 *  - Toast: immediate, needs no permission, guaranteed to appear.
 *  - Notification: persists, so a user who looked away still finds out.
 *    Silently no-ops without POST_NOTIFICATIONS on API 33+, hence the toast.
 */
object Notifier {

    private const val CHANNEL_ID = "jarvis_flow"
    private const val NOTIFICATION_ID = 1001

    private val main = Handler(Looper.getMainLooper())

    fun announce(context: Context, title: String, body: String) {
        toast(context, "$title — $body")
        notify(context, title, body)
    }

    fun toast(context: Context, text: String) {
        main.post {
            runCatching { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
        }
    }

    private fun notify(context: Context, title: String, body: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        ensureChannel(manager)

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Flow results",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Tells you when an automated flow stops, finishes or fails."
            }
        )
    }
}
