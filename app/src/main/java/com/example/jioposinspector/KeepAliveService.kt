package com.example.jioposinspector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.app.NotificationCompat

class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val NOTIF_PERSISTENT_CHANNEL = "jiopos_persistent"
        private const val NOTIF_PERSISTENT_ID = 1000
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val persistentChannel = NotificationChannel(
                NOTIF_PERSISTENT_CHANNEL,
                "Keep-Alive Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Maintains automation in background"
                setShowBadge(false)
            }
            nm.createNotificationChannel(persistentChannel)
        }
    }

    private var isReceiverRegistered = false

    private val disableReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == JioPOSAccessibilityService.ACTION_DISABLE_SERVICE) {
                JioPOSAccessibilityService.instance?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        try {
                            it.disableSelf()
                        } catch (e: Exception) { Log.e(TAG, "Failed to disableSelf", e) }
                    }
                }
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            unregisterReceiver(disableReceiver)
            isReceiverRegistered = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(JioPOSAccessibilityService.ACTION_DISABLE_SERVICE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(disableReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(disableReceiver, filter)
            }
            isReceiverRegistered = true
        }

        try {
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val disablePi = PendingIntent.getBroadcast(
                this, 1, 
                Intent(JioPOSAccessibilityService.ACTION_DISABLE_SERVICE).setPackage(packageName), 
                piFlags
            )
            val notif = NotificationCompat.Builder(this, NOTIF_PERSISTENT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.app_name) + " Active")
                .setContentText("Tap TURN OFF to allow other payment apps to work.")
                .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), piFlags))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .addAction(android.R.drawable.ic_delete, "TURN OFF SERVICE", disablePi)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_PERSISTENT_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_PERSISTENT_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } else {
                startForeground(NOTIF_PERSISTENT_ID, notif)
            }
            Log.i(TAG, "KeepAliveService started in foreground")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start KeepAliveService foreground", e)
        }
        
        // This is the magic flag: tells Android to restart the service automatically if memory gets low
        return START_STICKY
    }
}