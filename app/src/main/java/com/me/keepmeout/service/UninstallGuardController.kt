package com.me.keepmeout.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.me.keepmeout.R
import com.me.keepmeout.data.AppPrefs
import com.me.keepmeout.data.LockPhase
import com.me.keepmeout.ui.ProtectionNotifier
import com.me.keepmeout.util.PermissionState

/**
 * Blocks uninstall screens while a lock session is active.
 *
 * The guard backs out of the dangerous screen first; if the screen survives the back
 * action for [RECHECK_DELAY_MS], the phone is locked. Install screens are never matched.
 */
class UninstallGuardController(private val service: LockAccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = AppPrefs(service.applicationContext)
    private var lastProbeElapsed = 0L
    private var lastActionElapsed = 0L
    private var pendingRecheck: Runnable? = null

    /** Returns true when the event was a dangerous screen that the guard handled. */
    fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
        val pkg = event.packageName?.toString() ?: return false
        if (!UninstallGuard.isCandidate(pkg)) return false
        if (!prefs.isProtectionSessionActive()) return false
        if (!isScreenAwakeAndUnlocked()) return false

        val now = SystemClock.elapsedRealtime()
        val isContentChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (isContentChange && now - lastProbeElapsed < PROBE_THROTTLE_MS) return false
        if (now - lastActionElapsed < ACTION_COOLDOWN_MS) return false
        // Throttle content-change events even when they are not dangerous; scrolling
        // through Settings would otherwise re-read the node tree many times a second.
        lastProbeElapsed = now

        val className = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString()
        } else {
            null
        }
        val reason = UninstallGuard.detect(GuardScreen(pkg, className, collectTexts()))
            ?: return false
        intercept(reason)
        return true
    }

    fun onUnbindIfSessionActive() {
        if (!prefs.isProtectionSessionActive()) return
        // onUnbind also fires on app updates and reboots; only record a tamper when the
        // accessibility setting really is off.
        if (PermissionState.accessibilityEnabled(service)) return
        prefs.lastTamperMillis = System.currentTimeMillis()
        prefs.lastTamperReason = service.getString(R.string.protection_tamper_accessibility)
        ProtectionNotifier.notifyGuardLost(service)
    }

    private fun intercept(reason: GuardReason) {
        lastActionElapsed = SystemClock.elapsedRealtime()
        val message = when (reason) {
            GuardReason.UNINSTALL -> R.string.guard_toast_uninstall
            GuardReason.DEVICE_ADMIN, GuardReason.ACCESSIBILITY -> R.string.guard_toast_protection
        }
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        scheduleRecheck()
    }

    private fun scheduleRecheck() {
        pendingRecheck?.let(handler::removeCallbacks)
        val runnable = Runnable {
            pendingRecheck = null
            if (!prefs.isProtectionSessionActive()) return@Runnable
            if (!isScreenAwakeAndUnlocked()) return@Runnable
            if (prefs.phase == LockPhase.WARNING) return@Runnable
            val pkg = service.currentForegroundPackage() ?: return@Runnable
            if (!UninstallGuard.isCandidate(pkg)) return@Runnable
            val reason = UninstallGuard.detect(GuardScreen(pkg, null, collectTexts()))
                ?: return@Runnable
            Log.d(TAG, "dangerous screen survived back: $pkg ($reason); locking")
            lockFromGuard()
        }
        pendingRecheck = runnable
        handler.postDelayed(runnable, RECHECK_DELAY_MS)
    }

    private fun lockFromGuard() {
        if (LockService.instance?.lockFromGuard() == true) return
        if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) return
        if (!PermissionState.deviceAdminActive(service)) return
        try {
            service.getSystemService(DevicePolicyManager::class.java).lockNow()
        } catch (e: Exception) {
            Log.w(TAG, "Device admin lock failed", e)
        }
    }

    private fun isScreenAwakeAndUnlocked(): Boolean {
        val power = service.getSystemService(PowerManager::class.java)
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private fun collectTexts(): List<String> {
        val result = LinkedHashSet<String>()
        try {
            collectFromNode(service.rootInActiveWindow, result)
        } catch (_: Exception) {
            // The active window may disappear while it is being inspected.
        }
        try {
            service.windows?.forEach { window ->
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
                try {
                    collectFromNode(window.root, result)
                } catch (_: Exception) {
                    // Window detached between listing and reading.
                }
            }
        } catch (_: Exception) {
            // Ignore window listing failures.
        }
        return result.toList()
    }

    private fun collectFromNode(root: AccessibilityNodeInfo?, result: MutableSet<String>) {
        if (root == null) return
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(result::add)
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(result::add)
            if (depth >= MAX_DEPTH) continue
            try {
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { queue.add(it to depth + 1) }
                }
            } catch (_: Exception) {
                // Skip subtrees that cannot be read.
            }
        }
    }

    companion object {
        private const val TAG = "UninstallGuard"
        private const val PROBE_THROTTLE_MS = 250L
        private const val ACTION_COOLDOWN_MS = 600L
        private const val RECHECK_DELAY_MS = 700L
        private const val MAX_NODES = 250
        private const val MAX_DEPTH = 8
    }
}
