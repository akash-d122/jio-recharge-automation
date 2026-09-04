package com.example.jioposinspector

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportExporter {

    private val filenameDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun saveAndShare(context: Context, report: DiagnosticReport): Intent {
        val reportText = ReportBuilder.buildText(report)
        val timestamp = filenameDateFormat.format(Date(report.captureTimeMs))
        val filename = "jiopos_inspection_$timestamp.txt"

        val reportsDir = File(context.filesDir, "reports")
        reportsDir.mkdirs()
        val file = File(reportsDir, filename)
        file.writeText(reportText, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "JioPOS Accessibility Diagnostic")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun listSavedReports(context: Context): List<File> {
        val dir = File(context.filesDir, "reports")
        return dir.listFiles()
            ?.filter { it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}
