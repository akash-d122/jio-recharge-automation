package com.example.jioposinspector

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class NodeRecord(
    val depth: Int,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val isClickable: Boolean,
    val isEnabled: Boolean,
    val isEditable: Boolean,
    val isFocused: Boolean,
    val isPassword: Boolean,
    val bounds: String,
    val childCount: Int
)

object NodeTraverser {
    private const val MAX_DEPTH = 30
    private val PHONE_PATTERN = Regex("\\b\\d{10}\\b")

    fun traverse(root: AccessibilityNodeInfo): List<NodeRecord> {
        val result = mutableListOf<NodeRecord>()
        visitNode(root, 0, result)
        return result
    }

    private fun redactSensitive(value: String?, isPassword: Boolean, isEditable: Boolean): String? {
        if (value.isNullOrBlank()) return null
        if (isPassword) return "[PASSWORD OMITTED]"
        if (isEditable) return "[EDITABLE CONTENT OMITTED]"
        return value.trim().replace(PHONE_PATTERN, "[PHONE REDACTED]")
    }

    private fun visitNode(node: AccessibilityNodeInfo, depth: Int, out: MutableList<NodeRecord>) {
        if (depth > MAX_DEPTH) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val rawText = node.text?.toString()
        val rawContentDesc = node.contentDescription?.toString()
        val isPassword = node.isPassword
        val isEditable = node.isEditable

        out.add(
            NodeRecord(
                depth = depth,
                className = node.className?.toString() ?: "",
                text = redactSensitive(rawText, isPassword, isEditable),
                contentDescription = redactSensitive(rawContentDesc, isPassword, isEditable),
                viewIdResourceName = node.viewIdResourceName,
                isClickable = node.isClickable,
                isEnabled = node.isEnabled,
                isEditable = isEditable,
                isFocused = node.isFocused,
                isPassword = isPassword,
                bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
                childCount = node.childCount
            )
        )

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                visitNode(child, depth + 1, out)
            } finally {
                child.recycle()
            }
        }
    }
}
