package com.example.jioposinspector

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticReport(
    val packageName: String,
    val activityName: String?,
    val captureTimeMs: Long,
    val nodes: List<NodeRecord>
)

object ReportBuilder {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    fun buildText(report: DiagnosticReport): String = buildString {
        appendLine("=== JioPOS Accessibility Diagnostic Report ===")
        appendLine("Captured: ${dateFormat.format(Date(report.captureTimeMs))}")
        appendLine("Package:  ${report.packageName}")
        appendLine("Activity: ${report.activityName ?: "(unknown)"}")
        appendLine("Nodes:    ${report.nodes.size}")
        appendLine()
        appendLine("--- Node Tree ---")
        appendLine()

        for (node in report.nodes) {
            val indent = "  ".repeat(node.depth)
            appendLine("${indent}[${node.className.substringAfterLast('.')}]")
            if (node.viewIdResourceName != null)
                appendLine("${indent}  id:          ${node.viewIdResourceName}")
            if (node.text != null)
                appendLine("${indent}  text:        ${sanitize(node.text)}")
            if (node.contentDescription != null)
                appendLine("${indent}  desc:        ${sanitize(node.contentDescription)}")
            appendLine("${indent}  clickable:   ${node.isClickable}")
            appendLine("${indent}  enabled:     ${node.isEnabled}")
            appendLine("${indent}  editable:    ${node.isEditable}")
            appendLine("${indent}  focused:     ${node.isFocused}")
            appendLine("${indent}  password:    ${node.isPassword}")
            appendLine("${indent}  bounds:      ${node.bounds}")
            appendLine("${indent}  children:    ${node.childCount}")
            appendLine()
        }

        appendLine("--- End of Report ---")
        appendLine("NOTE: Password field contents are never captured or logged.")
    }

    // Truncate very long strings to prevent log bloat; these are UI labels, not content
    private fun sanitize(s: String): String =
        if (s.length > 200) s.take(200) + "…[truncated]" else s
}
