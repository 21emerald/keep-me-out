package com.me.keepmeout.service

import android.app.KeyguardManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.me.keepmeout.R
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
                        overlay.hide()
                    }
                    handler.postDelayed(this, 250L)
                }

                LockPhase.GRACE -> {
                    val now = System.currentTimeMillis()
                    if (activeUntilMillis > 0 && now >= activeUntilMillis) {
                        reconcile()
                        return
                    }
                    val remaining = graceEndElapsed - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) {
                        expireGrace()
                        return
                    }
                    if (isScreenUnlocked()) {
                        showGraceOverlay(remaining)
                    } else {
                        overlay.hide()
                    }
                    handler.postDelayed(this, 250L)
                }

                else -> tickerRunning = false
            }
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
        if (::prefs.isInitialized && ::notifications.isInitialized) {
            render()
        }
    }

    override fun onDestroy() {
        tickerRunning = false
        handler.removeCallbacks(ticker)
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
                // Already unregistered.
            }
            receiverRegistered = false
        }
        overlay.hide()
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
            } else if (snapshotEnd > committed) {
                prefs.extendCommitted(snapshotEnd)
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
                    val remaining = graceEndElapsed - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) {
                        performLock()
                    } else if (!isScreenUnlocked()) {
                        cancelGrace()
                        phase = LockPhase.LOCKED
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
        overlay.hide()
        reconcile()
    }

    /**
     * User explicitly exits the current lock session from the app.
     * Active scheduled occurrences are suppressed for the rest of their window; the task
     * itself is kept and will run again at its next occurrence.
     */
    private fun exitLockSession() {
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
        overlay.hide()
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
        when (phase) {
            LockPhase.GRACE -> Unit
            LockPhase.WARNING -> render()
            LockPhase.LOCKED, LockPhase.IDLE -> {
                val now = System.currentTimeMillis()
                if (isSessionActive(now)) {
                    startGrace()
                } else {
                    reconcile()
                }
            }
        }
    }

    private fun startGrace() {
        val now = System.currentTimeMillis()
        Log.d(TAG, "startGrace: active=${isSessionActive(now)} unlocked=${isScreenUnlocked()}")
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
        } else if (snapshotEnd > committed) {
            prefs.extendCommitted(snapshotEnd)
        }
        activeUntilMillis = prefs.committedEndMillis ?: 0L
        if (activeUntilMillis <= now) {
            reconcile()
            return
        }

        val graceSeconds = prefs.committedGraceSeconds ?: prefs.graceSeconds
        lastError = ""
        if (graceSeconds <= 0) {
            performLock()
            render()
            return
        }

        phase = LockPhase.GRACE
        graceEndElapsed = SystemClock.elapsedRealtime() + graceSeconds * 1000L
        graceEndWall = now + graceSeconds * 1000L
        alarms.scheduleGraceSafety(graceEndWall)
        render()
    }

    private fun expireGrace() {
        if (phase != LockPhase.GRACE) return
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
        overlay.hide()
        if (phase == LockPhase.GRACE) {
            cancelGrace()
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
        overlay.hide()
    }

    private fun render() {
        val now = System.currentTimeMillis()
        when (phase) {
            LockPhase.IDLE -> {
                overlay.hide()
                notifications.showIdle(nextStartMillis.takeIf { it > now })
            }

            LockPhase.WARNING -> {
                if (isScreenUnlocked()) showWarningOverlay() else overlay.hide()
                notifications.showWarning(warningStartMillis)
            }

            LockPhase.LOCKED -> {
                overlay.hide()
                if (activeUntilMillis > now) {
                    notifications.showLocked(activeUntilMillis)
                } else {
                    notifications.showIdle(nextStartMillis.takeIf { it > now })
                }
            }

            LockPhase.GRACE -> {
                val remaining = graceEndElapsed - SystemClock.elapsedRealtime()
                if (isScreenUnlocked() && remaining > 0L) {
                    showGraceOverlay(remaining)
                } else {
                    overlay.hide()
                }
                notifications.showGrace(
                    graceEndWall,
                    prefs.committedGraceSeconds ?: prefs.graceSeconds,
                )
            }
        }

        if (lastError.isNotBlank() && phase == LockPhase.LOCKED) {
            notifications.showError()
        }

        publishState()
        startTickerIfNeeded()
    }

    private fun showWarningOverlay() {
        val seconds = ceil((warningStartMillis - System.currentTimeMillis()).coerceAtLeast(0L) / 1000.0)
            .toInt()
        overlay.show(
            getString(R.string.overlay_warning_title, seconds),
            getString(R.string.overlay_warning_subtitle, TimeText.full(this, warningStartMillis)),
        )
    }

    private fun showGraceOverlay(remainingMillis: Long) {
        val seconds = ceil(remainingMillis.coerceAtLeast(0L) / 1000.0).toInt()
        val sessionRemaining = (activeUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        overlay.show(
            getString(R.string.overlay_grace_title, seconds),
            getString(R.string.overlay_grace_subtitle, TimeText.remaining(sessionRemaining)),
        )
    }

    private fun startTickerIfNeeded() {
        handler.removeCallbacks(ticker)
        tickerRunning = phase == LockPhase.WARNING || phase == LockPhase.GRACE
        if (tickerRunning) handler.post(ticker)
    }

    private fun cancelGrace() {
        handler.removeCallbacks(ticker)
        tickerRunning = false
        if (graceEndElapsed > 0L) alarms.cancelGraceSafety()
        graceEndElapsed = 0L
        graceEndWall = 0L
    }

    private fun publishState() {
        prefs.phase = phase
        prefs.stateLockAtMillis = warningStartMillis
        prefs.stateActiveUntilMillis = activeUntilMillis
        prefs.stateGraceEndElapsed = graceEndElapsed
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
    }
}
