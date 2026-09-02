package com.jarvispoc.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import com.jarvispoc.core.AgentLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The single privileged component. Holds no flow logic of its own — it exists
 * to expose an [ActionExecutor] and a coroutine scope to whoever presses Run.
 *
 * We use a polling model (see [ActionExecutor.awaitNode]) rather than reacting
 * to accessibility events: event streams from Amazon and Instagram are far too
 * noisy to drive a state machine off, and polling the tree every 250ms is both
 * simpler and more predictable.
 */
class JarvisAccessibilityService : AccessibilityService() {

    /**
     * The handler is not optional: an uncaught exception in a flow coroutine
     * would otherwise reach the default handler and kill the process, which
     * looks like "the app crashed" instead of "step 4 threw".
     */
    val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                AgentLog.error("flow coroutine died", throwable)
            }
    )

    val executor: ActionExecutor by lazy { ActionExecutor(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AgentLog.success("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: the executor polls on demand.
    }

    override fun onInterrupt() {
        AgentLog.warn("Accessibility service interrupted")
    }

    override fun onDestroy() {
        AgentLog.warn("Accessibility service destroyed")
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null

        /**
         * Whether the user has switched us on in Settings > Accessibility.
         * Reading Settings.Secure is the only reliable way to know; a bound
         * [instance] can lag behind a toggle-off.
         */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(
                context.packageName,
                JarvisAccessibilityService::class.java.name,
            ).flattenToString()

            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }
}
