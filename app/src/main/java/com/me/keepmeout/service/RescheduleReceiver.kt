package com.me.keepmeout.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.me.keepmeout.data.AppPrefs

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AppPrefs(context)
        val hasManual = prefs.manualStartMillis != null && (prefs.manualEndMillis ?: 0L) > System.currentTimeMillis()
        val hasCommitted = (prefs.committedEndMillis ?: 0L) > System.currentTimeMillis()
        if (!prefs.hasEnabledSchedules() && !hasManual && !hasCommitted) return
        try {
            context.startForegroundService(
                Intent(context, LockService::class.java).setAction(ServiceActions.ACTION_RECONCILE)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start LockService after ${intent.action}", e)
        }
    }

    companion object {
        private const val TAG = "RescheduleReceiver"
    }
}
