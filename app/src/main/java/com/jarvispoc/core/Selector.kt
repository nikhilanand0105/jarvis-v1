package com.jarvispoc.core

/**
 * One way of recognising a node. Every non-null field must match (AND).
 *
 * String matching is case-insensitive and substring-based except for [text],
 * which is an exact match. [text] and [textContains] check *both* `text` and
 * `contentDescription`, because Amazon and Instagram label a large share of
 * their controls via contentDescription only.
 *
 * **One implicit criterion beyond the declared fields:** disabled nodes never
 * match, on any selector. Tapping a greyed-out control does nothing, so
 * matching one would burn a step and report false success. If a selector is
 * mysteriously not matching a node you can see in a dump, check its `enabled`
 * flag there first.
 */
data class Selector(
    /** Substring of the fully-qualified view id, e.g. "add_to_cart". */
    val id: String? = null,
    /** Exact (case-insensitive) match on text or contentDescription. */
    val text: String? = null,
    /** Substring match on text or contentDescription. */
    val textContains: String? = null,
    /** Substring match on contentDescription only. */
    val desc: String? = null,
    /** Substring of the class name, e.g. "EditText". */
    val clazz: String? = null,
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    val scrollable: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    /** Rejects nodes whose longest label is shorter than this. Filters out chrome. */
    val minTextLen: Int? = null,
) {
    fun matches(n: UiNode): Boolean {
        if (!n.enabled) return false
        id?.let { if (!n.viewId.contains(it, ignoreCase = true)) return false }
        text?.let {
            if (!n.text.equals(it, ignoreCase = true) &&
                !n.contentDescription.equals(it, ignoreCase = true)
            ) return false
        }
        textContains?.let {
            if (!n.text.contains(it, ignoreCase = true) &&
                !n.contentDescription.contains(it, ignoreCase = true)
            ) return false
        }
        desc?.let { if (!n.contentDescription.contains(it, ignoreCase = true)) return false }
        clazz?.let { if (!n.className.contains(it, ignoreCase = true)) return false }
        clickable?.let { if (n.clickable != it) return false }
        editable?.let { if (n.editable != it) return false }
        scrollable?.let { if (n.scrollable != it) return false }
        checked?.let { if (n.checked != it) return false }
        selected?.let { if (n.selected != it) return false }
        minTextLen?.let { if (maxOf(n.text.length, n.contentDescription.length) < it) return false }
        return true
    }
}

/**
 * An ordered list of fallback [Selector]s for one logical target.
 *
 * Earlier alternatives win. This is what lets a step name three plausible
 * resource ids and survive an Amazon A/B test or an Instagram redesign.
 */
class Query(val label: String, val alternatives: List<Selector>) {

    fun firstMatch(nodes: List<UiNode>): UiNode? = firstMatchDetailed(nodes)?.first

    /**
     * Also reports *which* alternative hit.
     *
     * Worth surfacing: a query limping along on its last-resort fallback looks
     * identical to a clean match in the log, right up until it grabs the wrong
     * node. Knowing it fell through to "any editable field" is usually the
     * whole diagnosis.
     */
    fun firstMatchDetailed(nodes: List<UiNode>): Pair<UiNode, Int>? {
        for ((index, selector) in alternatives.withIndex()) {
            val hit = nodes.firstOrNull { selector.matches(it) }
            if (hit != null) return hit to index
        }
        return null
    }

    override fun toString(): String = label
}

fun query(label: String, vararg alternatives: Selector): Query =
    Query(label, alternatives.toList())
