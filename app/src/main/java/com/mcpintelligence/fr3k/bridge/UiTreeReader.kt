package com.mcpintelligence.fr3k.bridge

import android.view.accessibility.AccessibilityNodeInfo
import com.mcpintelligence.fr3k.capability.UiNode

/** Converts a live accessibility tree into the pure model used by the matcher. */
object UiTreeReader {
    const val MAX_NODES = 600

    fun toUiNode(root: AccessibilityNodeInfo, maxDepth: Int = 24): UiNode {
        var budget = MAX_NODES
        return convert(root, 0, maxDepth) { budget-- }
    }

    private fun convert(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int, takeBudget: () -> Int): UiNode {
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        val password = node.isPassword
        val children = ArrayList<UiNode>()
        if (depth < maxDepth) {
            for (index in 0 until node.childCount) {
                if (takeBudget() <= 0) break
                val child = node.getChild(index) ?: continue
                children.add(convert(child, depth + 1, maxDepth, takeBudget))
            }
        }
        return UiNode(
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            text = if (password) null else text,
            contentDescription = if (password) null else description,
            password = password,
            clickable = node.isClickable,
            children = children,
        )
    }

    /** Walks the live tree by index path so the matcher result can be acted on. */
    fun nodeAtPath(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo = root
        for (index in path) {
            current = current.getChild(index) ?: return null
        }
        return current
    }

    /** Deepest clickable ancestor of a matched node, so tapping works even on non-clickable text. */
    fun clickableTarget(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var current: AccessibilityNodeInfo = root
        if (current.isClickable) best = current
        for (index in path) {
            current = current.getChild(index) ?: return best
            if (current.isClickable) best = current
        }
        return best ?: current
    }
}
