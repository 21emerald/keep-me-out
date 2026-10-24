package com.me.keepmeout.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.me.keepmeout.MainActivity
import com.me.keepmeout.service.AlarmReceiver
import com.me.keepmeout.service.ServiceActions

class AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun schedule(snapshot: ScheduleCalculator.Snapshot, nowMillis: Long) {
        if (!canScheduleExactAlarms()) {
            Log.w(TAG, "Exact alarm permission is not available")
            return
        }
        setOrCancel(REQUEST_WARNING, snapshot.nextWarningMillis, ServiceActions.ALARM_WARNING, nowMillis)
        setOrCancel(REQUEST_START, snapshot.nextStartMillis, ServiceActions.ALARM_START, nowMillis)
        setOrCancel(REQUEST_END, snapshot.nextEndMillis, ServiceActions.ALARM_END, nowMillis)
    }

    fun scheduleGraceSafety(triggerMillis: Long) {
        if (!canScheduleExactAlarms()) return
        val trigger = triggerMillis.coerceAtLeast(System.currentTimeMillis() + 100L)
        val operation = pendingBroadcast(REQUEST_GRACE, ServiceActions.ALARM_GRACE)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to schedule grace safety alarm", e)
        }
    }

    fun cancelGraceSafety() {
        alarmManager.cancel(pendingBroadcast(REQUEST_GRACE, ServiceActions.ALARM_GRACE))
    }

    fun cancelAll() {
        alarmManager.cancel(pendingBroadcast(REQUEST_WARNING, ServiceActions.ALARM_WARNING))
        alarmManager.cancel(pendingBroadcast(REQUEST_START, ServiceActions.ALARM_START))
        alarmManager.cancel(pendingBroadcast(REQUEST_END, ServiceActions.ALARM_END))
        cancelGraceSafety()
    }

    private fun setOrCancel(requestCode: Int, triggerMillis: Long?, action: String, nowMillis: Long) {
        if (triggerMillis == null) {
            alarmManager.cancel(pendingBroadcast(requestCode, action))
            return
        }
        // A boundary may be only milliseconds away. Fire it shortly in the future instead of
        // dropping it, otherwise a start exactly at the edge could be missed.
        val trigger = triggerMillis.coerceAtLeast(nowMillis + 100L)
        val operation = pendingBroadcast(requestCode, action)
        val showIntent = PendingIntent.getActivity(
            context,
            requestCode + 1000,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showIntent), operation)
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to schedule exact alarm $action", e)
        }
    }

    private fun pendingBroadcast(requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "AlarmScheduler"
        private const val REQUEST_WARNING = 1001
        private const val REQUEST_START = 1002
        private const val REQUEST_END = 1003
        private const val REQUEST_GRACE = 1004
    }
}
