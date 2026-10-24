package com.me.keepmeout.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            ServiceActions.ALARM_GRACE -> ServiceActions.ACTION_GRACE_EXPIRED
            else -> ServiceActions.ACTION_RECONCILE
        }
        try {
            context.startForegroundService(
                Intent(context, LockService::class.java).setAction(action)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start LockService from alarm", e)
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
    }
}
