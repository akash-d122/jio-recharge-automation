package com.example.jioposinspector

import android.view.accessibility.AccessibilityNodeInfo

enum class JioPosState {
    JIOPOS_NOT_FOREGROUND,
    LOGIN,
    LOGIN_IN_PROGRESS,
    POST_LOGIN_SURVEY,
    HOME,
    RECHARGE,
    UNKNOWN;

    fun label(): String = when (this) {
        JIOPOS_NOT_FOREGROUND -> "JioPOS not foreground"
        LOGIN                 -> "Login screen — please login manually"
        LOGIN_IN_PROGRESS     -> "Login in progress"
        POST_LOGIN_SURVEY     -> "Post-login survey"
        HOME                  -> "Home screen — ready"
        RECHARGE              -> "Recharge screen"
        UNKNOWN               -> "Unknown screen"
    }
}

object JioPosStateDetector {

    private const val PKG = "com.jio.jpp1"

    // Runtime-confirmed IDs from M1 inspection reports
    private const val ID_USERNAME  = "$PKG:id/edtJDSUserName"
    private const val ID_PASSWORD  = "$PKG:id/edtJDSPassword"
    private const val ID_LOGIN_BTN = "$PKG:id/btnJSDLogin"
    private const val ID_SURVEY    = "$PKG:id/btn_may_be"
    private const val ID_HOME_GRID = "$PKG:id/rvQuickLinks"

    // Exact visible text of the Recharge quick-link tile (M1 confirmed)
    private const val TEXT_RECHARGE_TILE = "Recharge"

    /**
     * Detect the current JioPOS state from the live accessibility tree.
     * [root] must be the JioPOS window root — caller owns lifecycle (recycle after use).
     * [activityClass] is the full class name from the last TYPE_WINDOW_STATE_CHANGED event.
     */
    fun detect(root: AccessibilityNodeInfo, activityClass: String?): JioPosState {
        // Survey check first — it can overlay any activity
        if (hasNode(root, ID_SURVEY)) return JioPosState.POST_LOGIN_SURVEY

        return when {
            activityClass?.endsWith("DIBLoginActivity") == true ||
            activityClass?.endsWith("RPOSMDMLoginActivity") == true -> detectLoginState(root)

            activityClass?.endsWith("RPOSHomeActivity") == true -> JioPosState.HOME

            activityClass?.endsWith("RechargeReactActivity") == true -> JioPosState.RECHARGE

            // Activity class not yet reported or is a transition frame — probe nodes
            hasNode(root, ID_USERNAME) -> detectLoginState(root)
            hasNode(root, ID_HOME_GRID) -> JioPosState.HOME

            else -> JioPosState.UNKNOWN
        }
    }

    private fun detectLoginState(root: AccessibilityNodeInfo): JioPosState {
        val hasLoginFields = hasNode(root, ID_USERNAME) && hasNode(root, ID_PASSWORD)
        val hasLoginBtn    = hasNode(root, ID_LOGIN_BTN)
        return if (hasLoginFields && hasLoginBtn) JioPosState.LOGIN else JioPosState.LOGIN_IN_PROGRESS
    }

    /**
     * Finds the Recharge quick-link tile on the Home screen.
     *
     * Runtime structure (M2 confirmed): rvQuickLinks > RelativeLayout (clickable) > TextView (text="Recharge").
     * The clickable node is the PARENT of the text node, so we locate the text leaf first,
     * then walk up to the nearest clickable+enabled ancestor within rvQuickLinks.
     *
     * Returns the clickable ancestor node. Caller must recycle.
     */
    fun findRechargeNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val grids = root.findAccessibilityNodeInfosByViewId(ID_HOME_GRID)
        if (grids.isEmpty()) return null

        val grid = grids.first()
        grids.drop(1).forEach { it.recycle() }

        return try {
            findClickableAncestorOfExactText(grid, TEXT_RECHARGE_TILE)
        } finally {
            grid.recycle()
        }
    }

    /**
     * Searches [container]'s subtree for a leaf node with [text] (exact match),
     * then walks its parent chain to the nearest clickable+enabled ancestor.
     *
     * Uses findAccessibilityNodeInfosByText for discovery (it returns the TextView),
     * then validates exact match and walks up. Returns obtained copy or null.
     */
    fun findClickableAncestorOfExactText(container: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // findAccessibilityNodeInfosByText does substring match, so we validate exact match after.
        val candidates = container.findAccessibilityNodeInfosByText(text)
        val textNode = candidates.firstOrNull { it.text?.toString() == text }
        candidates.filter { it !== textNode }.forEach { it.recycle() }
        if (textNode == null) return null

        return try {
            nearestClickableAncestor(textNode)
        } finally {
            textNode.recycle()
        }
    }

    /**
     * Walks the parent chain from [node] (inclusive) upward, returning an obtained
     * copy of the first node that is clickable and enabled, or null.
     *
     * Each intermediate parent is recycled after stepping up. The returned node is
     * obtained so the caller owns it independently.
     */
    fun nearestClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.isEnabled) return AccessibilityNodeInfo.obtain(node)
        val parent = node.parent ?: return null
        return try {
            nearestClickableAncestor(parent)
        } finally {
            parent.recycle()
        }
    }

    /**
     * Searches [container]'s subtree for a node whose contentDescription equals [desc] (exact),
     * then walks to the nearest clickable+enabled ancestor. Returns obtained copy or null.
     * Caller must recycle.
     */
    fun findClickableAncestorOfExactDesc(container: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? =
        walkForDesc(container, desc)

    private fun walkForDesc(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == desc) {
            return if (node.isClickable && node.isEnabled) AccessibilityNodeInfo.obtain(node)
            else nearestClickableAncestor(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = walkForDesc(child, desc)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    /** Returns true if any node with [viewId] exists under [root]. */
    fun hasNode(root: AccessibilityNodeInfo, viewId: String): Boolean {
        val found = root.findAccessibilityNodeInfosByViewId(viewId)
        val exists = found.isNotEmpty()
        found.forEach { it.recycle() }
        return exists
    }

    /** Returns the first enabled, clickable node matching [viewId], or null. Caller must recycle. */
    fun findClickable(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val candidates = root.findAccessibilityNodeInfosByViewId(viewId)
        val match = candidates.firstOrNull { it.isEnabled && it.isClickable }
        candidates.filter { it !== match }.forEach { it.recycle() }
        return match
    }
}
