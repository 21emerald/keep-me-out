package com.me.keepmeout.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.me.keepmeout.R
import com.me.keepmeout.data.AppPrefs
import com.me.keepmeout.service.LockService
import com.me.keepmeout.ui.ProtectionNotifier

class LockDeviceAdminReceiver : DeviceAdminReceiver() {

    /**
     * Shown by the system before the user can deactivate the admin. Android does not
     * allow preventing the deactivation, only warning about it.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val prefs = AppPrefs(context)
        return context.getString(
            if (prefs.isProtectionSessionActive()) {
                R.string.admin_disable_warning_session
            } else {
                R.string.admin_disable_warning_idle
            }
        )
    }

    override fun onDisabled(context: Context, intent: Intent) {
        val prefs = AppPrefs(context)
        if (!prefs.isProtectionSessionActive()) return
        prefs.lastTamperMillis = System.currentTimeMillis()
        prefs.lastTamperReason = context.getString(R.string.protection_tamper_admin)
        ProtectionNotifier.notifyAdminDisabled(context)
        LockService.instance?.lockFromGuard()
    }
}
