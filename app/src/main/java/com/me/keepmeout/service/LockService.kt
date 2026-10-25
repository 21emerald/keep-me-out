package com.me.keepmeout.service

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.me.keepmeout.BuildConfig
import com.me.keepmeout.R
import com.me.keepmeout.WhitelistHomeActivity
import com.me.keepmeout.data.AppPrefs
import com.me.keepmeout.data.LockPhase
import com.me.keepmeout.data.Schedule
import com.me.keepmeout.data.ScheduleType
import com.me.keepmeout.schedule.AlarmScheduler
import com.me.keepmeout.schedule.ScheduleCalculator
import com.me.keepmeout.ui.NotificationController
import com.me.keepmeout.ui.OverlayController
import com.me.keepmeout.util.PermissionState
import com.me.keepmeout.util.TimeText
import java.time.ZoneId
import kotlin.math.ceil

class LockService : Service() {

    private lateinit var prefs: AppPrefs
    private lateinit var alarms: AlarmScheduler
    private lateinit var overlay: OverlayController
    private lateinit var notifications: NotificationController
    private lateinit var keyguard: KeyguardManager
    private lateinit var power: PowerManager
    private lateinit var devicePolicy: DevicePolicyManager
    private val dialerPackage: String? by lazy {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_DIAL),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
    }
    private val inputMethodPackage: String? by lazy {
        try {
            val flat = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ComponentName.unflattenFromString(flat ?: "")?.packageName
        } catch (_: Exception) {
            null
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false

    private var phase = LockPhase.IDLE
    private var activeUntilMillis = 0L
    private var warningStartMillis = 0L
    private var warningWindowStarts: Set<Long> = emptySet()
    private var nextStartMillis = 0L
    private var graceEndElapsed = 0L
    private var graceEndWall = 0L
    private var lockedAtElapsed = 0L
    private var lastError = ""
    private var lastSnapshot: ScheduleCalculator.Snapshot? = null
    private var tickerRunning = false
    private var lastForegroundPollElapsed = 0L
    private var lastPolledPackage: String? = null
    private var verifyCoveredApp = false
    private var whitelistActive = false
    private var whitelistUnlimited = false
    private var peekActive = false
    private var peekEndElapsed = 0L
    private var peekEndWall = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (!tickerRunning) return
            when (phase) {
                LockPhase.WARNING -> {
                    val now = System.currentTimeMillis()
                    if (now >= warningStartMillis) {
                        reconcile()
                        return
                    }
                    if (isScreenUnlocked()) {
                        showWarningOverlay()
                    } else {
                        hideOverlay()
                    }
                    handler.postDelayed(this, 250L)
                }

                LockPhase.GRACE -> {
                    val now = System.currentTimeMillis()
                    if (activeUntilMillis > 0 && now >= activeUntilMillis) {
                        reconcile()
                        return
                    }
                    maybePollForeground()
                    if (peekActive) {
                        val remaining = peekEndElapsed - SystemClock.elapsedRealtime()
                        if (remaining <= 0L) {
                            expireGrace()
                            return
                        }
                        if (isScreenUnlocked()) {
                            showGraceOverlay(remaining)
                        } else {
                            hideOverlay()
                        }
                        handler.postDelayed(this, 250L)
                        return
                    }
                    if (!whitelistActive || whitelistUnlimited) {
                        handler.postDelayed(this, 1000L)
                        return
                    }
                    val remaining = graceEndElapsed - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) {
                        expireGrace()
                        return
                    }
                    if (isScreenUnlocked()) {
                        showWhitelistOverlay(remaining)
                    } else {
                        hideOverlay()
                    }
                    handler.postDelayed(this, 250L)
                }

                else -> tickerRunning = false
            }
        }
    }

    private val showWhitelistHomeRunnable = Runnable {
        if (phase != LockPhase.GRACE ||
            !peekActive ||
            !isSessionActive(System.currentTimeMillis()) ||
            prefs.sessionWhitelistPackages.isEmpty()
        ) {
            return@Runnable
        }
        // A window-state event may have been missed while the whitelist app was brought
        // back from the background. Never cover it with the page in that case.
        val current = LockAccessibilityService.instance?.currentForegroundPackage()
        if (current != null && prefs.sessionWhitelistPackages.contains(current)) {
            onForegroundPackage(current)
            return@Runnable
        }
        val intent = Intent(this, WhitelistHomeActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        launchWhitelistHome(intent)
        if (verifyCoveredApp) {
            handler.removeCallbacks(verifyWhitelistHomeRunnable)
            handler.postDelayed(verifyWhitelistHomeRunnable, 600L)
        }
    }

    /**
     * If the page raced ahead of a whitelist app that was being brought back from the
     * background, the page can end up on top of it. Its window is still present directly
     * underneath; hand control back to that app instead of leaving the page in front.
     */
    private val verifyWhitelistHomeRunnable = Runnable {
        if (phase != LockPhase.GRACE || !peekActive) return@Runnable
        val windows = LockAccessibilityService.instance?.applicationWindowPackages().orEmpty()
        if (windows.firstOrNull() != packageName) return@Runnable
        val covered = windows.getOrNull(1) ?: return@Runnable
        if (prefs.sessionWhitelistPackages.contains(covered)) {
            Log.d(TAG, "whitelist page covered $covered; yielding")
            onForegroundPackage(covered)
        }
    }

    /**
     * Android 14+ lets the sender explicitly allow a PendingIntent to start an activity
     * from the background. OEM launchers (ColorOS/OriginOS) are stricter than stock
     * Android, so use this path where it is available.
     */
    private fun launchWhitelistHome(intent: Intent) {
        // The accessibility service is bound by the system, which is one of the
        // exemptions from background activity-start restrictions on OEM ROMs.
        val accessibility = LockAccessibilityService.instance
        if (accessibility != null) {
            try {
                accessibility.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Accessibility launch failed", e)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    REQUEST_WHITELIST_HOME,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                pending.send(options.toBundle())
            } else {
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open whitelist home", e)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "screen receiver: ${intent.action}")
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> onUserPresent()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        alarms = AlarmScheduler(this)
        overlay = OverlayController(this)
        notifications = NotificationController(this)
        keyguard = getSystemService(KeyguardManager::class.java)
        power = getSystemService(PowerManager::class.java)
        devicePolicy = getSystemService(DevicePolicyManager::class.java)
        instance = this

        // Start foreground immediately so a startForegroundService() caller never times out.
        notifications.showIdle(null)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        // Exported so system broadcasts (especially USER_PRESENT) reach this dynamic receiver.
        // These are protected system actions; other apps cannot spoof them.
        registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ServiceActions.ACTION_START_MANUAL -> {
                startManual(intent.getIntExtra(ServiceActions.EXTRA_MINUTES, 0))
            }

            ServiceActions.ACTION_CANCEL_WARNING -> cancelWarning()

            ServiceActions.ACTION_EXIT_LOCK_SESSION -> exitLockSession()

            ServiceActions.ACTION_GRACE_EXPIRED -> {
                if (phase == LockPhase.GRACE) expireGrace() else reconcile()
            }

            else -> reconcile()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlay.isInitialized) {
            overlay.onConfigurationChanged()
        }
        LockAccessibilityService.instance?.refreshStatusOverlay()
        if (::prefs.isInitialized && ::notifications.isInitialized) {
            render()
        }
    }

    override fun onDestroy() {
        tickerRunning = false
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(showWhitelistHomeRunnable)
        handler.removeCallbacks(verifyWhitelistHomeRunnable)
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
                // Already unregistered.
            }
            receiverRegistered = false
        }
        hideOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun reconcile() {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        val previousSchedules = prefs.schedules
        val suppressed = prefs.suppressedStarts()
        val cleanedSchedules = previousSchedules.filterNot { schedule ->
            if (schedule.type != ScheduleType.ONCE) return@filterNot false
            val window = schedule.onceWindow(zone) ?: return@filterNot true
            window.second <= now || suppressed.contains(window.first)
        }
        val schedules = if (cleanedSchedules.size != previousSchedules.size) {
            prefs.saveSchedules(cleanedSchedules)
            cleanedSchedules
        } else {
            previousSchedules
        }

        prefs.pruneSuppressed(now)
        if ((prefs.manualEndMillis ?: Long.MAX_VALUE) <= now) prefs.clearManual()
        if ((prefs.committedEndMillis ?: Long.MAX_VALUE) <= now) prefs.clearCommitted()

        val snapshot = ScheduleCalculator.snapshot(
            schedules = schedules,
            manualStartMillis = prefs.manualStartMillis,
            manualEndMillis = prefs.manualEndMillis,
            committedEndMillis = prefs.committedEndMillis,
            suppressedStarts = prefs.suppressedStarts(),
            preLockSeconds = prefs.preLockSeconds,
            nowMillis = now,
            zone = zone,
        )
        lastSnapshot = snapshot

        if (snapshot.active) {
            val snapshotEnd = snapshot.activeUntilMillis ?: (now + 1000L)
            val committed = prefs.committedEndMillis
            if (committed == null || committed <= now) {
                prefs.commitSession(maxOf(snapshotEnd, now + 1000L), prefs.graceSeconds)
                prefs.snapshotWhitelistForSession()
            } else if (snapshotEnd > committed) {
                prefs.extendCommitted(snapshotEnd)
            }
            if (prefs.sessionWhitelistRemainingMillis == -2L) {
                prefs.snapshotWhitelistForSession()
            }
            activeUntilMillis = prefs.committedEndMillis ?: snapshotEnd
            warningStartMillis = 0L
            warningWindowStarts = emptySet()

            // If the process was restarted in the middle of an active session, the in-memory
            // phase starts as IDLE. Detect that from the last published phase so we grant a
            // grace countdown instead of immediately re-locking the user's unlock.
            val wasAlreadyActive = prefs.phase == LockPhase.LOCKED || prefs.phase == LockPhase.GRACE

            when (phase) {
                LockPhase.GRACE -> {
                    if (peekActive) {
                        if (!isScreenUnlocked()) {
                            cancelGrace()
                            phase = LockPhase.LOCKED
                        } else if (peekEndElapsed - SystemClock.elapsedRealtime() <= 0L) {
                            expireGrace()
                        }
                    } else if (!whitelistActive) {
                        phase = LockPhase.LOCKED
                        cancelGrace()
                    } else if (!isScreenUnlocked()) {
                        pauseWhitelistWindow()
                        phase = LockPhase.LOCKED
                    } else if (!whitelistUnlimited) {
                        val remaining = graceEndElapsed - SystemClock.elapsedRealtime()
                        if (remaining <= 0L) {
                            expireGrace()
                        }
                    }
                }

                LockPhase.LOCKED -> {
                    // USER_PRESENT normally starts the grace timer, but an Activity resume or
                    // screen-on reconcile can win the race. Never re-lock an unlocked phone
                    // here; give the user the configured grace countdown instead.
                    if (isScreenUnlocked() &&
                        SystemClock.elapsedRealtime() - lockedAtElapsed > 1000L
                    ) {
                        startGrace()
                    }
                }

                LockPhase.WARNING -> {
                    if (isScreenUnlocked()) {
                        performLock()
                    } else {
                        phase = LockPhase.LOCKED
                        cancelGrace()
                    }
                }

                LockPhase.IDLE -> {
                    if (isScreenUnlocked()) {
                        if (wasAlreadyActive) {
                            startGrace()
                        } else {
                            // A brand-new session start (manual pre-lock = 0 or a missed
                            // scheduled start) should lock immediately.
                            performLock()
                        }
                    } else {
                        phase = LockPhase.LOCKED
                        cancelGrace()
                    }
                }
            }
        } else {
            if (phase == LockPhase.GRACE && isScreenUnlocked()) {
                performLock()
            }
            activeUntilMillis = 0L
            if (snapshot.warningStartMillis != null) {
                phase = LockPhase.WARNING
                warningStartMillis = snapshot.warningStartMillis
                warningWindowStarts = snapshot.warningWindowStarts
                cancelGrace()
            } else {
                phase = LockPhase.IDLE
                warningStartMillis = 0L
                warningWindowStarts = emptySet()
                cancelGrace()
            }
        }

        nextStartMillis = snapshot.nextStartMillis ?: 0L
        alarms.schedule(snapshot, now)
        render()
        maybeStop()
    }

    private fun startManual(minutes: Int) {
        if (minutes !in 1..1440) {
            reconcile()
            return
        }
        if (phase != LockPhase.IDLE) {
            lastError = getString(R.string.session_busy)
            render()
            return
        }
        if (!PermissionState.canStart(this)) {
            lastError = getString(R.string.need_permissions)
            reconcile()
            return
        }
        val now = System.currentTimeMillis()
        val preLockMillis = prefs.preLockSeconds * 1000L
        val start = now + preLockMillis
        val end = start + minutes * 60_000L
        prefs.manualStartMillis = start
        prefs.manualEndMillis = end
        prefs.clearCommitted()
        lastError = ""
        reconcile()
    }

    private fun cancelWarning() {
        if (phase != LockPhase.WARNING) {
            reconcile()
            return
        }
        val snapshot = lastSnapshot
        if (snapshot == null) {
            reconcile()
            return
        }
        removeCanceledOneOffs(snapshot.warningWindowStarts)
        prefs.addSuppressedStarts(snapshot.warningWindowStarts)
        if ((prefs.manualStartMillis ?: 0L) > System.currentTimeMillis()) {
            prefs.clearManual()
        }
        phase = LockPhase.IDLE
        warningStartMillis = 0L
        warningWindowStarts = emptySet()
        lastError = ""
        cancelGrace()
        hideOverlay()
        reconcile()
    }

    /**
     * User explicitly exits the current lock session from the app.
     * Active scheduled occurrences are suppressed for the rest of their window; the task
     * itself is kept and will run again at its next occurrence.
     */
    private fun exitLockSession() {
        // The one-tap exit is a development/testing tool only.
        if (!BuildConfig.DEBUG) return
        val now = System.currentTimeMillis()
        val snapshot = ScheduleCalculator.snapshot(
            schedules = prefs.schedules,
            manualStartMillis = prefs.manualStartMillis,
            manualEndMillis = prefs.manualEndMillis,
            committedEndMillis = prefs.committedEndMillis,
            suppressedStarts = prefs.suppressedStarts(),
            preLockSeconds = prefs.preLockSeconds,
            nowMillis = now,
            zone = ZoneId.systemDefault(),
        )
        if (!snapshot.active) {
            reconcile()
            return
        }

        Log.d(TAG, "exit lock session; suppress=${snapshot.activeWindowStarts}")
        removeCanceledOneOffs(snapshot.activeWindowStarts)
        prefs.addSuppressedStarts(snapshot.activeWindowStarts)
        prefs.clearManual()
        prefs.clearCommitted()

        phase = LockPhase.IDLE
        activeUntilMillis = 0L
        warningStartMillis = 0L
        warningWindowStarts = emptySet()
        cancelGrace()
        hideOverlay()
        lastError = ""
        reconcile()
    }

    private fun removeCanceledOneOffs(occurrenceStarts: Set<Long>) {
        if (occurrenceStarts.isEmpty()) return
        val current = prefs.schedules
        val remaining = Schedule.removeOneOffOccurrences(
            current,
            occurrenceStarts,
            ZoneId.systemDefault(),
        )
        if (remaining.size != current.size) {
            prefs.saveSchedules(remaining)
        }
    }

    private fun onUserPresent() {
        Log.d(
            TAG,
            "user present: phase=$phase active=$activeUntilMillis screenUnlocked=${isScreenUnlocked()} " +
                "committed=${prefs.committedEndMillis}",
        )
        val now = System.currentTimeMillis()
        // Per-unlock mode: each unlock grants a fresh whitelist budget. In total mode
        // the budget is shared by the whole lock session and must not be reset here.
        if (isSessionActive(now) && !prefs.sessionWhitelistTotalMode) {
            resetWhitelistForUnlock()
        }
        when (phase) {
            LockPhase.GRACE -> Unit
            LockPhase.WARNING -> render()
            LockPhase.LOCKED, LockPhase.IDLE -> {
                if (isSessionActive(now)) {
                    startGrace()
                } else {
                    reconcile()
                    return
                }
            }
        }
        if (phase == LockPhase.GRACE &&
            peekActive &&
            isSessionActive(System.currentTimeMillis())
        ) {
            showWhitelistHome()
        }
    }

    private fun resetWhitelistForUnlock() {
        if (prefs.sessionWhitelistRemainingMillis == -2L) {
            prefs.snapshotWhitelistForSession()
            return
        }
        prefs.setSessionWhitelistRemaining(prefs.sessionWhitelistDurationMillis)
    }

    /**
     * While the phone is unlocked during a lock session, show only the whitelisted apps.
     * The overlay permission makes this background activity start allowed on Android 10+.
     * The small delay lets the keyguard finish going away before the activity is started.
     */
    private fun showWhitelistHome(verifyCoveredApp: Boolean = false) {
        if (prefs.sessionWhitelistPackages.isEmpty()) return
        this.verifyCoveredApp = verifyCoveredApp
        handler.removeCallbacks(showWhitelistHomeRunnable)
        handler.removeCallbacks(verifyWhitelistHomeRunnable)
        handler.postDelayed(showWhitelistHomeRunnable, 300L)
    }

    private fun startGrace() {
        val now = System.currentTimeMillis()
        Log.d(TAG, "start peek: active=${isSessionActive(now)} unlocked=${isScreenUnlocked()}")
        if (!isSessionActive(now)) {
            reconcile()
            return
        }
        if (!isScreenUnlocked()) {
            reconcile()
            return
        }

        val snapshotEnd = lastSnapshot?.activeUntilMillis ?: 0L
        val committed = prefs.committedEndMillis
        if (committed == null || committed <= now) {
            prefs.commitSession(maxOf(snapshotEnd, now + 1000L), prefs.graceSeconds)
            prefs.snapshotWhitelistForSession()
        } else if (snapshotEnd > committed) {
            prefs.extendCommitted(snapshotEnd)
        }
        if (prefs.sessionWhitelistRemainingMillis == -2L) {
            prefs.snapshotWhitelistForSession()
        }
        activeUntilMillis = prefs.committedEndMillis ?: 0L
        if (activeUntilMillis <= now) {
            reconcile()
            return
        }

        startPeekWindow(now)
    }

    private fun startPeekWindow(now: Long) {
        val peekSeconds = prefs.graceSeconds.coerceIn(0, 300)
        lastError = ""
        cancelGrace()
        phase = LockPhase.GRACE
        peekActive = true
        peekEndElapsed = SystemClock.elapsedRealtime() + peekSeconds * 1000L
        peekEndWall = now + peekSeconds * 1000L
        if (peekSeconds <= 0) {
            val lastPackage = LockAccessibilityService.lastForegroundPackage
            if (lastPackage != null && prefs.sessionWhitelistPackages.contains(lastPackage)) {
                startWhitelistFromPeek()
            } else {
                performLock()
                render()
            }
            return
        }
        alarms.scheduleGraceSafety(peekEndWall)
        render()
    }

    private fun startWhitelistFromPeek() {
        if (phase != LockPhase.GRACE || !peekActive) return
        handler.removeCallbacks(showWhitelistHomeRunnable)
        if (prefs.sessionWhitelistRemainingMillis == -2L) {
            prefs.snapshotWhitelistForSession()
        }
        val whitelistRemaining = prefs.sessionWhitelistRemainingMillis
        cancelGrace()
        lastError = ""
        if (whitelistRemaining <= 0L) {
            performLock()
            render()
            hideWhitelistHome()
            return
        }
        val now = System.currentTimeMillis()
        phase = LockPhase.GRACE
        whitelistActive = true
        whitelistUnlimited = false
        graceEndElapsed = SystemClock.elapsedRealtime() + whitelistRemaining
        graceEndWall = now + whitelistRemaining
        alarms.scheduleGraceSafety(graceEndWall)
        render()
        // Entering a whitelisted app must win over a whitelist page that is about to be
        // shown (or is already showing) because of the exit animation/event delay.
        hideWhitelistHome()
    }

    private fun hideWhitelistHome() {
        handler.removeCallbacks(verifyWhitelistHomeRunnable)
        sendBroadcast(Intent(ServiceActions.ACTION_HIDE_WHITELIST_HOME).setPackage(packageName))
    }

    private fun startPeekAfterWhitelist() {
        if (phase != LockPhase.GRACE || !whitelistActive) return
        prefs.setSessionWhitelistRemaining(
            (graceEndElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        )
        val now = System.currentTimeMillis()
        cancelGrace()
        startPeekWindow(now)
        // Leaving a whitelisted app returns the user to the whitelist page, with the
        // normal grace countdown running underneath it.
        if (peekActive && isScreenUnlocked() && prefs.sessionWhitelistPackages.isNotEmpty()) {
            showWhitelistHome(verifyCoveredApp = true)
        }
    }

    private fun pauseWhitelistWindow() {
        if (!whitelistActive) return
        prefs.setSessionWhitelistRemaining(
            (graceEndElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        )
        cancelGrace()
    }

    private fun expireGrace() {
        if (phase != LockPhase.GRACE) return
        if (peekActive) {
            cancelGrace()
            performLock()
            render()
            return
        }
        if (!whitelistActive) return
        prefs.setSessionWhitelistRemaining(0L)
        cancelGrace()
        val now = System.currentTimeMillis()
        if (!isSessionActive(now)) {
            phase = LockPhase.IDLE
            reconcile()
            return
        }
        if (isScreenUnlocked()) {
            performLock()
        } else {
            phase = LockPhase.LOCKED
        }
        render()
    }

    private fun onScreenOff() {
        hideOverlay()
        if (phase == LockPhase.GRACE) {
            if (whitelistActive) {
                pauseWhitelistWindow()
            } else {
                cancelGrace()
            }
            phase = LockPhase.LOCKED
        }
        render()
    }

    private fun onScreenOn() {
        reconcile()
    }

    private fun performLock() {
        var success = false
        val accessibility = LockAccessibilityService.instance
        if (accessibility != null) {
            try {
                success = accessibility.lockScreen()
            } catch (e: Exception) {
                Log.w(TAG, "Accessibility lock failed", e)
            }
        }
        if (!success && PermissionState.deviceAdminActive(this)) {
            try {
                devicePolicy.lockNow()
                success = true
            } catch (e: Exception) {
                Log.w(TAG, "Device admin lock failed", e)
            }
        }
        phase = LockPhase.LOCKED
        lockedAtElapsed = SystemClock.elapsedRealtime()
        lastError = if (success) "" else getString(R.string.notification_error_text)
        cancelGrace()
        hideOverlay()
    }

    private fun render() {
        val now = System.currentTimeMillis()
        when (phase) {
            LockPhase.IDLE -> {
                hideOverlay()
                notifications.showIdle(nextStartMillis.takeIf { it > now })
            }

            LockPhase.WARNING -> {
                if (isScreenUnlocked()) showWarningOverlay() else hideOverlay()
                notifications.showWarning(warningStartMillis)
            }

            LockPhase.LOCKED -> {
                hideOverlay()
                if (activeUntilMillis > now) {
                    notifications.showLocked(activeUntilMillis)
                } else {
                    notifications.showIdle(nextStartMillis.takeIf { it > now })
                }
            }

            LockPhase.GRACE -> {
                if (peekActive && isScreenUnlocked()) {
                    val remaining = (peekEndElapsed - SystemClock.elapsedRealtime())
                        .coerceAtLeast(0L)
                    showGraceOverlay(remaining)
                    notifications.showGrace(
                        peekEndWall,
                        ceil(remaining / 1000.0).toInt(),
                    )
                } else if (whitelistActive && isScreenUnlocked()) {
                    if (whitelistUnlimited) {
                        showWhitelistUnlimitedOverlay()
                        notifications.showWhitelistUnlimited(activeUntilMillis)
                    } else {
                        val remaining = (graceEndElapsed - SystemClock.elapsedRealtime())
                            .coerceAtLeast(0L)
                        showWhitelistOverlay(remaining)
                        notifications.showWhitelist(
                            graceEndWall,
                            remaining,
                            activeUntilMillis,
                        )
                    }
                } else {
                    hideOverlay()
                    if (activeUntilMillis > now) {
                        notifications.showLocked(activeUntilMillis)
                    } else {
                        notifications.showIdle(nextStartMillis.takeIf { it > now })
                    }
                }
            }
        }

        if (lastError.isNotBlank() && phase == LockPhase.LOCKED) {
            notifications.showError()
        }

        publishState()
        startTickerIfNeeded()
    }

    private fun showOverlay(title: String, subtitle: String) {
        val accessibility = LockAccessibilityService.instance
        if (accessibility != null && accessibility.showStatusOverlay(title, subtitle)) {
            // Do not leave a duplicate application overlay behind.
            overlay.hide()
        } else {
            overlay.show(title, subtitle)
        }
    }

    private fun hideOverlay() {
        LockAccessibilityService.instance?.hideStatusOverlay()
        overlay.hide()
    }

    private fun showWarningOverlay() {
        val seconds = ceil((warningStartMillis - System.currentTimeMillis()).coerceAtLeast(0L) / 1000.0)
            .toInt()
        showOverlay(
            getString(R.string.overlay_warning_title, seconds),
            getString(R.string.overlay_warning_subtitle, TimeText.full(this, warningStartMillis)),
        )
    }

    private fun showGraceOverlay(remainingMillis: Long) {
        val seconds = ceil(remainingMillis.coerceAtLeast(0L) / 1000.0).toInt()
        val sessionRemaining = (activeUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        showOverlay(
            getString(R.string.overlay_grace_title, seconds),
            getString(R.string.overlay_grace_subtitle, TimeText.remaining(sessionRemaining)),
        )
    }

    private fun showWhitelistOverlay(remainingMillis: Long) {
        showOverlay(
            getString(
                R.string.overlay_whitelist_title,
                TimeText.remaining(remainingMillis),
            ),
            getString(
                R.string.overlay_whitelist_subtitle,
                TimeText.full(this, activeUntilMillis),
            ),
        )
    }

    private fun showWhitelistUnlimitedOverlay() {
        showOverlay(
            getString(R.string.overlay_whitelist_unlimited_title),
            getString(
                R.string.overlay_whitelist_subtitle,
                TimeText.full(this, activeUntilMillis),
            ),
        )
    }

    private fun startTickerIfNeeded() {
        handler.removeCallbacks(ticker)
        tickerRunning = phase == LockPhase.WARNING ||
            (phase == LockPhase.GRACE && peekActive) ||
            (phase == LockPhase.GRACE && whitelistActive && !whitelistUnlimited)
        if (tickerRunning) handler.post(ticker)
    }

    private fun cancelGrace() {
        handler.removeCallbacks(ticker)
        tickerRunning = false
        if (graceEndElapsed > 0L || peekEndElapsed > 0L) alarms.cancelGraceSafety()
        graceEndElapsed = 0L
        graceEndWall = 0L
        peekEndElapsed = 0L
        peekEndWall = 0L
        peekActive = false
        whitelistActive = false
        whitelistUnlimited = false
    }

    /**
     * The system only sends window-state events for some transitions. Poll the topmost
     * application window as a fallback so bringing a whitelisted app back from the
     * background still resumes the whitelist budget (and so leaving an app starts the
     * grace page) even if the event was missed.
     */
    private fun maybePollForeground() {
        if (phase != LockPhase.GRACE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastForegroundPollElapsed < 750L) return
        lastForegroundPollElapsed = now
        val packageName = LockAccessibilityService.instance?.currentForegroundPackage()
            ?: return
        if (packageName != lastPolledPackage) {
            lastPolledPackage = packageName
            Log.d(TAG, "foreground poll: $packageName")
        }
        onForegroundPackage(packageName)
    }

    /**
     * Called by the accessibility service when the foreground window changes.
     * During a peek any app can be used. Opening a whitelisted app starts the whitelist
     * budget. Leaving a whitelisted app starts another peek; if no whitelisted app is
     * opened within that peek, the phone locks.
     */
    fun onForegroundPackage(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        if (phase != LockPhase.GRACE) return
        if (peekActive) {
            if (prefs.sessionWhitelistPackages.contains(packageName)) {
                startWhitelistFromPeek()
            }
            return
        }
        if (!whitelistActive) return
        if (prefs.sessionWhitelistPackages.contains(packageName)) return
        if (isTransientSystemPackage(packageName)) return
        startPeekAfterWhitelist()
    }

    private fun isTransientSystemPackage(pkg: String): Boolean {
        if (pkg == "android" || pkg == "com.android.systemui") return true
        if (pkg == dialerPackage || pkg == inputMethodPackage) return true
        return pkg in SYSTEM_ALLOWED_PACKAGES
    }

    private fun publishState() {
        prefs.phase = phase
        prefs.stateLockAtMillis = warningStartMillis
        prefs.stateActiveUntilMillis = activeUntilMillis
        prefs.stateGraceEndElapsed = if (peekActive) peekEndElapsed else graceEndElapsed
        prefs.statePeekActive = peekActive
        prefs.stateNextStartMillis = nextStartMillis
        prefs.stateError = lastError
        sendBroadcast(Intent(ServiceActions.ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun maybeStop() {
        if (phase == LockPhase.IDLE &&
            nextStartMillis == 0L &&
            prefs.manualStartMillis == null &&
            (prefs.committedEndMillis ?: 0L) <= System.currentTimeMillis()
        ) {
            alarms.cancelAll()
            notifications.stopForeground()
            sendBroadcast(Intent(ServiceActions.ACTION_STATE_CHANGED).setPackage(packageName))
            stopSelf()
        }
    }

    private fun isSessionActive(now: Long): Boolean {
        val committed = prefs.committedEndMillis ?: 0L
        if (committed > now) return true
        val snapshot = lastSnapshot ?: return false
        return snapshot.active && (snapshot.activeUntilMillis ?: 0L) > now
    }

    private fun isScreenUnlocked(): Boolean =
        power.isInteractive && !keyguard.isKeyguardLocked

    companion object {
        private const val TAG = "LockService"
        private const val REQUEST_WHITELIST_HOME = 3004

        @Volatile
        var instance: LockService? = null
            private set

        private val SYSTEM_ALLOWED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.telecom",
            "com.android.incallui",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.oplus.dialer",
            "com.coloros.phone",
            "com.oplus.phone",
            "com.coloros.incallui",
            "com.oplus.incallui",
            "com.android.contacts",
            "com.google.android.contacts",
            "com.coloros.contacts",
            "com.oplus.contacts",
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.intentresolver",
        )
    }
}
