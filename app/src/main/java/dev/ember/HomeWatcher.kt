package dev.ember

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * The stock home (Google TV's launcherx, or Android TV's tvlauncher) outranks any other HOME app, so the Home button
 * always opens it. This service only hears window changes from those packages (res/xml/home_watcher.xml) and brings
 * Ember forward instead.
 */
class HomeWatcher : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            startActivity(Intent(this, Home::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onInterrupt() {}
}
