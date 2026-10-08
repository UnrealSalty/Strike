package com.strike

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

// BYD discards every broadcast to a stopped app while parked, BOOT_COMPLETED included.
// Android still binds enabled accessibility services after boot, so this is Strike's start.
class BootStart : AccessibilityService() {
    override fun onServiceConnected() {
        BootDiagnostics.environment(this, "accessibility bound")
        prepareBootAccess(this)
        restoreAfterRestart(this, "after accessibility bind")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
