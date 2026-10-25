package com.me.keepmeout

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.me.keepmeout.data.AppPrefs
import com.me.keepmeout.data.LockPhase

class WhitelistActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private lateinit var searchInput: EditText
    private lateinit var appContainer: LinearLayout
    private val selectedPackages = linkedSetOf<String>()
    private val allApps = mutableListOf<AppEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        if (prefs.phase != LockPhase.IDLE) {
            Toast.makeText(this, R.string.whitelist_frozen_note, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.activity_whitelist)
        searchInput = findViewById(R.id.whitelistSearchInput)
        appContainer = findViewById(R.id.whitelistAppContainer)

        selectedPackages += prefs.whitelistPackages
        loadLaunchableApps()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                renderApps()
            }
        })

        findViewById<Button>(R.id.whitelistSaveButton).setOnClickListener {
            prefs.whitelistPackages = selectedPackages
            prefs.whitelistInitialized = true
            Toast.makeText(this, R.string.whitelist_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        renderApps()
    }

    private fun loadLaunchableApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val entries = packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_ALL,
        ).mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) return@mapNotNull null
            AppEntry(pkg, info.loadLabel(packageManager).toString())
        }.distinctBy { it.packageName }

        val knownPackages = entries.mapTo(mutableSetOf()) { it.packageName }
        val missingSelected = selectedPackages.filterNot { it in knownPackages }.map { pkg ->
            val label = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            } catch (_: Exception) {
                pkg
            }
            AppEntry(pkg, label)
        }

        allApps += entries
        allApps += missingSelected
        allApps.sortBy { it.label.lowercase() }
    }

    private fun renderApps() {
        appContainer.removeAllViews()
        val query = searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.lowercase().contains(query) ||
                    it.packageName.lowercase().contains(query)
            }
        }

        if (filtered.isEmpty()) {
            appContainer.addView(TextView(this).apply {
                text = getString(R.string.whitelist_no_matches)
                setTextColor(themeColor(android.R.attr.textColorSecondary))
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }

        filtered.forEach { app ->
            appContainer.addView(CheckBox(this).apply {
                text = app.label
                textSize = 15f
                isChecked = selectedPackages.contains(app.packageName)
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        selectedPackages += app.packageName
                    } else {
                        selectedPackages -= app.packageName
                    }
                }
            })
        }
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return android.graphics.Color.DKGRAY
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class AppEntry(val packageName: String, val label: String)
}
