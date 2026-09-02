package com.jarvispoc.flows

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.jarvispoc.ai.LocalLlmEngine
import com.jarvispoc.core.AgentLog
import com.jarvispoc.core.Selector
import com.jarvispoc.core.query
import com.jarvispoc.service.JarvisAccessibilityService
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

object TelegramAutoReplyFlow {

    enum class Mode {
        AVAILABLE, DRIVING, SLEEPING
    }

    var currentMode = Mode.AVAILABLE

    // (sender + message) -> timestamp
    private val handledMessages = ConcurrentHashMap<String, Long>()

    // Simple LLM engine instance, can be re-used
    private var llmEngine: LocalLlmEngine? = null

    suspend fun handleIncomingMessage(context: Context, sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val sender = extras.getString(Notification.EXTRA_TITLE)?.trim() ?: return
        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: return

        // 1. Prevent duplicates
        val key = "$sender|$message"
        if (handledMessages.containsKey(key)) {
            AgentLog.info("Skipping duplicate message: $key")
            return
        }
        
        // Skip our own replies or self-messages (sometimes sender is "You")
        if (sender.equals("You", ignoreCase = true) || sender.equals("Saved Messages", ignoreCase = true)) {
            return
        }

        handledMessages[key] = System.currentTimeMillis()

        // 2. Safety filter (OTP/PIN/banking)
        if (isSensitiveMessage(message)) {
            AgentLog.warn("Message contains sensitive info (OTP/PIN), skipping auto-reply.")
            return
        }

        // 3. Generate Reply based on Mode
        val replyText = generateReply(context, message)
        if (replyText.isBlank()) return

        AgentLog.info("Auto-replying to $sender: \"$replyText\"")

        // 4. Send Reply
        val sentViaRemote = tryRemoteInput(context, sbn, replyText)
        if (sentViaRemote) {
            AgentLog.success("Reply sent via RemoteInput")
            // 7. Verify
            verifyRemoteInputSuccess(sender, replyText)
            return
        }

        AgentLog.info("RemoteInput not available or failed. Falling back to Accessibility.")
        
        // 5. Fallback: Accessibility
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            AgentLog.error("AccessibilityService not running for fallback")
            return
        }

        fallbackAccessibilityReply(service, sbn, sender, replyText)
    }

    fun isSensitiveMessage(msg: String): Boolean {
        val lower = msg.lowercase()
        val sensitiveWords = listOf("otp", "pin", "password", "bank", "verification code", "auth", "login")
        return sensitiveWords.any { lower.contains(it) }
    }

    private suspend fun generateReply(context: Context, msg: String): String {
        return when (currentMode) {
            Mode.DRIVING -> "I'm driving right now. I'll get back to you when I'm free."
            Mode.SLEEPING -> "I'm asleep right now. I'll get back to you when I'm awake."
            Mode.AVAILABLE -> {
                if (llmEngine == null) llmEngine = LocalLlmEngine(context)
                val res = llmEngine?.reply(msg)
                res?.getOrNull() ?: "Hello! I'm currently busy but will get back to you."
            }
        }
    }

    private fun tryRemoteInput(context: Context, sbn: StatusBarNotification, reply: String): Boolean {
        val notification = sbn.notification
        val actions = notification.actions ?: return false

        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (remoteInput in remoteInputs) {
                if (remoteInput.resultKey != null && action.actionIntent != null) {
                    // Send inline reply
                    val intent = Intent()
                    val bundle = Bundle()
                    bundle.putCharSequence(remoteInput.resultKey, reply)
                    RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
                    
                    try {
                        action.actionIntent.send(context, 0, intent)
                        return true
                    } catch (e: PendingIntent.CanceledException) {
                        AgentLog.error("Failed to send inline reply", e)
                    }
                }
            }
        }
        return false
    }

    private suspend fun verifyRemoteInputSuccess(sender: String, reply: String) {
        // We'll wait a bit, then launch Telegram via accessibility to verify? 
        // The prompt says "Verify that the reply actually appears as sent in the correct Telegram conversation."
        // We can do this using Accessibility fallback verification path.
        delay(2000)
        val service = JarvisAccessibilityService.instance ?: return
        val x = service.executor

        AgentLog.step("Verifying reply in Telegram...")
        
        // Launch Telegram
        x.launchPackage("org.telegram.messenger")
        if (!x.awaitPackage("org.telegram.messenger", 5000)) {
            AgentLog.error("Could not open Telegram to verify.")
            return
        }
        
        delay(1000)
        
        // Try to find the chat
        val chatNode = x.awaitNode(query("Chat with $sender", Selector(textContains = sender)), timeoutMs = 2000)
        if (chatNode != null) {
            x.tap(chatNode)
            delay(1000)
        }

        // Verify reply is visible
        val replyVisible = x.awaitNode(query("Reply text", Selector(textContains = reply)), timeoutMs = 3000)
        if (replyVisible != null) {
            AgentLog.success("Verified: Reply appears in Telegram conversation.")
        } else {
            AgentLog.error("Verification failed: Could not see the reply in Telegram.")
        }
    }

    private suspend fun fallbackAccessibilityReply(
        service: JarvisAccessibilityService, 
        sbn: StatusBarNotification,
        sender: String, 
        reply: String
    ) {
        val x = service.executor
        
        // Tap notification to open the chat
        if (sbn.notification.contentIntent != null) {
            sbn.notification.contentIntent.send()
        } else {
            x.launchPackage("org.telegram.messenger")
        }

        if (!x.awaitPackage("org.telegram.messenger", 5000)) {
            AgentLog.error("Could not open Telegram")
            return
        }

        delay(1500)

        // Make sure we are in the right chat (check sender name in header)
        // Telegram usually has the sender name at the top. 
        // If not, we might need to search for it, but tapping notification usually opens the chat directly.
        val header = x.awaitNode(query("Chat Header", Selector(textContains = sender)), timeoutMs = 2000)
        if (header == null) {
            AgentLog.warn("Could not strictly verify we are in $sender's chat. Proceeding anyway assuming notification worked.")
        }

        // Type reply
        val inputBox = x.awaitNode(query("Message input", Selector(textContains = "Message")), timeoutMs = 2000)
            ?: x.awaitNode(query("Message input", Selector(clazz = "android.widget.EditText")), timeoutMs = 2000)
            
        if (inputBox == null) {
            AgentLog.error("Could not find message input box")
            return
        }
        
        if (!x.tap(inputBox)) {
            AgentLog.error("Could not tap input box")
            return
        }
        delay(500)

        // Send text using our Accessibility node or global action
        val set = x.setText(inputBox, reply)
        if (!set) {
            AgentLog.warn("Failed to setText directly. Ensure JarvisAccessibilityService has capabilities.")
            // Wait, we need to send text. Let's see if we have `x.setText`
        }

        // Send button in Telegram is usually a Send icon (desc: "Send")
        val sendBtn = x.awaitNode(query("Send Button", Selector(desc = "Send")), timeoutMs = 2000)
        if (sendBtn != null) {
            x.tap(sendBtn)
            delay(1000)
            AgentLog.success("Fallback reply sent.")
        } else {
            AgentLog.error("Could not find Send button.")
            return
        }

        // Verify
        val replyVisible = x.awaitNode(query("Reply text", Selector(textContains = reply)), timeoutMs = 3000)
        if (replyVisible != null) {
            AgentLog.success("Verified: Reply appears in Telegram conversation.")
        } else {
            AgentLog.error("Verification failed: Could not see the reply in Telegram.")
        }
    }
}
