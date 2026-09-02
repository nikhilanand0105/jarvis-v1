package com.jarvispoc.service

import android.app.Notification
import android.app.RemoteInput
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Bundle
import com.jarvispoc.core.AgentLog
import com.jarvispoc.flows.TelegramAutoReplyFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JarvisNotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        AgentLog.info("NotificationListenerService connected")
        instance = this
    }

    override fun onListenerDisconnected() {
        AgentLog.info("NotificationListenerService disconnected")
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "org.telegram.messenger") return

        val notification = sbn.notification
        val extras = notification.extras
        
        val sender = extras.getString(Notification.EXTRA_TITLE) ?: return
        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        // Skip non-text or system messages (e.g., "Updating...")
        if (message.isBlank() || sender == "Telegram") return

        AgentLog.info("Telegram message from [$sender]: $message")

        // Trigger the flow
        scope.launch {
            TelegramAutoReplyFlow.handleIncomingMessage(this@JarvisNotificationService, sbn)
        }
    }

    companion object {
        var instance: JarvisNotificationService? = null
            private set
    }
}
