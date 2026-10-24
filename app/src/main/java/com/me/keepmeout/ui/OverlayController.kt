package com.me.keepmeout.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.me.keepmeout.R

class OverlayController(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null

    fun show(title: String, subtitle: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(title, subtitle) }
            return
        }
        val existing = root
        if (existing == null) {
            try {
                val view = LayoutInflater.from(appContext).inflate(R.layout.view_overlay, null, false)
                windowManager.addView(view, buildParams())
                root = view
                titleView = view.findViewById(R.id.overlayTitle)
                subtitleView = view.findViewById(R.id.overlaySubtitle)
                logGeometry(view)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to add overlay", e)
                return
            }
        }
        titleView?.text = title
        subtitleView?.text = subtitle
        subtitleView?.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide() }
            return
        }
        val view = root ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // The window may already have been detached by the system.
        }
        root = null
        titleView = null
        subtitleView = null
    }

    /**
     * Activity/Service configuration changes (rotation, font size, etc.) can leave an
     * application overlay at its old display position. Re-attach it with fresh metrics so
     * the full-width window and its centered card are laid out for the new orientation.
     */
    fun onConfigurationChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onConfigurationChanged() }
            return
        }
        val view = root ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // Not attached.
        }
        try {
            windowManager.addView(view, buildParams())
            logGeometry(view)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to re-add overlay after configuration change", e)
            root = null
            titleView = null
            subtitleView = null
        }
    }

    private fun buildParams(): WindowManager.LayoutParams {
        // Use the display's current width and let the window extend into a cutout.
        // Otherwise an overlay that avoids a left landscape cutout is centered inside
        // the narrower safe area and visually appears shifted to the right.
        val bounds = windowManager.currentWindowMetrics.bounds
        return WindowManager.LayoutParams(
            bounds.width(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

    private fun logGeometry(view: View) {
        view.post {
            try {
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                val card = view.findViewById<View>(R.id.overlayCard) ?: return@post
                val cardLocation = IntArray(2)
                card.getLocationOnScreen(cardLocation)
                Log.d(
                    TAG,
                    "overlay geometry display=${metrics.bounds.width()}x${metrics.bounds.height()} " +
                        "insets=$insets windowAt=${location[0]},${location[1]} " +
                        "window=${view.width}x${view.height} " +
                        "cardAt=${cardLocation[0]},${cardLocation[1]} " +
                        "card=${card.width}x${card.height} " +
                        "cardCenterX=${cardLocation[0] + card.width / 2} " +
                        "displayCenterX=${metrics.bounds.width() / 2}",
                )
            } catch (e: Exception) {
                Log.w(TAG, "Unable to log overlay geometry", e)
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "OverlayController"
    }
}
