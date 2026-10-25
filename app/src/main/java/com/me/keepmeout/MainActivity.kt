package com.me.keepmeout

import android.app.Activity
import android.app.LocaleManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.provider.Telephony
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.app.AlertDialog
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.me.keepmeout.data.AppPrefs
import com.me.keepmeout.data.LockPhase
import com.me.keepmeout.data.Schedule
import com.me.keepmeout.data.ScheduleType
import com.me.keepmeout.service.LockService
import com.me.keepmeout.service.ServiceActions
import com.me.keepmeout.util.PermissionState
import com.me.keepmeout.util.TimeText
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var cancelWarningButton: Button
    private lateinit var exitLockButton: Button
    private lateinit var manualMinutesInput: EditText
    private lateinit var startManualButton: Button
    private lateinit var preLockInput: EditText
    private lateinit var peekInput: EditText
    private lateinit var saveSettingsButton: Button
    private lateinit var whitelistPickerButton: Button
    private lateinit var whitelistLimitSwitch: Switch
    private lateinit var whitelistDurationInput: EditText
    private lateinit var whitelistUnitSpinner: Spinner
    private lateinit var whitelistFrozenNote: TextView
    private var whitelistUnitIsMinutes = true
    private var suppressWhitelistUnitChange = false
    private lateinit var languageButton: Button
    private lateinit var permissionSummary: TextView
    private lateinit var permissionContainer: LinearLayout
    private lateinit var scheduleContainer: LinearLayout
    private lateinit var noTasksText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var tickerRunning = false
    private var canStartNow = false
    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshStatus()
            refreshTaskList()
        }
    }

    private val uiTicker = object : Runnable {
        override fun run() {
            if (!tickerRunning) return
            refreshStatus()
            syncTaskListIfNeeded()
            handler.postDelayed(this, 1000L)
        }
    }

    private var renderedSchedules: List<Schedule> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        setContentView(R.layout.activity_main)

        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        cancelWarningButton = findViewById(R.id.cancelWarningButton)
        exitLockButton = findViewById(R.id.exitLockButton)
        manualMinutesInput = findViewById(R.id.manualMinutesInput)
        startManualButton = findViewById(R.id.startManualButton)
        preLockInput = findViewById(R.id.preLockInput)
        peekInput = findViewById(R.id.peekInput)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        whitelistPickerButton = findViewById(R.id.whitelistPickerButton)
        whitelistLimitSwitch = findViewById(R.id.whitelistLimitSwitch)
        whitelistDurationInput = findViewById(R.id.whitelistDurationInput)
        whitelistUnitSpinner = findViewById(R.id.whitelistUnitSpinner)
        whitelistFrozenNote = findViewById(R.id.whitelistFrozenNote)
        languageButton = findViewById(R.id.languageButton)
        permissionSummary = findViewById(R.id.permissionSummary)
        permissionContainer = findViewById(R.id.permissionContainer)
        scheduleContainer = findViewById(R.id.scheduleContainer)
        noTasksText = findViewById(R.id.noTasksText)

        prefs.ensureWhitelistInitialized(defaultWhitelistPackages())
        preLockInput.setText(prefs.preLockSeconds.toString())
        peekInput.setText(prefs.graceSeconds.toString())
        whitelistLimitSwitch.isChecked = prefs.whitelistLimitEnabled
        setupWhitelistDuration()

        whitelistPickerButton.setOnClickListener {
            if (prefs.phase == LockPhase.IDLE) {
                startActivity(Intent(this, WhitelistActivity::class.java))
            } else {
                Toast.makeText(this, R.string.whitelist_frozen_note, Toast.LENGTH_SHORT).show()
            }
        }

        startManualButton.setOnClickListener { startManualClicked() }
        cancelWarningButton.setOnClickListener {
            startLockService(ServiceActions.ACTION_CANCEL_WARNING)
        }
        exitLockButton.setOnClickListener {
            startLockService(ServiceActions.ACTION_EXIT_LOCK_SESSION)
        }
        saveSettingsButton.setOnClickListener {
            if (applySettings(showToast = true)) refreshStatus()
        }
        updateLanguageButton()
        languageButton.setOnClickListener { showLanguageDialog() }
        findViewById<Button>(R.id.addScheduleButton).setOnClickListener {
            startActivity(
                Intent(this, ScheduleEditActivity::class.java)
                    .putExtra(ScheduleEditActivity.EXTRA_SCHEDULE_ID, -1L)
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            registerReceiver(
                stateReceiver,
                IntentFilter(ServiceActions.ACTION_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        tickerRunning = true
        handler.post(uiTicker)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        if (serviceRelevant()) {
            startLockService(ServiceActions.ACTION_RECONCILE)
        }
    }

    override fun onStop() {
        tickerRunning = false
        handler.removeCallbacks(uiTicker)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshAll()
    }

    private fun refreshAll() {
        updateLanguageButton()
        refreshPermissions()
        refreshTaskList()
        refreshStatus()
    }

    private fun refreshStatus() {
        val now = System.currentTimeMillis()
        val phase = prefs.phase
        val error = prefs.stateError.trim()
        var detail = ""
        var showCancel = false

        when (phase) {
            LockPhase.IDLE -> {
                statusTitle.setText(R.string.status_idle)
                val next = prefs.stateNextStartMillis
                detail = if (next > now) {
                    getString(R.string.next_lock_at, TimeText.full(this, next))
                } else {
                    getString(R.string.next_lock_none)
                }
            }

            LockPhase.WARNING -> {
                statusTitle.setText(R.string.status_warning)
                val lockAt = prefs.stateLockAtMillis
                val seconds = ceil((lockAt - now).coerceAtLeast(0L) / 1000.0).toInt()
                detail = getString(R.string.warning_detail, seconds, TimeText.full(this, lockAt))
                showCancel = true
            }

            LockPhase.LOCKED -> {
                statusTitle.setText(R.string.status_locked)
                val until = prefs.stateActiveUntilMillis
                detail = if (until > now) {
                    getString(R.string.lock_until, TimeText.full(this, until))
                } else {
                    getString(R.string.next_lock_none)
                }
            }

            LockPhase.GRACE -> {
                val until = prefs.stateActiveUntilMillis
                val whitelistEnd = prefs.stateGraceEndElapsed
                if (prefs.statePeekActive) {
                    statusTitle.setText(R.string.status_grace)
                    val remaining = (whitelistEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    val seconds = ceil(remaining / 1000.0).toInt()
                    detail = getString(
                        R.string.grace_detail,
                        seconds,
                        TimeText.full(this, until),
                    )
                } else {
                    statusTitle.setText(R.string.status_whitelist)
                    detail = if (whitelistEnd > 0L) {
                        val remaining = (whitelistEnd - SystemClock.elapsedRealtime())
                            .coerceAtLeast(0L)
                        getString(
                            R.string.whitelist_detail,
                            TimeText.remaining(remaining),
                            TimeText.full(this, until),
                        )
                    } else {
                        getString(
                            R.string.whitelist_unlimited_detail,
                            TimeText.full(this, until),
                        )
                    }
                }
            }
        }

        if (error.isNotEmpty()) {
            detail = if (detail.isBlank()) error else "$detail\n$error"
        }
        statusDetail.text = detail
        cancelWarningButton.visibility = if (showCancel) View.VISIBLE else View.GONE
        val canEditSettings = phase == LockPhase.IDLE
        exitLockButton.visibility = if (
            BuildConfig.DEBUG && (phase == LockPhase.LOCKED || phase == LockPhase.GRACE)
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        exitLockButton.setText(R.string.debug_exit_lock)
        preLockInput.isEnabled = canEditSettings
        peekInput.isEnabled = canEditSettings
        saveSettingsButton.isEnabled = canEditSettings
        whitelistPickerButton.isEnabled = canEditSettings
        whitelistLimitSwitch.isEnabled = canEditSettings
        whitelistDurationInput.isEnabled = canEditSettings
        whitelistUnitSpinner.isEnabled = canEditSettings
        whitelistFrozenNote.visibility = if (canEditSettings) View.GONE else View.VISIBLE
        startManualButton.isEnabled = phase == LockPhase.IDLE && canStartNow
    }

    private fun refreshPermissions() {
        canStartNow = PermissionState.canStart(this)
        val missing = PermissionState.missingRequired(this)
        if (missing.isEmpty()) {
            permissionSummary.setText(R.string.permission_configured)
            permissionSummary.setTextColor(getColor(R.color.success))
        } else {
            permissionSummary.text = getString(
                R.string.permission_required,
                missing.joinToString(getString(R.string.day_separator)),
            )
            permissionSummary.setTextColor(getColor(R.color.danger))
        }

        permissionContainer.removeAllViews()

        addPermissionRow(
            label = getString(R.string.permission_notification),
            status = permissionOnOff(PermissionState.notificationsGranted(this)),
            ok = PermissionState.notificationsGranted(this),
            required = true,
            actionText = getString(
                if (PermissionState.notificationsGranted(this)) R.string.permission_settings
                else R.string.permission_open
            ),
            action = { openNotificationSettings() },
        )

        addPermissionRow(
            label = getString(R.string.permission_overlay),
            status = permissionOnOff(PermissionState.overlayGranted(this)),
            ok = PermissionState.overlayGranted(this),
            required = true,
            actionText = getString(
                if (PermissionState.overlayGranted(this)) R.string.permission_settings
                else R.string.permission_open
            ),
            action = { openOverlaySettings() },
        )

        addPermissionRow(
            label = getString(R.string.permission_accessibility),
            status = permissionOnOff(PermissionState.accessibilityEnabled(this)),
            ok = PermissionState.accessibilityEnabled(this),
            required = true,
            actionText = getString(
                if (PermissionState.accessibilityEnabled(this)) R.string.permission_settings
                else R.string.permission_open
            ),
            action = { openAccessibilitySettings() },
        )

        addPermissionRow(
            label = getString(R.string.permission_device_admin),
            status = permissionOnOff(PermissionState.deviceAdminActive(this)),
            ok = PermissionState.deviceAdminActive(this),
            required = !PermissionState.accessibilityEnabled(this),
            actionText = getString(
                if (PermissionState.deviceAdminActive(this)) R.string.permission_settings
                else R.string.permission_open
            ),
            action = { openDeviceAdmin() },
        )

        addPermissionRow(
            label = getString(R.string.permission_battery),
            status = getString(
                if (PermissionState.batteryOptimizationIgnored(this)) R.string.permission_on
                else R.string.permission_off
            ),
            ok = PermissionState.batteryOptimizationIgnored(this),
            required = false,
            actionText = getString(
                if (PermissionState.batteryOptimizationIgnored(this)) R.string.permission_settings
                else R.string.permission_fix
            ),
            action = { requestBatteryWhitelist() },
        )

        addPermissionRow(
            label = getString(R.string.permission_secure),
            status = getString(
                if (PermissionState.secureLockScreen(this)) R.string.permission_on
                else R.string.permission_off
            ),
            ok = PermissionState.secureLockScreen(this),
            required = true,
            actionText = getString(
                if (PermissionState.secureLockScreen(this)) R.string.permission_settings
                else R.string.permission_fix
            ),
            action = { openSecuritySettings() },
        )

        val exact = PermissionState.exactAlarmsGranted(this)
        addPermissionRow(
            label = getString(R.string.permission_exact_alarm),
            status = getString(if (exact) R.string.permission_ok else R.string.permission_problem),
            ok = exact,
            required = true,
            actionText = if (exact) null else getString(R.string.permission_fix),
            action = { openAppDetails() },
        )

        refreshStatus()
    }

    private fun permissionOnOff(on: Boolean): String =
        getString(if (on) R.string.permission_on else R.string.permission_off)

    private fun addPermissionRow(
        label: String,
        status: String,
        ok: Boolean,
        required: Boolean,
        actionText: String?,
        action: () -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        textColumn.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(themeColor(android.R.attr.textColorPrimary))
        })
        textColumn.addView(TextView(this).apply {
            text = status
            textSize = 12f
            setTextColor(
                getColor(
                    when {
                        ok -> R.color.success
                        required -> R.color.danger
                        else -> R.color.warning
                    }
                )
            )
        })
        row.addView(textColumn)

        if (actionText != null) {
            row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = actionText
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(10), 0, dp(10), 0)
                setTextColor(getColor(R.color.accent))
                setOnClickListener { action() }
            })
        }

        permissionContainer.addView(row)
    }

    private fun currentVisibleSchedules(): List<Schedule> {
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val suppressed = prefs.suppressedStarts()
        return prefs.schedules.filterNot { schedule ->
            if (schedule.type != ScheduleType.ONCE) return@filterNot false
            val window = schedule.onceWindow(zone) ?: return@filterNot true
            window.second <= now || suppressed.contains(window.first)
        }
    }

    private fun syncTaskListIfNeeded() {
        if (currentVisibleSchedules() != renderedSchedules) {
            refreshTaskList()
        }
    }

    private fun refreshTaskList() {
        scheduleContainer.removeAllViews()
        val schedules = currentVisibleSchedules()
        if (schedules != prefs.schedules) {
            prefs.saveSchedules(schedules)
        }
        renderedSchedules = schedules
        noTasksText.visibility = if (schedules.isEmpty()) View.VISIBLE else View.GONE

        schedules.forEach { schedule ->
            val row = layoutInflater.inflate(R.layout.item_schedule, scheduleContainer, false)
            val title = row.findViewById<TextView>(R.id.scheduleTitle)
            val subtitle = row.findViewById<TextView>(R.id.scheduleSubtitle)
            val switch = row.findViewById<Switch>(R.id.scheduleSwitch)
            val edit = row.findViewById<Button>(R.id.editScheduleButton)

            title.text = scheduleTitle(schedule)
            subtitle.text = getString(
                if (schedule.enabled) R.string.schedule_enabled else R.string.schedule_disabled
            )
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = schedule.enabled

            var suppressSwitch = false
            switch.setOnCheckedChangeListener { _, checked ->
                if (suppressSwitch) return@setOnCheckedChangeListener
                if (checked && !PermissionState.canStart(this@MainActivity)) {
                    suppressSwitch = true
                    switch.isChecked = false
                    suppressSwitch = false
                    Toast.makeText(
                        this@MainActivity,
                        R.string.need_permissions,
                        Toast.LENGTH_SHORT,
                    ).show()
                    refreshPermissions()
                    return@setOnCheckedChangeListener
                }
                val updated = schedule.copy(
                    enabled = checked,
                    enabledAtMillis = if (checked) System.currentTimeMillis() else schedule.enabledAtMillis,
                )
                val current = prefs.schedules.toMutableList()
                val index = current.indexOfFirst { it.id == schedule.id }
                if (index >= 0) {
                    current[index] = updated
                    prefs.saveSchedules(current)
                }
                subtitle.text = getString(
                    if (checked) R.string.schedule_enabled else R.string.schedule_disabled
                )
                startLockService(ServiceActions.ACTION_RECONCILE)
                refreshStatus()
            }

            val open = { openSchedule(schedule.id) }
            row.setOnClickListener { open() }
            edit.setOnClickListener { open() }
            scheduleContainer.addView(row)
        }
    }

    private fun currentLanguageCode(): String {
        val localeManager = getSystemService(LocaleManager::class.java)
        val appLocales = localeManager.applicationLocales
        val locale = if (!appLocales.isEmpty) {
            appLocales[0]
        } else {
            resources.configuration.locales[0]
        }
        return when (locale.language) {
            "en" -> "en"
            else -> "zh"
        }
    }

    private fun updateLanguageButton() {
        val current = if (currentLanguageCode() == "zh") {
            getString(R.string.language_chinese)
        } else {
            getString(R.string.language_english)
        }
        languageButton.text = getString(R.string.language_current, current)
    }

    private fun defaultWhitelistPackages(): Set<String> {
        val result = mutableSetOf<String>()
        packageManager.resolveActivity(
            Intent(Intent.ACTION_DIAL),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName?.let(result::add)
        try {
            Telephony.Sms.getDefaultSmsPackage(this)?.let(result::add)
        } catch (_: Exception) {
            // The device may not expose a default SMS package.
        }
        return result
    }

    private fun showLanguageDialog() {
        val options = arrayOf("中文", "English")
        val selected = if (currentLanguageCode() == "zh") 0 else 1
        AlertDialog.Builder(this)
            .setTitle(R.string.language_select_title)
            .setSingleChoiceItems(options, selected) { dialog, which ->
                val tag = if (which == 0) "zh-CN" else "en"
                getSystemService(LocaleManager::class.java).applicationLocales =
                    LocaleList.forLanguageTags(tag)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startManualClicked() {
        if (!PermissionState.canStart(this)) {
            Toast.makeText(this, R.string.need_permissions, Toast.LENGTH_SHORT).show()
            refreshAll()
            return
        }
        if (prefs.phase != LockPhase.IDLE) {
            Toast.makeText(this, R.string.session_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val minutes = manualMinutesInput.text.toString().toIntOrNull()
        if (minutes == null || minutes !in 1..1440) {
            Toast.makeText(this, R.string.invalid_minutes, Toast.LENGTH_SHORT).show()
            return
        }
        if (!applySettings(showToast = false, reconcile = false)) return
        startLockService(ServiceActions.ACTION_START_MANUAL, minutes)
    }

    private fun applySettings(showToast: Boolean, reconcile: Boolean = true): Boolean {
        if (prefs.phase != LockPhase.IDLE) {
            Toast.makeText(this, R.string.whitelist_frozen_note, Toast.LENGTH_SHORT).show()
            return false
        }
        val preLock = preLockInput.text.toString().toIntOrNull()
        if (preLock == null || preLock !in 0..300) {
            Toast.makeText(this, R.string.invalid_seconds, Toast.LENGTH_SHORT).show()
            return false
        }
        val peek = peekInput.text.toString().toIntOrNull()
        if (peek == null || peek !in 0..300) {
            Toast.makeText(this, R.string.invalid_seconds, Toast.LENGTH_SHORT).show()
            return false
        }
        val limitEnabled = whitelistLimitSwitch.isChecked
        val whitelistSeconds = parseWhitelistDurationSeconds()
        if (whitelistSeconds == null) {
            Toast.makeText(this, R.string.invalid_whitelist_duration, Toast.LENGTH_SHORT).show()
            return false
        }
        prefs.preLockSeconds = preLock
        prefs.graceSeconds = peek
        prefs.whitelistLimitEnabled = limitEnabled
        prefs.whitelistLimitSeconds = whitelistSeconds
        if (reconcile && serviceRelevant()) {
            startLockService(ServiceActions.ACTION_RECONCILE)
        }
        if (showToast) {
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun setupWhitelistDuration() {
        val units = arrayOf(
            getString(R.string.minutes_suffix),
            getString(R.string.seconds_suffix),
        )
        whitelistUnitSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            units,
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setWhitelistDurationFromSeconds(prefs.whitelistLimitSeconds)
        whitelistUnitSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (suppressWhitelistUnitChange) return
                    val minutes = position == 0
                    if (minutes == whitelistUnitIsMinutes) return
                    // Preserve the duration when the unit changes (e.g. 5 分钟 → 300 秒).
                    val seconds = parseWhitelistDurationSeconds()
                    whitelistUnitIsMinutes = minutes
                    if (seconds != null) showWhitelistDuration(seconds)
                    applyWhitelistDurationInputMode()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun setWhitelistDurationFromSeconds(seconds: Int) {
        val useMinutes = seconds >= 60 && seconds % 60 == 0
        whitelistUnitIsMinutes = useMinutes
        suppressWhitelistUnitChange = true
        whitelistUnitSpinner.setSelection(if (useMinutes) 0 else 1, false)
        suppressWhitelistUnitChange = false
        showWhitelistDuration(seconds)
        applyWhitelistDurationInputMode()
    }

    private fun showWhitelistDuration(seconds: Int) {
        whitelistDurationInput.setText(
            if (whitelistUnitIsMinutes) {
                formatWhitelistMinutes(seconds)
            } else {
                seconds.toString()
            }
        )
    }

    private fun formatWhitelistMinutes(seconds: Int): String {
        if (seconds % 60 == 0) return (seconds / 60).toString()
        return String.format(Locale.ROOT, "%.2f", seconds / 60.0)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun parseWhitelistDurationSeconds(): Int? {
        val raw = whitelistDurationInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val seconds = if (whitelistUnitIsMinutes) {
            val minutes = raw.toDoubleOrNull() ?: return null
            (minutes * 60.0).roundToInt()
        } else {
            raw.toIntOrNull() ?: return null
        }
        return seconds.takeIf { it in 1..AppPrefs.MAX_WHITELIST_LIMIT_SECONDS }
    }

    private fun applyWhitelistDurationInputMode() {
        whitelistDurationInput.inputType = InputType.TYPE_CLASS_NUMBER or
            (if (whitelistUnitIsMinutes) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        whitelistDurationInput.keyListener = DigitsKeyListener.getInstance(
            if (whitelistUnitIsMinutes) "0123456789." else "0123456789"
        )
    }

    private fun serviceRelevant(): Boolean {
        val now = System.currentTimeMillis()
        return prefs.hasEnabledSchedules() ||
            prefs.phase != LockPhase.IDLE ||
            (prefs.committedEndMillis ?: 0L) > now ||
            (prefs.manualEndMillis ?: 0L) > now
    }

    private fun startLockService(action: String, minutes: Int? = null) {
        val intent = Intent(this, LockService::class.java).setAction(action)
        if (minutes != null) intent.putExtra(ServiceActions.EXTRA_MINUTES, minutes)
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start LockService", e)
            Toast.makeText(this, R.string.service_start_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSchedule(id: Long) {
        startActivity(
            Intent(this, ScheduleEditActivity::class.java)
                .putExtra(ScheduleEditActivity.EXTRA_SCHEDULE_ID, id)
        )
    }

    private fun scheduleTitle(schedule: Schedule): String {
        val start = formatMinute(schedule.startMinute)
        val end = formatMinute(schedule.endMinute)
        val timeRange = if (schedule.endMinute < schedule.startMinute) {
            getString(R.string.schedule_time_range_next_day, start, end)
        } else {
            getString(R.string.schedule_time_range, start, end)
        }
        return when (schedule.type) {
            ScheduleType.WEEKLY -> {
                val days = if (schedule.days.size == 7) {
                    getString(R.string.schedule_daily)
                } else {
                    schedule.days.sortedBy { it.value }
                        .joinToString(getString(R.string.day_separator)) { dayName(it) }
                }
                "$days $timeRange"
            }

            ScheduleType.ONCE -> {
                val date = schedule.date?.let { TimeText.date(this, it) }
                    ?: getString(R.string.type_once)
                "$date $timeRange"
            }
        }
    }

    private fun dayName(day: DayOfWeek): String =
        day.getDisplayName(TextStyle.SHORT, resources.configuration.locales[0])

    private fun formatMinute(minute: Int): String =
        "%02d:%02d".format(minute / 60, minute % 60)

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        launchSafely(intent)
    }

    private fun openOverlaySettings() {
        launchSafely(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, R.string.accessibility_settings_hint, Toast.LENGTH_LONG).show()
        launchSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openDeviceAdmin() {
        if (PermissionState.deviceAdminActive(this)) {
            openAppDetails()
            return
        }
        Toast.makeText(this, R.string.device_admin_explanation, Toast.LENGTH_LONG).show()
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, PermissionState.adminComponent(this))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_explanation),
            )
        launchSafely(intent)
    }

    private fun requestBatteryWhitelist() {
        if (PermissionState.batteryOptimizationIgnored(this)) {
            launchSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            return
        }
        launchSafely(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun openSecuritySettings() {
        launchSafely(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }

    private fun openAppDetails() {
        launchSafely(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun launchSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to launch settings", e)
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(this, R.string.open_settings_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return Color.DKGRAY
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "MainActivity"
    }
}
