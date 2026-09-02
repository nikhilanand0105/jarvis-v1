package com.jarvispoc.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvispoc.core.AgentLog
import com.jarvispoc.core.Query
import com.jarvispoc.core.UiNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Every primitive a scripted flow is allowed to use.
 *
 * The design principle throughout is **layered fallback**. Real third-party
 * apps break the textbook accessibility contract constantly: Amazon marks a
 * product tile's container clickable but not the text inside it; Instagram's
 * caption field silently rejects ACTION_SET_TEXT. Each primitive therefore
 * tries the clean route, then a structural workaround, then brute force.
 */
class ActionExecutor(private val service: JarvisAccessibilityService) {

    /** Last text we successfully put on the clipboard. See [copyToClipboard]. */
    @Volatile
    private var lastClipboardText: String? = null

    // ---------------------------------------------------------------- observe

    fun snapshot(): List<UiNode> = ScreenObserver.snapshot(service)

    fun currentPackage(): String =
        snapshot().firstOrNull { it.packageName.isNotBlank() }?.packageName.orEmpty()

    fun isInstalled(pkg: String): Boolean =
        runCatching { service.packageManager.getLaunchIntentForPackage(pkg) != null }
            .getOrDefault(false)

    /**
     * Waits for [pkg] to own the foreground.
     *
     * Worth its own step: if a deep link is unhandled and the target never
     * opens, the next `awaitNode` eventually times out and blames the selector,
     * which sends you debugging entirely the wrong thing. Failing here says what
     * actually happened. It also replaces a fixed sleep — most launches settle
     * far faster than a conservative delay would assume.
     */
    suspend fun awaitPackage(
        pkg: String,
        timeoutMs: Long = 10_000,
        pollMs: Long = 250,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (currentPackage() == pkg) {
                AgentLog.step("$pkg is in the foreground")
                return true
            }
            delay(pollMs)
        }
        AgentLog.warn("$pkg never reached the foreground in ${timeoutMs}ms (saw '${currentPackage()}')")
        return false
    }

    /**
     * Polls until [q] stops matching. The verification counterpart to
     * [awaitNode]: "the composer closed" is often the only observable proof
     * that an action actually took effect.
     */
    suspend fun awaitGone(
        q: Query,
        timeoutMs: Long = 8_000,
        pollMs: Long = 250,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (q.firstMatch(snapshot()) == null) {
                AgentLog.step("'${q.label}' is gone")
                return true
            }
            delay(pollMs)
        }
        AgentLog.warn("'${q.label}' still present after ${timeoutMs}ms")
        return false
    }

    /**
     * Polls the tree until [q] matches or [timeoutMs] elapses.
     * This is the workhorse — nearly every flow step begins here.
     */
    suspend fun awaitNode(
        q: Query,
        timeoutMs: Long = 8_000,
        pollMs: Long = 250,
    ): UiNode? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var polls = 0
        while (SystemClock.uptimeMillis() < deadline) {
            polls++
            val hit = q.firstMatchDetailed(snapshot())
            if (hit != null) {
                val (node, alt) = hit
                AgentLog.step(
                    "found '${q.label}' via alt ${alt + 1}/${q.alternatives.size} -> " +
                        "${node.label} [${node.className.substringAfterLast('.')}" +
                        "${if (node.viewId.isNotBlank()) " id=" + node.viewId.substringAfterLast('/') else ""}]"
                )
                return node
            }
            delay(pollMs)
        }
        AgentLog.warn("timed out after ${timeoutMs}ms waiting for '${q.label}' ($polls polls)")
        return null
    }

    // ------------------------------------------------------------------- tap

    suspend fun tap(node: UiNode): Boolean {
        val raw = node.raw

        // 1. the node itself is clickable
        if (raw != null && raw.isClickable &&
            raw.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            AgentLog.step("tapped '${node.label}' (ACTION_CLICK)")
            return true
        }

        // 2. nearest clickable ancestor — Amazon's product tiles need this
        if (raw != null) {
            var parent = runCatching { raw.parent }.getOrNull()
            var hops = 1
            while (hops <= MAX_ANCESTOR_HOPS) {
                val current = parent ?: break
                if (current.isClickable &&
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    AgentLog.step("tapped '${node.label}' (clickable ancestor +$hops)")
                    return true
                }
                parent = runCatching { current.parent }.getOrNull()
                hops++
            }
        }

        // 3. synthetic touch at the node's centre
        val ok = tapAt(node.centerX, node.centerY)
        AgentLog.step("tapped '${node.label}' (gesture @${node.centerX},${node.centerY}) -> $ok")
        return ok
    }

    suspend fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        return dispatch(gesture)
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatch(gesture)
    }

    /**
     * Bounded on purpose: if [AccessibilityService.dispatchGesture] accepts the
     * gesture but the service is torn down before either callback fires, the
     * continuation would never resume and the whole flow would hang with no
     * timeout of its own.
     */
    private suspend fun dispatch(gesture: GestureDescription): Boolean {
        val result = withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val accepted = service.dispatchGesture(gesture, callback, null)
                // If dispatch was refused outright the callback never fires.
                if (!accepted && cont.isActive) cont.resume(false)
            }
        }
        if (result == null) AgentLog.warn("gesture dispatch timed out after ${GESTURE_TIMEOUT_MS}ms")
        return result ?: false
    }

    // ------------------------------------------------------------------ text

    /**
     * ACTION_SET_TEXT first; clipboard paste as fallback. Instagram's composer
     * is the specific reason the fallback exists.
     */
    suspend fun setText(node: UiNode, text: String): Boolean {
        val raw = node.raw ?: run {
            AgentLog.error("cannot type into '${node.label}' — no live node handle")
            return false
        }

        AgentLog.info(
            "typing into ${node.className.substringAfterLast('.')} " +
                "id='${node.viewId.substringAfterLast('/')}' editable=${node.editable} " +
                "focusable=${raw.isFocusable}"
        )

        // ACTION_FOCUS is *accessibility* focus, which many custom fields ignore.
        // A real tap is what puts the cursor in and opens the IME, so fall back
        // to a synthetic touch when the node refuses ACTION_CLICK.
        raw.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!raw.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            AgentLog.info("ACTION_CLICK refused — tapping the field directly")
            tapAt(node.centerX, node.centerY)
        }
        delay(500)

        // 1 — ACTION_SET_TEXT
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        val setOk = raw.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (setOk && verifyText(text)) {
            AgentLog.step("entered ${text.length} chars (ACTION_SET_TEXT)")
            return true
        }
        AgentLog.warn("ACTION_SET_TEXT returned $setOk and did not stick — trying clipboard paste")

        // Belt and braces against duplication: if anything did land, clear it
        // before pasting, since ACTION_PASTE inserts at the cursor rather than
        // replacing the field.
        val blank = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        raw.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, blank)

        // 2 — clipboard paste. NEVER paste unless the clipboard holds *our*
        // text: a failed copy plus a blind ACTION_PASTE inserts whatever the
        // user copied earlier, and on the Instagram path that gets published.
        val copied = copyToClipboard(text)
        if (!copied && lastClipboardText != text) {
            AgentLog.error(
                "ACTION_SET_TEXT failed and the clipboard does not hold our text — " +
                    "refusing to paste, which would insert previously-copied content"
            )
            return false
        }
        delay(250)
        val pasted = raw.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (!pasted) {
            AgentLog.error(
                "both ACTION_SET_TEXT and ACTION_PASTE were refused by " +
                    "'${node.label}' — this node may not be the real input field"
            )
            return false
        }
        // Accept an unverifiable paste rather than failing a path that worked:
        // some fields hide their contents from the tree while the IME has a
        // composing region.
        if (!verifyText(text)) {
            AgentLog.warn("paste reported success but could not be verified — continuing anyway")
        }
        AgentLog.step("entered ${text.length} chars (clipboard paste)")
        return true
    }

    /**
     * Did our text land in *any* editable field on screen?
     *
     * Deliberately identity-free. An earlier version re-located the field by
     * viewId-or-bounds, which breaks on exactly the case that matters: entering
     * a multi-line caption makes the field grow, the bounds change, the field
     * is not found, and a perfectly successful write is reported as failed —
     * whereupon the caller pastes on top and the text ends up duplicated.
     * Asking "is the text there" instead of "is the text in that node" is
     * immune to resize, re-layout and node recycling.
     *
     * `performAction` returning true only means the node accepted the action,
     * not that anything changed, which is why this check exists at all.
     */
    private suspend fun verifyText(expected: String): Boolean {
        delay(400)
        val probe = expected.take(20)
        val nodes = snapshot()
        val landed = nodes.any { it.editable && it.text.contains(probe, ignoreCase = true) }
        if (!landed) {
            val fields = nodes.filter { it.editable }
                .joinToString(" | ") { "'${it.text.take(30)}'" }
                .ifBlank { "(no editable fields visible)" }
            AgentLog.warn("'$probe' not found in any editable field. They read: $fields")
        }
        return landed
    }

    /**
     * @return true only if the write actually landed.
     *
     * Tracked in [lastClipboardText] because the write can succeed while we are
     * foreground and fail later once the target app has the screen, so "did the
     * most recent call work" is not the same question as "does the clipboard
     * hold our text".
     */
    fun copyToClipboard(text: String): Boolean {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            AgentLog.error("clipboard service unavailable")
            return false
        }
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("jarvis", text))
            lastClipboardText = text
            true
        }.onFailure {
            AgentLog.error("clipboard write failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- scroll

    /**
     * Scrolls the largest scrollable container until [q] matches or we run out
     * of attempts. Returns the matched node, or null.
     */
    suspend fun scrollUntilVisible(
        q: Query,
        maxScrolls: Int = 8,
        settleMs: Long = 900,
    ): UiNode? {
        for (attempt in 0..maxScrolls) {
            q.firstMatch(snapshot())?.let {
                AgentLog.step("found '${q.label}' after $attempt scroll(s)")
                return it
            }
            if (attempt == maxScrolls) break
            if (!scrollForward()) {
                AgentLog.warn("nothing scrollable while looking for '${q.label}'")
                break
            }
            delay(settleMs)
        }
        AgentLog.warn("gave up scrolling for '${q.label}'")
        return null
    }

    suspend fun scrollForward(): Boolean {
        val nodes = snapshot()
        val target = nodes
            .filter { it.scrollable }
            .maxByOrNull { it.bounds.width().toLong() * it.bounds.height().toLong() }
            
        if (target != null && target.raw?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) {
            AgentLog.step("scrolled forward via ACTION_SCROLL_FORWARD")
            return true
        }

        // Fallback: physical swipe gesture
        val root = nodes.maxByOrNull { it.bounds.width().toLong() * it.bounds.height().toLong() }
        val screenHeight = root?.bounds?.bottom ?: 2400
        val screenWidth = root?.bounds?.right ?: 1080
        
        val x = screenWidth / 2
        val yStart = (screenHeight * 0.85).toInt()
        val yEnd = (screenHeight * 0.15).toInt()
        
        AgentLog.info("scrolling forward via swipe ($x, $yStart -> $x, $yEnd)")
        return swipe(x, yStart, x, yEnd, durationMs = 400)
    }

    // --------------------------------------------------------- interstitials

    /**
     * Sweeps away the upsell sheets Amazon shows after "Add to Cart".
     *
     * Deliberately conservative: no "Cancel" or "Close", because those can
     * back out of the very screen the flow is trying to advance through.
     */
    suspend fun dismissInterstitials(rounds: Int = 2) {
        repeat(rounds) {
            val nodes = snapshot()
            val hit = DISMISS_LABELS.firstNotNullOfOrNull { word ->
                nodes.firstOrNull { n ->
                    n.text.equals(word, ignoreCase = true) ||
                        n.contentDescription.equals(word, ignoreCase = true)
                }
            } ?: return
            AgentLog.step("dismissing interstitial: '${hit.label}'")
            tap(hit)
            delay(900)
        }
    }

    // ---------------------------------------------------------------- launch

    fun launchPackage(pkg: String): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            AgentLog.error("package not installed or not visible: $pkg")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startActivity(intent).also {
            if (it) AgentLog.step("launched $pkg")
        }
    }

    fun launchUri(uri: String, pkg: String?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) setPackage(pkg)
        }
        return startActivity(intent).also {
            if (it) AgentLog.step("opened $uri")
        }
    }

    fun startActivity(intent: Intent): Boolean = runCatching {
        if (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        service.startActivity(intent)
        true
    }.onFailure {
        AgentLog.error("startActivity failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(false)

    fun back(): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    fun home(): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    // ----------------------------------------------------------------- debug

    fun dumpScreen(tag: String) {
        val nodes = snapshot()
        if (nodes.isEmpty()) {
            AgentLog.error(
                "empty tree — either this app is still in the foreground (our own " +
                    "nodes are excluded by design), or the target sets FLAG_SECURE / " +
                    "suppresses its hierarchy, in which case it cannot be automated."
            )
            return
        }
        ScreenObserver.writeDump(service, nodes, tag)
    }

    private companion object {
        const val TAP_DURATION_MS = 60L
        const val GESTURE_TIMEOUT_MS = 5_000L
        const val MAX_ANCESTOR_HOPS = 8

        val DISMISS_LABELS = listOf(
            "No thanks",
            "No, thanks",
            "Skip",
            "Not now",
            "Maybe later",
            "Decline",
            "Dismiss",
        )
    }
}
