package com.jarvispoc.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvispoc.core.AgentLog
import com.jarvispoc.core.UiNode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Flattens the live window hierarchy into [UiNode]s, and writes debug dumps.
 *
 * Only nodes that are visible and have non-zero bounds are kept — those are
 * the only ones we can meaningfully tap, and it cuts a typical Amazon tree
 * from a few thousand nodes to a few hundred.
 */
object ScreenObserver {

    private const val MAX_NODES = 3_000
    private const val MAX_DEPTH = 60
    private const val MAX_DUMPS = 40

    /**
     * Nodes belonging to *this* app are excluded.
     *
     * `getWindows()` returns every window, including our own control panel and
     * system chrome. Without this the agent can match its own UI — the log pane
     * in particular is full of long strings that the looser heuristic selectors
     * (e.g. Amazon's "any clickable node with 25+ characters") will happily
     * match on. An agent should never be able to see itself.
     */
    fun snapshot(service: AccessibilityService): List<UiNode> {
        val own = service.packageName
        val out = ArrayList<UiNode>(256)
        for (root in roots(service)) {
            walk(root, 0, out, own)
            if (out.size >= MAX_NODES) break
        }
        return out
    }

    /**
     * rootInActiveWindow alone misses dialogs and overlays — exactly where
     * Amazon puts its protection-plan upsells — so we also sweep getWindows().
     */
    private fun roots(service: AccessibilityService): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>(4)
        runCatching { service.rootInActiveWindow }.getOrNull()?.let { roots.add(it) }
        runCatching { service.windows }.getOrNull()?.forEach { window ->
            runCatching { window.root }.getOrNull()?.let { root ->
                if (roots.none { it == root }) roots.add(root)
            }
        }
        return roots
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: MutableList<UiNode>,
        ownPackage: String?,
    ) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        // Our own window: skip the whole subtree, not just this node.
        if (ownPackage != null && node.packageName?.toString() == ownPackage) return

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0) {
            out.add(
                UiNode(
                    index = out.size,
                    depth = depth,
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName.orEmpty(),
                    text = node.text?.toString().orEmpty(),
                    contentDescription = node.contentDescription?.toString().orEmpty(),
                    bounds = bounds,
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    scrollable = node.isScrollable,
                    editable = node.isEditable,
                    enabled = node.isEnabled,
                    checked = node.isChecked,
                    selected = node.isSelected,
                    packageName = node.packageName?.toString().orEmpty(),
                    raw = node,
                )
            )
        }

        val children = node.childCount
        for (i in 0 until children) {
            walk(runCatching { node.getChild(i) }.getOrNull(), depth + 1, out, ownPackage)
        }
    }

    fun toJson(nodes: List<UiNode>): String = buildString(nodes.size * 160) {
        append("{\"count\":").append(nodes.size).append(",\"nodes\":[\n")
        nodes.forEachIndexed { i, n ->
            if (i > 0) append(",\n")
            append(n.toJson())
        }
        append("\n]}")
    }

    /**
     * Writes a dump to the app's external files dir, which is `adb pull`-able
     * without root. Returns the file, or null if writing failed.
     */
    fun writeDump(context: Context, nodes: List<UiNode>, tag: String): File? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null), "dumps").apply { mkdirs() }
        val file = File(dir, "dump-$tag-$stamp.json")
        return runCatching {
            file.writeText(toJson(nodes))
            AgentLog.success("dump written: ${file.absolutePath} (${nodes.size} nodes)")
            pruneOldDumps(dir)
            file
        }.onFailure {
            AgentLog.error("dump write failed: ${it.message}")
        }.getOrNull()
    }

    /**
     * Every failed flow writes a dump, and a debugging session produces a lot
     * of failed flows. Keep the newest [MAX_DUMPS] so the directory stays
     * pullable and does not quietly eat storage.
     */
    private fun pruneOldDumps(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size <= MAX_DUMPS) return
        files.drop(MAX_DUMPS).forEach { runCatching { it.delete() } }
        AgentLog.info("pruned ${files.size - MAX_DUMPS} old dump(s)")
    }
}
