package com.jarvispoc.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * A flattened, log-friendly view of one accessibility node.
 *
 * [raw] is kept so the executor can perform actions without re-walking the tree.
 * It is deliberately excluded from any equality contract (this is a plain class,
 * not a data class) because node handles are transient.
 */
class UiNode(
    val index: Int,
    val depth: Int,
    val className: String,
    val viewId: String,
    val text: String,
    val contentDescription: String,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val packageName: String,
    val raw: AccessibilityNodeInfo? = null,
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    /** Short human-readable identity, used throughout the logs. */
    val label: String
        get() = when {
            text.isNotBlank() -> text.take(40)
            contentDescription.isNotBlank() -> contentDescription.take(40)
            viewId.isNotBlank() -> viewId.substringAfterLast('/')
            else -> className.substringAfterLast('.')
        }

    fun toJson(): String = buildString(160) {
        append("{\"i\":").append(index)
        append(",\"d\":").append(depth)
        append(",\"class\":\"").append(esc(className)).append('"')
        append(",\"id\":\"").append(esc(viewId)).append('"')
        append(",\"text\":\"").append(esc(text)).append('"')
        append(",\"desc\":\"").append(esc(contentDescription)).append('"')
        append(",\"bounds\":\"").append(bounds.left).append(',').append(bounds.top)
            .append(',').append(bounds.right).append(',').append(bounds.bottom).append('"')
        append(",\"clickable\":").append(clickable)
        append(",\"scrollable\":").append(scrollable)
        append(",\"editable\":").append(editable)
        append(",\"enabled\":").append(enabled)
        append(",\"checked\":").append(checked)
        append(",\"selected\":").append(selected)
        append(",\"pkg\":\"").append(esc(packageName)).append('"')
        append('}')
    }

    override fun toString(): String = "[$index] $label <${className.substringAfterLast('.')}>"

    private fun esc(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> Unit
            '\t' -> append(' ')
            else -> if (c.code < 0x20) append(' ') else append(c)
        }
    }
}
