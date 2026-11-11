package com.me.keepmeout.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.me.keepmeout.MainActivity
import com.me.keepmeout.R

/** Alerts the user when uninstall protection is weakened during a lock session. */
object ProtectionNotifier {

    private const val CHANNEL_ID = "protection_alerts"
    private const val ADMIN_DISABLED_ID = 4201
    private const val GUARD_LOST_ID = 4202

    fun notifyAdminDisabled(context: Context) = post(
        context,
        ADMIN_DISABLED_ID,
        context.getString(R.string.protection_admin_disabled_title),
        context.getString(R.string.protection_admin_disabled_text),
    )

    fun notifyGuardLost(context: Context) = post(
        context,
        GUARD_LOST_ID,
        context.getString(R.string.protection_guard_lost_title),
        context.getString(R.string.protection_guard_lost_text),
    )

    private fun post(context: Context, id: Int, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(context.getColor(R.color.danger))
            .build()
        manager.notify(id, notification)
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.protection_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.protection_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}
