package com.me.keepmeout

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.me.keepmeout.data.AppPrefs
import com.me.keepmeout.service.ServiceActions

/**
 * Shown after the phone is unlocked during an active lock session. It lists only the
 * whitelisted apps, and closes itself as soon as the lock session ends.
 */
class WhitelistHomeActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private lateinit var grid: GridView
    private lateinit var emptyText: TextView
    private lateinit var adapter: AppAdapter
    private var receiverRegistered = false
    private var ready = false
    private var explicitOpen = false

    private val handler = Handler(Looper.getMainLooper())

    private val sessionChecker = object : Runnable {
        override fun run() {
            if (!lockSessionActive()) {
                finish()
                return
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ServiceActions.ACTION_HIDE_WHITELIST_HOME) {
                finish()
                return
            }
            if (!lockSessionActive() || shouldYieldToWhitelistApp()) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        explicitOpen = intent?.getBooleanExtra(EXTRA_EXPLICIT, false) == true
        if (!lockSessionActive() || shouldYieldToWhitelistApp()) {
            finish()
            return
        }

        setContentView(R.layout.activity_whitelist_home)
        grid = findViewById(R.id.whitelistHomeGrid)
        emptyText = findViewById(R.id.whitelistHomeEmpty)

        adapter = AppAdapter(loadApps())
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            launchApp(adapter.getItem(position))
        }
        updateContent()
        ready = true
    }

    override fun onStart() {
        super.onStart()
        if (!ready) return
        if (!receiverRegistered) {
            registerReceiver(
                stateReceiver,
                IntentFilter().apply {
                    addAction(ServiceActions.ACTION_STATE_CHANGED)
                    addAction(ServiceActions.ACTION_HIDE_WHITELIST_HOME)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        handler.post(sessionChecker)
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        if (!lockSessionActive() || shouldYieldToWhitelistApp()) {
            finish()
            return
        }
        adapter.update(loadApps())
        updateContent()
    }

    override fun onStop() {
        handler.removeCallbacks(sessionChecker)
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver)
            } catch (_: Exception) {
                // Already unregistered.
            }
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (!ready) return
        intent?.let(::setIntent)
        explicitOpen = intent?.getBooleanExtra(EXTRA_EXPLICIT, false) == true
        if (shouldYieldToWhitelistApp()) {
            finish()
            return
        }
        adapter.update(loadApps())
        updateContent()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Keep the page in front until the lock session ends. The user can still use
        // the whitelisted apps shown here.
    }

    private fun updateContent() {
        val count = adapter.count
        // With a short emergency list, fewer stretched columns keep the icons centered
        // instead of leaving the last columns empty.
        grid.numColumns = when {
            count <= 1 -> 1
            count == 2 -> 2
            else -> 3
        }
        val empty = count == 0
        grid.visibility = if (empty) View.GONE else View.VISIBLE
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun lockSessionActive(): Boolean {
        val now = System.currentTimeMillis()
        if ((prefs.committedEndMillis ?: 0L) > now) return true
        return prefs.stateActiveUntilMillis > now
    }

    /**
     * A whitelist app is already running (the countdown is in whitelist mode). An
     * auto-opened page must not cover it; a page opened from the notification must
     * stay, because the user explicitly asked for it.
     */
    private fun shouldYieldToWhitelistApp(): Boolean {
        if (explicitOpen || prefs.statePeekActive) return false
        return prefs.stateGraceEndElapsed > SystemClock.elapsedRealtime()
    }

    private fun loadApps(): List<AppEntry> {
        val packages = prefs.sessionWhitelistPackages.ifEmpty { prefs.whitelistPackages }
        return packages.mapNotNull { packageName ->
            try {
                val info = packageManager.getApplicationInfo(packageName, 0)
                AppEntry(
                    packageName = packageName,
                    label = packageManager.getApplicationLabel(info).toString(),
                    icon = packageManager.getApplicationIcon(info),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Unable to load whitelisted app $packageName", e)
                null
            }
        }.sortedBy { it.label.lowercase() }
    }

    private fun launchApp(app: AppEntry) {
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null) {
            Toast.makeText(this, R.string.whitelist_home_launch_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to launch ${app.packageName}", e)
            Toast.makeText(this, R.string.whitelist_home_launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class AppAdapter(private var apps: List<AppEntry>) : BaseAdapter() {
        override fun getCount(): Int = apps.size

        override fun getItem(position: Int): AppEntry = apps[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(
                R.layout.item_whitelist_app,
                parent,
                false,
            )
            val app = apps[position]
            view.findViewById<ImageView>(R.id.whitelistAppIcon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.whitelistAppLabel).text = app.label
            return view
        }

        fun update(newApps: List<AppEntry>) {
            apps = newApps
            notifyDataSetChanged()
        }
    }

    private data class AppEntry(
        val packageName: String,
        val label: String,
        val icon: Drawable,
    )

    companion object {
        private const val TAG = "WhitelistHome"
        const val EXTRA_EXPLICIT = "com.me.keepmeout.extra.EXPLICIT_WHITELIST_HOME"
    }
}
