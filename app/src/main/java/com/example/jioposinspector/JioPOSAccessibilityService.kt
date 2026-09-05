package com.example.jioposinspector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JioPOSAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "JioPOSService"
        const val ACTION_STATE_CHANGED = "com.example.jioposinspector.STATE_CHANGED"
        const val ACTION_CAPTURE_DONE  = "com.example.jioposinspector.CAPTURE_DONE"
        const val ACTION_NOTIF_CONFIRM = "com.example.jioposinspector.NOTIF_CONFIRM"
        const val ACTION_NOTIF_CANCEL  = "com.example.jioposinspector.NOTIF_CANCEL"
        private const val NOTIF_CHANNEL = "recharge_confirm"
        private const val NOTIF_ID = 1001

        @Volatile var instance: JioPOSAccessibilityService? = null
        @Volatile var isJioPosInForeground: Boolean = false
        @Volatile var lastDetectedPackage: String? = null
        @Volatile var lastDetectedState: JioPosState = JioPosState.JIOPOS_NOT_FOREGROUND
        @Volatile var lastActivityClass: String? = null
    }

    // ── instance state ──────────────────────────────────────────────────────
    private var activePhone: String? = null
    private var activeAmount: String? = null
    @Volatile private var isArmed = false
    private var automationThread: Thread? = null

    // Latch used to block the automation thread until user confirms/cancels
    @Volatile private var confirmLatch: CountDownLatch? = null
    @Volatile private var userConfirmed = false

    private val notifActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            NotificationManagerCompat.from(context).cancel(NOTIF_ID)
            when (intent.action) {
                ACTION_NOTIF_CONFIRM -> { userConfirmed = true;  confirmLatch?.countDown() }
                ACTION_NOTIF_CANCEL  -> { userConfirmed = false; confirmLatch?.countDown() }
            }
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────────
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
        serviceInfo = serviceInfo.also {
            it.flags = it.flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(ACTION_NOTIF_CONFIRM)
            addAction(ACTION_NOTIF_CANCEL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notifActionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(notifActionReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isArmed = false
        confirmLatch?.countDown() // unblock any waiting thread
        unregisterReceiver(notifActionReceiver)
        Log.i(TAG, "AccessibilityService destroyed")
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    // ── event handling ───────────────────────────────────────────────────────
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastDetectedPackage = pkg
            isJioPosInForeground = (pkg == "com.jio.jpp1")
            if (isJioPosInForeground) {
                lastActivityClass = event.className?.toString()
            }
        }

        if (isJioPosInForeground) {
            evaluateAndBroadcastState()
        } else {
            if (lastDetectedState != JioPosState.JIOPOS_NOT_FOREGROUND) {
                lastDetectedState = JioPosState.JIOPOS_NOT_FOREGROUND
                broadcastStatusUpdate()
            }
        }
    }

    fun evaluateAndBroadcastState() {
        val root = jiopOsRoot() ?: return
        try {
            lastDetectedState = JioPosStateDetector.detect(root, lastActivityClass)
        } finally {
            root.recycle()
        }
        broadcastStatusUpdate()
    }

    private fun broadcastStatusUpdate() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL,
                "Recharge Confirmation",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Requires user approval before cash payment" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun showConfirmNotification(phone: String, amount: String) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

        val confirmPi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_NOTIF_CONFIRM).setPackage(packageName), flags
        )
        val cancelPi = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_NOTIF_CANCEL).setPackage(packageName), flags
        )

        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Confirm ₹$amount recharge?")
            .setContentText("Mobile: [PHONE REDACTED] | Amount: ₹$amount")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_play, "CONFIRM & PAY", confirmPi)
            .addAction(android.R.drawable.ic_delete, "CANCEL", cancelPi)
            .build()

        NotificationManagerCompat.from(this).notify(NOTIF_ID, notif)
    }

    // ── root node helper ─────────────────────────────────────────────────────
    fun jiopOsRoot(): AccessibilityNodeInfo? {
        return windows?.firstOrNull {
            it.root?.packageName?.toString() == "com.jio.jpp1"
        }?.root
    }

    // ── diagnostics (M1) ─────────────────────────────────────────────────────
    fun captureCurrentScreen(): List<NodeRecord> {
        val root = jiopOsRoot() ?: return emptyList()
        return try {
            NodeTraverser.traverse(root)
        } finally {
            root.recycle()
        }
    }

    // ── navigation (M2) ──────────────────────────────────────────────────────
    sealed class NavigateResult {
        object Clicked : NavigateResult()
        object NotOnHome : NavigateResult()
        object RechargeNodeNotFound : NavigateResult()
    }

    fun navigateToRecharge(): NavigateResult {
        if (lastDetectedState != JioPosState.HOME) return NavigateResult.NotOnHome
        val root = jiopOsRoot() ?: return NavigateResult.RechargeNodeNotFound
        return try {
            val node = JioPosStateDetector.findRechargeNode(root)
                ?: return NavigateResult.RechargeNodeNotFound
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                NavigateResult.Clicked
            } finally {
                node.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    // ── M3 result types ──────────────────────────────────────────────────────
    sealed class M3EntryResult {
        object Entered : M3EntryResult()
        object NotOnRecharge : M3EntryResult()
        object PhoneFieldNotFound : M3EntryResult()
        object ConfirmFieldNotFound : M3EntryResult()
        object ContinueNotFound : M3EntryResult()
    }

    sealed class M3PlanSelectionResult {
        object PlanSelected : M3PlanSelectionResult()
        object NotOnRecharge : M3PlanSelectionResult()
        object PlanNotFound : M3PlanSelectionResult()
        object BuyButtonNotFound : M3PlanSelectionResult()
    }

    // ── M4 result types ──────────────────────────────────────────────────────
    sealed class M4CashResult {
        object CashPaid : M4CashResult()
        object NotOnCartScreen : M4CashResult()
        object CashOptionNotFound : M4CashResult()
        object SubmitButtonNotFound : M4CashResult()
    }

    // ── M3 mobile number entry ────────────────────────────────────────────────
    fun enterMobileNumber(phoneNumber: String): M3EntryResult {
        if (lastDetectedState != JioPosState.RECHARGE) return M3EntryResult.NotOnRecharge

        val editTexts = pollForEditTexts(timeoutMs = 15_000)
        if (editTexts.isEmpty()) return M3EntryResult.PhoneFieldNotFound
        if (editTexts.size < 2) {
            editTexts.forEach { it.recycle() }
            return M3EntryResult.ConfirmFieldNotFound
        }

        return try {
            val phoneField   = editTexts[0]
            val confirmField = editTexts[1]

            safeSleep(500)
            setClipboard(phoneNumber)
            safeSleep(300)

            tapNodeCenter(phoneField)
            safeSleep(400)
            phoneField.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            safeSleep(500)

            tapNodeCenter(confirmField)
            safeSleep(400)
            confirmField.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            safeSleep(500)

            tapCoord(540f, 300f) // blur to dismiss keyboard and trigger React validation
            safeSleep(1000) // wait for keyboard to fully animate away and Continue to become visible

            // Dynamic: wait until Continue button appears and is enabled
            var continueNode: AccessibilityNodeInfo? = null
            val continueDeadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < continueDeadline && isArmed) {
                val r = jiopOsRoot()
                if (r != null) {
                    try {
                        dismissGotItTooltips(r)
                    } catch (e: Exception) { /* non-fatal */ }
                    val n = JioPosStateDetector.findClickableAncestorOfExactDesc(r, "button Continue")
                        ?: JioPosStateDetector.findClickableAncestorOfExactText(r, "Continue")
                    r.recycle()
                    if (n != null && n.isEnabled) {
                        continueNode = n
                        break
                    }
                    n?.recycle()
                }
                Thread.sleep(500)
            }

            if (continueNode == null) return M3EntryResult.ContinueNotFound

            try {
                safeSleep(300)
                val bounds = Rect()
                continueNode.getBoundsInScreen(bounds)
                // Always tap by coordinate so keyboard can't intercept the click
                tapCoord(
                    if (bounds.isEmpty) 540f else bounds.centerX().toFloat(),
                    if (bounds.isEmpty || bounds.height() < 4) 1700f else bounds.centerY().toFloat(),
                    durationMs = 120
                )
            } finally {
                continueNode.recycle()
            }

            M3EntryResult.Entered
        } finally {
            editTexts.forEach { it.recycle() }
        }
    }

    // ── M3 plan selection ────────────────────────────────────────────────────
    fun selectPlan(amount: String = "19"): M3PlanSelectionResult {
        if (lastDetectedState != JioPosState.RECHARGE) return M3PlanSelectionResult.NotOnRecharge

        safeSleep(2000) // let phone-entry screen animate away

        val amtInt = amount.trim()
        val planRegex = Regex(
            "^(\u20B9|Rs\\.?\\s*)" + Regex.escape(amtInt) + "(\\.0{1,2})?\$",
            RegexOption.IGNORE_CASE
        )
        val buyRegex = Regex("""(?i)buy( plan)?|proceed""")

        // Step 1: wait for plan screen (< 2 EditTexts = left the 2-field phone screen)
        val findFieldDeadline = System.currentTimeMillis() + 15_000
        var planEditText: AccessibilityNodeInfo? = null
        while (System.currentTimeMillis() < findFieldDeadline && isArmed) {
            val root = jiopOsRoot()
            if (root != null) {
                val editTexts = collectEditTextNodes(root)
                Log.d(TAG, "selectPlan: waiting for plan screen, editTexts=${editTexts.size}")
                if (editTexts.size < 2) {
                    planEditText = editTexts.firstOrNull()?.let { AccessibilityNodeInfo.obtain(it) }
                    editTexts.forEach { it.recycle() }
                    root.recycle()
                    break
                }
                editTexts.forEach { it.recycle() }
                root.recycle()
            }
            Thread.sleep(500)
        }
        Log.i(TAG, "selectPlan: plan screen detected, planEditText=${planEditText != null}")

        // Step 2: paste amount into filter field then dismiss keyboard via accessibility actions only
        try {
            setClipboard(amount)
            safeSleep(150)
            if (planEditText != null) {
                val b = Rect(); planEditText.getBoundsInScreen(b)
                Log.i(TAG, "selectPlan: native EditText bounds=\$b, tapping + pasting")
                tapNodeCenter(planEditText) // coordinate tap — confirmed to open keyboard
                safeSleep(250)
                planEditText.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                safeSleep(250)
                // Try ACTION_CLEAR_FOCUS first; if RN ignores it the fallback tapCoord covers it
                planEditText.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                safeSleep(150)
                // Tap app toolbar area (y=80) — above WebView (starts at y≈106)
                // Cannot trigger WebView touch events; reliably removes keyboard focus
                tapCoord(540f, 80f)
                Log.i(TAG, "selectPlan: blur via toolbar tap, waiting for keyboard dismiss")
            } else {
                Log.i(TAG, "selectPlan: no native EditText, tapping plan filter at (540,921)")
                tapCoord(540f, 921f) // center of EditText: bounds 108,872,970,970
                safeSleep(350)
                performGlobalAction(7) // GLOBAL_ACTION_PASTE
                safeSleep(300)
                tapCoord(540f, 80f) // toolbar area above WebView
                Log.i(TAG, "selectPlan: global paste sent")
            }
            safeSleep(500) // wait for keyboard animation to complete
            Log.i(TAG, "selectPlan: keyboard dismissed, waiting for plan list to load")
            safeSleep(500)
        } finally {
            planEditText?.recycle()
        }

        // Dump visible text nodes for diagnostics
        jiopOsRoot()?.let { root ->
            val sb = StringBuilder("selectPlan tree dump:")
            fun dumpText(n: AccessibilityNodeInfo, depth: Int) {
                val t = n.text?.toString()?.take(40)
                val d = n.contentDescription?.toString()?.take(40)
                if (!t.isNullOrBlank() || !d.isNullOrBlank()) {
                    val bounds = Rect(); n.getBoundsInScreen(bounds)
                    sb.append("\n  [$depth] text='$t' desc='$d' bounds=$bounds clickable=${n.isClickable}")
                }
                for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; dumpText(c, depth + 1); c.recycle() }
            }
            dumpText(root, 0)
            root.recycle()
            Log.i(TAG, sb.toString())
        }

        // Step 3: scroll + poll for the exact plan amount node
        // Use ACTION_SCROLL_FORWARD on the scrollable container \u2014 avoids touch gestures
        // that React Native intercepts as taps (which re-focus the search input).
        Log.i(TAG, "selectPlan: starting scroll+poll for \u20B9$amtInt")
        var amountNode: AccessibilityNodeInfo? = null

        fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (root.isScrollable) return AccessibilityNodeInfo.obtain(root)
            for (i in 0 until root.childCount) {
                val child = root.getChild(i) ?: continue
                val found = findScrollable(child)
                child.recycle()
                if (found != null) return found
            }
            return null
        }

        fun doScroll() {
            val scrollRoot = jiopOsRoot() ?: return swipeUp()
            val scrollable = findScrollable(scrollRoot)
            scrollRoot.recycle()
            if (scrollable != null) {
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                scrollable.recycle()
                Log.i(TAG, "selectPlan: ACTION_SCROLL_FORWARD sent")
            } else {
                Log.w(TAG, "selectPlan: no scrollable node, falling back to swipeUp")
                swipeUp()
            }
            safeSleep(250)
        }

        for (scrollPass in 0 until 20) {
            if (!isArmed) break
            val passDeadline = System.currentTimeMillis() + 1_200
            while (System.currentTimeMillis() < passDeadline && isArmed) {
                val root = jiopOsRoot()
                if (root != null) {
                    amountNode = findRawTextNode(root, planRegex)
                    root.recycle()
                    if (amountNode != null) {
                        Log.i(TAG, "selectPlan: found \u20B9$amtInt node at scroll pass $scrollPass")
                        break
                    }
                }
                Thread.sleep(200)
            }
            if (amountNode != null) break
            Log.i(TAG, "selectPlan: not found at pass $scrollPass, scrolling")
            doScroll()
        }

        // Always scroll once more after finding the node so the Buy button sibling
        // is fully in the viewport — the ₹19 text can enter with positive height while
        // Buy is still clipped at the screen edge (height=0).
        if (amountNode != null) {
            val b = Rect(); amountNode!!.getBoundsInScreen(b)
            Log.i(TAG, "selectPlan: plan found at $b, scrolling once more to ensure Buy is visible")
            doScroll()
            amountNode!!.recycle()
            amountNode = null
            val root = jiopOsRoot()
            if (root != null) {
                amountNode = findRawTextNode(root, planRegex)
                root.recycle()
            }
        }

        if (amountNode == null) {
            Log.w(TAG, "selectPlan: \u20B9$amtInt not found after scroll passes")
            return M3PlanSelectionResult.PlanNotFound
        }

        // Step 4: find Buy button near amount node and tap
        try {
            // Dump sibling area for diagnostics
            jiopOsRoot()?.let { root ->
                val sb = StringBuilder("selectPlan post-scroll tree dump:")
                fun dumpNear(n: AccessibilityNodeInfo, depth: Int) {
                    val t = n.text?.toString()?.take(40)
                    val d = n.contentDescription?.toString()?.take(40)
                    val bk = Rect(); n.getBoundsInScreen(bk)
                    if ((!t.isNullOrBlank() || !d.isNullOrBlank()) && bk.height() > 0) {
                        sb.append("\n  [$depth] text='$t' desc='$d' bounds=$bk clickable=${n.isClickable}")
                    }
                    for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; dumpNear(c, depth + 1); c.recycle() }
                }
                dumpNear(root, 0)
                root.recycle()
                Log.i(TAG, sb.toString())
            }

            Log.i(TAG, "selectPlan: found amount node, searching for Buy button")
            val amtBounds = Rect(); amountNode!!.getBoundsInScreen(amtBounds)
            // Find the "button Buy" node whose top is nearest to and >= amtBounds.bottom.
            // RN plan cards use desc="button Buy" with empty text; walking up/down the tree
            // finds the wrong card's Buy. Spatial search on the full tree is reliable.
            var clickTarget: AccessibilityNodeInfo? = null
            jiopOsRoot()?.let { root ->
                var bestDelta = Int.MAX_VALUE
                fun collectBuy(n: AccessibilityNodeInfo) {
                    val desc = n.contentDescription?.toString() ?: ""
                    if (n.isClickable && buyRegex.containsMatchIn(desc)) {
                        val nb = Rect(); n.getBoundsInScreen(nb)
                        if (nb.height() > 0 && nb.top >= amtBounds.bottom) {
                            val delta = nb.top - amtBounds.bottom
                            if (delta < bestDelta) {
                                bestDelta = delta
                                clickTarget?.recycle()
                                clickTarget = AccessibilityNodeInfo.obtain(n)
                            }
                        }
                    }
                    for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; collectBuy(c); c.recycle() }
                }
                collectBuy(root)
                root.recycle()
                Log.i(TAG, "selectPlan: spatial Buy search done, bestDelta=$bestDelta target=${clickTarget != null}")
            }
            if (clickTarget == null) {
                // ponytail: coordinate fallback \u2014 Buy is ~560px below \u20b919 text bottom (from tree dump: price@1541, buy@2100)
                val tapX = ((amtBounds.left + amtBounds.right) / 2).toFloat()
                val tapY = (amtBounds.bottom + 560).toFloat()
                Log.w(TAG, "selectPlan: no accessible click target, tapping coord offset ($tapX, $tapY) below \u20B9$amtInt at $amtBounds")
                tapCoord(tapX, tapY)
                Log.i(TAG, "selectPlan: tapped coord fallback for buy button")
            } else {
                val ct = clickTarget!!
                try {
                    tapNodeCenter(ct, durationMs = 100)
                    Log.i(TAG, "selectPlan: tapped buy button")
                } finally {
                    ct.recycle()
                }
            }

            // Poll for up to 5s for any Continue/Checkout button after Buy tap
            // Coord fallback covers RN buttons with desc='button' and no text
            var postBuyTapped = false
            val postBuyDeadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < postBuyDeadline) {
                val r = jiopOsRoot()
                if (r != null) {
                    val btn = findRawTextNode(r, Regex("""(?i)checkout|continue"""))
                    r.recycle()
                    if (btn != null) {
                        val clickable = JioPosStateDetector.nearestClickableAncestor(btn) ?: btn
                        tapNodeCenter(clickable)
                        btn.recycle()
                        if (clickable !== btn) clickable.recycle()
                        postBuyTapped = true
                        Log.i(TAG, "selectPlan: post-Buy button tapped via text")
                        break
                    }
                }
                Thread.sleep(200)
            }
            if (!postBuyTapped) {
                // desc='button' with no text — coord fallback for Checkout Continue
                // bounds: 64,1873-1014,2015 → center (539, 1944)
                Log.i(TAG, "selectPlan: post-Buy text search timed out — coord tap (539,1944)")
                tapCoord(539f, 1944f)
            }
            safeSleep(800)
        } finally {
            amountNode.recycle()
        }

        return M3PlanSelectionResult.PlanSelected
    }

    // Returns the raw text node matching regex without walking up to a clickable ancestor.
    // Used as a spatial anchor so findNearbyNodeMatchingRegex can search sibling subtrees.
    private fun findRawTextNode(root: AccessibilityNodeInfo, regex: Regex): AccessibilityNodeInfo? {
        val text = root.text?.toString() ?: ""
        val desc = root.contentDescription?.toString() ?: ""
        if (regex.containsMatchIn(text) || regex.containsMatchIn(desc)) {
            val b = Rect(); root.getBoundsInScreen(b)
            if (b.height() <= 0) return null  // off-screen virtualised node — skip
            return AccessibilityNodeInfo.obtain(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findRawTextNode(child, regex)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun findNearbyNodeMatchingRegex(
        node: AccessibilityNodeInfo,
        regex: Regex,
        maxLevelsUp: Int
    ): AccessibilityNodeInfo? {
        var parent: AccessibilityNodeInfo? = node.parent
        var levels = 0
        while (parent != null && levels < maxLevelsUp) {
            val match = findNodeMatchingRegex(parent, regex)
            if (match != null) {
                val clickable = JioPosStateDetector.nearestClickableAncestor(match)
                if (clickable != null && clickable.isClickable) {
                    match.recycle()
                    return clickable
                }
                return match
            }
            val nextParent = parent.parent
            parent.recycle()
            parent = nextParent
            levels++
        }
        return null
    }

    // ── M4 cash payment ───────────────────────────────────────────────────────
    fun payCashAndConfirm(): M4CashResult {
        // Step 1: Find and click the "Cash" tile
        var cashOptionNode: AccessibilityNodeInfo? = null
        val step1Deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < step1Deadline) {
            val root = jiopOsRoot()
            if (root != null) {
                val cn = findClickableAncestorOfText(root, "Cash")
                root.recycle()
                if (cn != null) {
                    cashOptionNode = cn
                    break
                }
            }
            Thread.sleep(150)
        }
        if (cashOptionNode != null) {
            try {
                safeSleep(150)
                tapNodeCenter(cashOptionNode)
            } finally {
                cashOptionNode.recycle()
            }
        } else {
            // Cash tile has clickable=false with no clickable parent in RN tree — coord fallback
            // Cash row bounds: 64,660-1014,844 → center (539, 752)
            Log.i(TAG, "M4: Cash node not found via tree — coord tap fallback")
            safeSleep(150)
            tapCoord(539f, 752f)
        }

        // Step 2: Wait for the Cash Details modal, then find submit button
        var finalSubmitNode: AccessibilityNodeInfo? = null
        val step2Deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < step2Deadline) {
            val root = jiopOsRoot()
            if (root != null) {
                val byId = root.findAccessibilityNodeInfosByViewId("com.jio.jpp1:id/submitCashPayment")
                val visible = byId.firstOrNull { n ->
                    val r = android.graphics.Rect(); n.getBoundsInScreen(r); r.height() > 0
                }
                byId.filter { it !== visible }.forEach { it.recycle() }
                if (visible != null) {
                    finalSubmitNode = visible
                    root.recycle()
                    break
                }
                root.recycle()
            }
            Thread.sleep(200)
        }
        if (finalSubmitNode == null) return M4CashResult.SubmitButtonNotFound

        // Step 3: Human confirmation via notification — JioPOS stays in foreground
        val latch = CountDownLatch(1)
        confirmLatch = latch
        userConfirmed = false
        showConfirmNotification(activePhone ?: "", activeAmount ?: "")

        val confirmed = try {
            latch.await(120, TimeUnit.SECONDS) && userConfirmed
        } finally {
            confirmLatch = null
            NotificationManagerCompat.from(this).cancel(NOTIF_ID)
        }

        if (!confirmed) {
            finalSubmitNode.recycle()
            Log.i(TAG, "M4: user cancelled or timed out — aborting cash submit")
            return M4CashResult.CashOptionNotFound // reuse as "cancelled/abort"
        }

        try {
            safeSleep(300)
            tapNodeCenter(finalSubmitNode)
            Log.i(TAG, "M4: Cash submit tapped (LIVE)")
        } finally {
            finalSubmitNode.recycle()
        }

        return M4CashResult.CashPaid
    }

    private fun findClickableAncestorOfText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val candidates = root.findAccessibilityNodeInfosByText(text)
        val match = candidates.firstOrNull {
            it.text?.toString()?.trim() == text || it.contentDescription?.toString()?.trim() == text
        }
        candidates.filter { it !== match }.forEach { it.recycle() }
        if (match == null) return null
        return try {
            JioPosStateDetector.nearestClickableAncestor(match)
        } finally {
            match.recycle()
        }
    }

    private fun findDeepestClickableButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var deepest: AccessibilityNodeInfo? = null
        var deepestDepth = -1

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (node.isClickable && node.isEnabled) {
                val cls = node.className?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                if (cls.contains("Button") || desc.startsWith("button", ignoreCase = true)) {
                    if (depth > deepestDepth) {
                        deepest?.recycle()
                        deepest = AccessibilityNodeInfo.obtain(node)
                        deepestDepth = depth
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                child.recycle()
            }
        }

        walk(root, 0)
        return deepest
    }

    // ── armed automation entry point ─────────────────────────────────────────
    fun armAutomatedRecharge(phone: String, amount: String) {
        // Stop any previous run cleanly
        if (isArmed || automationThread?.isAlive == true) {
            showToast("Stopping previous automation...")
            isArmed = false
            automationThread?.interrupt()
            try { automationThread?.join(2000) } catch (e: InterruptedException) { /* ok */ }
        }

        activePhone = phone
        activeAmount = amount
        isArmed = true

        automationThread = Thread {
            try {
                Log.i(TAG, "Arm Mode: waiting for JioPOS to come to foreground")
                showToast("Armed. Switch to JioPOS.")

                // Phase 1: Wait for JioPOS to be in foreground, past login
                var hasNotifiedLogin = false
                val armDeadline = System.currentTimeMillis() + 30 * 60_000 // 30 min max wait

                while (System.currentTimeMillis() < armDeadline) {
                    if (!isArmed) return@Thread
                    if (isJioPosInForeground) {
                        evaluateAndBroadcastState()
                        val state = lastDetectedState
                        when (state) {
                            JioPosState.HOME,
                            JioPosState.RECHARGE,
                            JioPosState.POST_LOGIN_SURVEY -> break
                            JioPosState.LOGIN,
                            JioPosState.LOGIN_IN_PROGRESS -> {
                                if (!hasNotifiedLogin) {
                                    showToast("JioPOS is at login — please log in manually.")
                                    hasNotifiedLogin = true
                                }
                            }
                            else -> { /* UNKNOWN or not foreground — keep waiting */ }
                        }
                    }
                    Thread.sleep(500)
                }

                if (!isArmed) return@Thread

                showToast("JioPOS detected — starting automation!")
                Log.i(TAG, "Automation triggered. State=${lastDetectedState}")

                // Phase 2: Dismiss any post-login overlays (survey, feedback dialog, etc.)
                // Loop up to 3 times — multiple dialogs can appear sequentially.
                safeSleep(2000) // let React settle after login
                // Dismiss texts cover both the native survey and any RN feedback/rating dialogs.
                val dismissTexts = listOf(
                    "Maybe Later", "Maybe later", "Not Now", "Not now",
                    "Skip", "SKIP", "Later", "LATER", "No Thanks", "No thanks",
                    "Close", "CLOSE", "Dismiss", "DISMISS", "Cancel", "CANCEL"
                )
                repeat(3) { pass ->
                    evaluateAndBroadcastState()
                    val curState = lastDetectedState
                    if (curState == JioPosState.HOME || curState == JioPosState.RECHARGE) return@repeat
                    val root = jiopOsRoot() ?: return@repeat
                    var dismissed = false
                    // Try known survey resource ID first
                    val maybeLater = root.findAccessibilityNodeInfosByViewId("com.jio.jpp1:id/btn_may_be")
                    if (maybeLater.isNotEmpty() && maybeLater[0].isClickable) {
                        tapNodeCenter(maybeLater[0])
                        dismissed = true
                        Log.i(TAG, "Phase2 pass $pass: dismissed overlay via btn_may_be")
                    }
                    maybeLater.forEach { it.recycle() }
                    // Generic text-based dismissal for feedback/rating dialogs
                    if (!dismissed) {
                        for (txt in dismissTexts) {
                            val nodes = root.findAccessibilityNodeInfosByText(txt)
                            val clickable = nodes.firstOrNull { it.isClickable && it.isEnabled }
                                ?: nodes.firstOrNull()?.let { n ->
                                    JioPosStateDetector.nearestClickableAncestor(n).also { n.recycle() }
                                }
                            nodes.filter { it !== clickable }.forEach { it.recycle() }
                            if (clickable != null) {
                                tapNodeCenter(clickable)
                                clickable.recycle()
                                dismissed = true
                                Log.i(TAG, "Phase2 pass $pass: dismissed overlay via text '$txt'")
                                break
                            }
                        }
                    }
                    root.recycle()
                    if (dismissed) safeSleep(1500) // wait for overlay to clear
                }

                // Phase 3: Navigate to Recharge from Home (if not already there)
                evaluateAndBroadcastState()
                if (lastDetectedState != JioPosState.RECHARGE) {
                    if (!waitForState(JioPosState.HOME, 15_000)) {
                        showToast("Not on Home screen. Aborting.")
                        isArmed = false; return@Thread
                    }
                    safeSleep(1000)
                    // Dismiss any feedback/rating popup that appears on the Home screen
                    run {
                        val root = jiopOsRoot()
                        if (root != null) {
                            var dismissed = false
                            val maybeLater = root.findAccessibilityNodeInfosByViewId("com.jio.jpp1:id/btn_may_be")
                            if (maybeLater.isNotEmpty() && maybeLater[0].isClickable) {
                                tapNodeCenter(maybeLater[0]); dismissed = true
                                Log.i(TAG, "Phase3 pre-nav: dismissed Home overlay via btn_may_be")
                            }
                            maybeLater.forEach { it.recycle() }
                            if (!dismissed) {
                                for (txt in dismissTexts) {
                                    val nodes = root.findAccessibilityNodeInfosByText(txt)
                                    val clickable = nodes.firstOrNull { it.isClickable && it.isEnabled }
                                        ?: nodes.firstOrNull()?.let { n ->
                                            JioPosStateDetector.nearestClickableAncestor(n).also { n.recycle() }
                                        }
                                    nodes.filter { it !== clickable }.forEach { it.recycle() }
                                    if (clickable != null) {
                                        tapNodeCenter(clickable); clickable.recycle(); dismissed = true
                                        Log.i(TAG, "Phase3 pre-nav: dismissed Home overlay via text '$txt'")
                                        break
                                    }
                                }
                            }
                            root.recycle()
                            if (dismissed) safeSleep(1000)
                        }
                    }
                    val navResult = navigateToRecharge()
                    if (navResult !is NavigateResult.Clicked) {
                        showToast("Failed to click Recharge tile. Aborting.")
                        isArmed = false; return@Thread
                    }
                    safeSleep(1500)
                }

                // Phase 4: Wait for Recharge screen to load
                if (!waitForState(JioPosState.RECHARGE, 12_000)) {
                    showToast("Recharge screen didn't load. Aborting.")
                    isArmed = false; return@Thread
                }
                safeSleep(1500)

                // Phase 5: Enter mobile number
                val entryResult = enterMobileNumber(phone)
                if (entryResult !is M3EntryResult.Entered) {
                    showToast("Failed to enter number: $entryResult. Aborting.")
                    isArmed = false; return@Thread
                }
                safeSleep(1500)

                // Phase 6: Select plan
                safeSleep(3000) // give plan-search screen time to load after Continue tap
                val planResult = selectPlan(amount)
                if (planResult !is M3PlanSelectionResult.PlanSelected) {
                    showToast("Failed to select plan: $planResult. Aborting.")
                    isArmed = false; return@Thread
                }
                safeSleep(1000)

                // Phase 7: Navigate Buy → Continue → Cash → human confirmation → submit
                // payCashAndConfirm contains the latch-based notification confirmation
                val cashResult = payCashAndConfirm()
                when (cashResult) {
                    is M4CashResult.CashPaid -> showToast("Recharge completed!")
                    else -> showToast("Cash payment aborted or failed: $cashResult")
                }

            } catch (e: InterruptedException) {
                Log.i(TAG, "Automation thread interrupted cleanly")
            } catch (e: Exception) {
                Log.e(TAG, "Automation thread error", e)
                showToast("Automation error: ${e.message}")
            } finally {
                isArmed = false
            }
        }
        automationThread!!.start()
    }

    // ── wait helpers ──────────────────────────────────────────────────────────
    private fun waitForState(targetState: JioPosState, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            evaluateAndBroadcastState()
            if (lastDetectedState == targetState) return true
            Thread.sleep(400)
        }
        return false
    }

    private fun safeSleep(ms: Long) {
        if (isArmed) Thread.sleep(ms)
    }

    // ── node collection helpers ───────────────────────────────────────────────
    private fun pollForEditTexts(timeoutMs: Long): List<AccessibilityNodeInfo> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && isArmed) {
            val root = jiopOsRoot()
            if (root != null) {
                val found = collectEditTextNodes(root)
                root.recycle()
                if (found.size >= 2) return found
                found.forEach { it.recycle() }
            }
            Thread.sleep(300)
        }
        return emptyList()
    }

    fun collectEditTextNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            if (node.isEditable) result.add(AccessibilityNodeInfo.obtain(node))
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
                child.recycle()
            }
        }
        walk(root)
        return result
    }

    private fun findNodeMatchingRegex(root: AccessibilityNodeInfo, regex: Regex): AccessibilityNodeInfo? {
        val text = root.text?.toString() ?: ""
        val desc = root.contentDescription?.toString() ?: ""
        if (regex.containsMatchIn(text) || regex.containsMatchIn(desc)) {
            return if (root.isClickable && root.isEnabled) AccessibilityNodeInfo.obtain(root)
            else JioPosStateDetector.nearestClickableAncestor(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findNodeMatchingRegex(child, regex)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun dismissGotItTooltips(root: AccessibilityNodeInfo) {
        val candidates = root.findAccessibilityNodeInfosByText("Got it")
        candidates.forEach { node ->
            if (node.isClickable && node.isEnabled) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Dismissed 'Got it' tooltip")
            }
            node.recycle()
        }
    }

    // ── gesture helpers ───────────────────────────────────────────────────────
    fun tapNodeCenter(node: AccessibilityNodeInfo, durationMs: Long = 50) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return
        tapCoord(bounds.centerX().toFloat(), bounds.centerY().toFloat(), durationMs)
    }

    fun tapCoord(x: Float, y: Float, durationMs: Long = 50) {
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val cb = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) = latch.countDown()
            override fun onCancelled(g: GestureDescription) = latch.countDown()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatchGesture(gesture, cb, null)
        } else {
            Handler(Looper.getMainLooper()).post { dispatchGesture(gesture, cb, null) }
            latch.await()
        }
    }

    private fun swipeUp() {
        val path = Path().apply { moveTo(540f, 1400f); lineTo(540f, 600f) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 400)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val cb = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) = latch.countDown()
            override fun onCancelled(g: GestureDescription) = latch.countDown()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatchGesture(gesture, cb, null)
        } else {
            Handler(Looper.getMainLooper()).post { dispatchGesture(gesture, cb, null) }
            latch.await()
        }
    }

    private fun setClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        Handler(Looper.getMainLooper()).post {
            cm.setPrimaryClip(ClipData.newPlainText("automation", text))
        }
        Thread.sleep(200) // ensure clip is set before paste
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }
}
