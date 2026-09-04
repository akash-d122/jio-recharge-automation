package com.example.jioposinspector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.jioposinspector.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateStatusUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEnableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnStartAutomation.setOnClickListener { doStartAutomation() }
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

    private fun updateStatusUi() {
        val serviceEnabled = JioPOSAccessibilityService.instance != null
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
        
        binding.btnStartAutomation.isEnabled = serviceEnabled
    }

    private fun doStartAutomation() {
        val service = JioPOSAccessibilityService.instance
        if (service == null) {
            toast("Accessibility service not running - Enable it first")
            return
        }
        
        val phone = binding.etPhoneNumber.text?.toString()?.trim() ?: ""
        if (phone.length != 10 || !phone.all { it.isDigit() }) {
            toast("Enter a valid 10-digit mobile number")
            return
        }
        
        val amount = binding.etPlanAmount.text?.toString()?.trim() ?: "19"
        val isMock = binding.cbMockMode.isChecked
        
        // Hand off to service execution macro
        service.armAutomatedRecharge(phone, amount, isMock)
        
        // Safely bring JioPOS to foreground without wiping its activity stack
        val launchIntent = packageManager.getLaunchIntentForPackage("com.jio.jpp1")
        if (launchIntent != null) {
            // getLaunchIntentForPackage already includes FLAG_ACTIVITY_NEW_TASK
            startActivity(launchIntent)
        } else {
            val fallbackIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage("com.jio.jpp1")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                startActivity(fallbackIntent)
            } catch (e: Exception) {
                toast("JioPOS App not installed or detectable")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
