package com.me.keepmeout.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.me.keepmeout.MainActivity
import com.me.keepmeout.R
import com.me.keepmeout.service.LockService
import com.me.keepmeout.service.ServiceActions
import com.me.keepmeout.util.TimeText

class NotificationController(private val service: Service) {
    private val manager = service.getSystemService(NotificationManager::class.java)
    private var foregroundStarted = false

    init {
        ensureChannel()
    }

    fun showIdle(nextStartMillis: Long?) {
        val text = if (nextStartMillis != null) {
            service.getString(R.string.notification_idle_text, TimeText.full(service, nextStartMillis))
        } else {
            service.getString(R.string.notification_no_tasks)
        }
        post(baseBuilder(service.getString(R.string.notification_idle_title), text).build())
    }

    fun showWarning(startMillis: Long) {
        val builder = baseBuilder(
            service.getString(R.string.notification_warning_title),
            service.getString(R.string.notification_warning_text, TimeText.full(service, startMillis)),
        )
        builder.setShowWhen(true)
            .setWhen(startMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(service, R.drawable.ic_cancel),
                    service.getString(R.string.notification_cancel),
                    cancelPendingIntent(),
                ).build()
            )
        post(builder.build())
    }

    fun showLocked(activeUntilMillis: Long) {
        val builder = baseBuilder(
            service.getString(R.string.notification_locked_title),
            service.getString(R.string.notification_locked_text, TimeText.full(service, activeUntilMillis)),
        )
        builder.setShowWhen(true)
            .setWhen(activeUntilMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
        post(builder.build())
    }

    fun showGrace(graceEndWallMillis: Long, graceSeconds: Int) {
        val builder = baseBuilder(
            service.getString(R.string.notification_grace_title),
            service.getString(R.string.notification_grace_text, graceSeconds),
        )
        builder.setShowWhen(true)
            .setWhen(graceEndWallMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
        post(builder.build())
    }

    fun showError() {
        val builder = baseBuilder(
            service.getString(R.string.notification_error_title),
            service.getString(R.string.notification_error_text),
        )
        post(builder.build())
    }

    fun stopForeground() {
        if (foregroundStarted) {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    fun isForeground(): Boolean = foregroundStarted

    private fun post(notification: Notification) {
        // Recreate/update the channel so its name/description follows a language change.
        ensureChannel()
        if (!foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                service.startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } else {
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun baseBuilder(title: String, text: String): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(service, CHANNEL_ID)
        } else {
            Notification.Builder(service)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setColor(service.getColor(R.color.accent))
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(service, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            service,
            3001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(service, LockService::class.java).setAction(ServiceActions.ACTION_CANCEL_WARNING)
        return PendingIntent.getForegroundService(
            service,
            3002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = service.getString(R.string.notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "lock_status"
        const val NOTIFICATION_ID = 4101
    }
}
