package com.example.jioposinspector

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.widget.Button
import android.widget.TextView

class HumanConfirmationActivity : AppCompatActivity() {

    companion object {
        const val ACTION_CONFIRM = "com.example.jioposinspector.ACTION_CONFIRM"
        const val ACTION_CANCEL = "com.example.jioposinspector.ACTION_CANCEL"
        const val EXTRA_MOBILE = "extra_mobile"
        const val EXTRA_AMOUNT = "extra_amount"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple layout via code for MVP without needing extra XML files right away
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            gravity = android.view.Gravity.CENTER
        }

        val tvTitle = TextView(this).apply {
            text = "HUMAN CONFIRMATION REQUIRED"
            textSize = 24f
            setTextColor(android.graphics.Color.RED)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        }

        val mobile = intent.getStringExtra(EXTRA_MOBILE) ?: "Unknown"
        val amount = intent.getStringExtra(EXTRA_AMOUNT) ?: "Unknown"

        val tvDetails = TextView(this).apply {
            text = "Target Mobile Number:\n$mobile\n\nPlan Amount:\n₹$amount"
            textSize = 20f
            setPadding(0, 32, 0, 64)
            gravity = android.view.Gravity.CENTER
        }

        val btnConfirm = Button(this).apply {
            text = "CONFIRM & PAY CASH"
            textSize = 18f
            setPadding(32, 32, 32, 32)
                        setOnClickListener {
                LocalBroadcastManager.getInstance(this@HumanConfirmationActivity)
                    .sendBroadcast(Intent(ACTION_CONFIRM))
                finish()
            }
        }

        val btnCancel = Button(this).apply {
            text = "CANCEL / ABORT"
            textSize = 18f
            setPadding(32, 32, 32, 32)
            setOnClickListener {
                LocalBroadcastManager.getInstance(this@HumanConfirmationActivity)
                    .sendBroadcast(Intent(ACTION_CANCEL))
                finish()
            }
        }

        layout.addView(tvTitle)
        layout.addView(tvDetails)
        layout.addView(btnConfirm)
        
        val space = android.widget.Space(this)
        space.layoutParams = android.widget.LinearLayout.LayoutParams(1, 32)
        layout.addView(space)
        
        layout.addView(btnCancel)

        setContentView(layout)
    }
}
