package com.example.jioposinspector

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.jioposinspector.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastReport: DiagnosticReport? = null

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — permission is best-effort */ }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateStatusUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.btnEnableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnBatteryOptimization.setOnClickListener {
            handleBatteryAndAutostart()
        }
        binding.btnCapture.setOnClickListener { doCapture() }
        binding.btnShare.setOnClickListener { doShare() }
        binding.btnStartAutomation.setOnClickListener { doStartAutomation() }
    }

    private fun isBatteryOptimized(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            return pm?.isIgnoringBatteryOptimizations(packageName) == false
        }
        return false
    }

    private fun handleBatteryAndAutostart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm?.isIgnoringBatteryOptimizations(packageName) == false) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    toast("Please select 'Don't optimize / Unrestricted'")
                    return
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        return
                    } catch (e2: Exception) {
                        // fallback to OEM settings
                    }
                }
            }
        }
        openOemSettings()
    }

    private fun openOemSettings() {
        val intents = listOf(
            // Xiaomi / Poco (MIUI / HyperOS Autostart)
            Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            // Realme / Oppo / ColorOS (Startup Manager)
            Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(android.content.ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(android.content.ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            // Vivo / iQOO
            Intent().setComponent(android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            // App Info settings
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                toast("Enable 'Autostart' & set Battery to 'No restrictions'")
                return
            } catch (e: Exception) {
                // try next
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(JioPOSAccessibilityService.ACTION_CAPTURE_DONE)
            addAction(JioPOSAccessibilityService.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        updateStatusUi()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    // ponytail: Settings.Secure check survives process death; instance==null only until onServiceConnected fires
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val target = "${packageName}/${JioPOSAccessibilityService::class.java.name}"
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(target, ignoreCase = true)) return true
        }
        return false
    }

    private fun updateStatusUi() {
        val serviceEnabled = isAccessibilityServiceEnabled()
        val jioPosActive   = JioPOSAccessibilityService.isJioPosInForeground
        val state          = JioPOSAccessibilityService.lastDetectedState

        binding.tvServiceStatus.text = if (serviceEnabled)
            "Accessibility Service: ENABLED"
        else
            "Accessibility Service: DISABLED — tap button below"

        binding.tvJioPosStatus.text = when {
            !serviceEnabled -> "JioPOS: service not running"
            jioPosActive    -> "JioPOS: IN FOREGROUND  pkg=${JioPOSAccessibilityService.lastDetectedPackage}"
            else            -> "JioPOS: not in foreground  (last: ${JioPOSAccessibilityService.lastDetectedPackage ?: "none"})"
        }

        binding.tvJioPosState.text = "State: ${state.label()}"

        binding.btnCapture.isEnabled = serviceEnabled && jioPosActive
        binding.btnShare.isEnabled   = lastReport != null
        binding.btnStartAutomation.isEnabled = serviceEnabled
    }

    private fun doCapture() {
        val service = JioPOSAccessibilityService.instance
        if (service == null) {
            toast("Service not running — enable it in Accessibility Settings")
            return
        }
        val nodes = service.captureCurrentScreen()
        val report = DiagnosticReport(
            packageName   = JioPOSAccessibilityService.lastDetectedPackage ?: "unknown",
            activityName  = JioPOSAccessibilityService.lastActivityClass,
            captureTimeMs = System.currentTimeMillis(),
            nodes         = nodes
        )
        lastReport = report
        binding.tvLastCapture.text =
            "Captured ${nodes.size} nodes  activity=${report.activityName?.substringAfterLast('.') ?: "?"}"
        binding.tvLog.text =
            ReportBuilder.buildText(report).take(4000) + "\n\n[Tap Export/Share for full report]"
        binding.btnShare.isEnabled = true
        toast("Captured ${nodes.size} nodes")
    }

    private fun doShare() {
        val report = lastReport ?: run { toast("No report captured yet"); return }
        startActivity(Intent.createChooser(ReportExporter.saveAndShare(this, report), "Share diagnostic report"))
    }

    private fun doStartAutomation() {
        if (!isAccessibilityServiceEnabled()) {
            toast("Accessibility service not running - Enable it first")
            return
        }

        val phone = binding.etPhoneNumber.text?.toString()?.trim() ?: ""
        if (phone.length != 10 || !phone.all { it.isDigit() }) {
            toast("Enter a valid 10-digit mobile number")
            return
        }

        val amount = binding.etPlanAmount.text?.toString()?.trim() ?: "19"

        val launchIntent = packageManager.getLaunchIntentForPackage("com.jio.jpp1")

        val service = JioPOSAccessibilityService.instance
        if (service == null) {
            // Service is toggled on in settings, but process was cold or sleeping.
            // Launch JioPOS and poll for up to 15s for the OS to bind the service.
            toast("Waking service — launching JioPOS, arming automatically…")
            if (launchIntent != null) startActivity(launchIntent)
            Thread {
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline) {
                    val svc = JioPOSAccessibilityService.instance
                    if (svc != null) {
                        svc.armAutomatedRecharge(phone, amount)
                        return@Thread
                    }
                    Thread.sleep(250)
                }
                android.os.Handler(mainLooper).post {
                    toast("Service is asleep. Toggle it OFF and back ON, then tap 'Fix Background & Autostart'.")
                    try {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }.start()
            return
        }

        service.armAutomatedRecharge(phone, amount)

        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            toast("Armed! Switch to JioPOS manually.")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}