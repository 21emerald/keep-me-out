package com.me.keepmeout.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import com.me.keepmeout.R

class LockAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var overlayView: View? = null
    private var overlayTitle: TextView? = null
    private var overlaySubtitle: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foregroundPackage = event.packageName?.toString() ?: return
        lastForegroundPackage = foregroundPackage
        LockService.instance?.onForegroundPackage(foregroundPackage)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        hideStatusOverlay()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideStatusOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    /**
     * Package of the topmost application window. Window-state events alone are not
     * reliable on some ROMs (especially when an app is brought back from the background
     * without a state change), so the lock service polls this as a fallback.
     * Only the window's package name is read; no screen content is inspected.
     */
    fun currentForegroundPackage(): String? = applicationWindowPackages().firstOrNull()

    /** Application windows ordered from topmost to bottommost. */
    fun applicationWindowPackages(): List<String> {
        val list = try {
            windows
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return list.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull {
                try {
                    it.root?.packageName?.toString()
                } catch (_: Exception) {
                    null
                }
            }
            .toList()
    }

    /**
     * Draws the countdown with TYPE_ACCESSIBILITY_OVERLAY instead of the ordinary
     * application overlay. The system phone/contacts app (and some other sensitive
     * apps) hide TYPE_APPLICATION_OVERLAY windows, but accessibility overlays stay
     * visible because they are drawn on behalf of the accessibility service.
     */
    fun showStatusOverlay(title: String, subtitle: String): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showStatusOverlay(title, subtitle) }
            return false
        }
        var view = overlayView
        if (view == null) {
            try {
                view = LayoutInflater.from(this).inflate(R.layout.view_overlay, null, false)
                windowManager.addView(view, overlayParams())
                overlayView = view
                overlayTitle = view.findViewById(R.id.overlayTitle)
                overlaySubtitle = view.findViewById(R.id.overlaySubtitle)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to add accessibility overlay", e)
                overlayView = null
                overlayTitle = null
                overlaySubtitle = null
                return false
            }
        }
        overlayTitle?.text = title
        overlaySubtitle?.text = subtitle
        overlaySubtitle?.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        return true
    }

    fun hideStatusOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hideStatusOverlay() }
            return
        }
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // The window may already have been detached by the system.
        }
        overlayView = null
        overlayTitle = null
        overlaySubtitle = null
    }

    fun refreshStatusOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { refreshStatusOverlay() }
            return
        }
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // Not attached.
        }
        try {
            windowManager.addView(view, overlayParams())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to re-add accessibility overlay", e)
            overlayView = null
            overlayTitle = null
            overlaySubtitle = null
        }
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        val width = windowManager.currentWindowMetrics.bounds.width()
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(54)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "LockAccessibility"

        @Volatile
        var instance: LockAccessibilityService? = null
            private set

        @Volatile
        var lastForegroundPackage: String? = null
            private set
    }
}
