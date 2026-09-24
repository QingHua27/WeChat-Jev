package com.jev.relationship.service

internal class AccessibilityOverlayPackagePolicy(
    private val targetPackage: String,
    private val gracePeriodMs: Long = 3_000L,
) {
    private var lastTargetEventAt: Long? = null

    fun shouldClearSnapshot(
        eventPackage: String?,
        activeWindowPackage: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (eventPackage == targetPackage) {
            lastTargetEventAt = nowMs
            return false
        }
        if (activeWindowPackage == targetPackage) return false
        val lastTargetEvent = lastTargetEventAt ?: return false
        return nowMs - lastTargetEvent >= gracePeriodMs &&
            activeWindowPackage != null &&
            activeWindowPackage != targetPackage
    }

    fun isTargetWindow(
        eventPackage: String?,
        activeWindowPackage: String?,
    ): Boolean = eventPackage == targetPackage || activeWindowPackage == targetPackage
}
