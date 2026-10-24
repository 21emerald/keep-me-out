package com.me.keepmeout.util

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.me.keepmeout.R
import com.me.keepmeout.admin.LockDeviceAdminReceiver
import com.me.keepmeout.service.LockAccessibilityService

object PermissionState {

    fun notificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled()
    }

    fun overlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun exactAlarmsGranted(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        return manager.canScheduleExactAlarms()
    }

    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!enabled) return false
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val expected = ComponentName(context, LockAccessibilityService::class.java)
        return flat.split(':').any { value ->
            ComponentName.unflattenFromString(value) == expected
        }
    }

    fun deviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm.isAdminActive(adminComponent(context))
    }

    fun secureLockScreen(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return keyguard.isDeviceSecure
    }

    fun batteryOptimizationIgnored(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasLockMethod(context: Context): Boolean =
        accessibilityEnabled(context) || deviceAdminActive(context)

    fun missingRequired(context: Context): List<String> = buildList {
        if (!secureLockScreen(context)) add(context.getString(R.string.permission_secure))
        if (!hasLockMethod(context)) {
            add("${context.getString(R.string.permission_accessibility)} / ${context.getString(R.string.permission_device_admin)}")
        }
        if (!overlayGranted(context)) add(context.getString(R.string.permission_overlay))
        if (!notificationsGranted(context)) add(context.getString(R.string.permission_notification))
        if (!exactAlarmsGranted(context)) add(context.getString(R.string.permission_exact_alarm))
    }

    fun canStart(context: Context): Boolean = missingRequired(context).isEmpty()

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, LockDeviceAdminReceiver::class.java)
}
