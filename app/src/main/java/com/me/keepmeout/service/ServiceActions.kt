package com.me.keepmeout.service

object ServiceActions {
    const val ACTION_RECONCILE = "com.me.keepmeout.action.RECONCILE"
    const val ACTION_START_MANUAL = "com.me.keepmeout.action.START_MANUAL"
    const val ACTION_CANCEL_WARNING = "com.me.keepmeout.action.CANCEL_WARNING"
    const val ACTION_EXIT_LOCK_SESSION = "com.me.keepmeout.action.EXIT_LOCK_SESSION"
    const val ACTION_GRACE_EXPIRED = "com.me.keepmeout.action.GRACE_EXPIRED"
    const val ACTION_STATE_CHANGED = "com.me.keepmeout.action.STATE_CHANGED"
    const val ACTION_HIDE_WHITELIST_HOME = "com.me.keepmeout.action.HIDE_WHITELIST_HOME"
    const val EXTRA_MINUTES = "com.me.keepmeout.extra.MINUTES"

    const val ALARM_WARNING = "com.me.keepmeout.alarm.WARNING"
    const val ALARM_START = "com.me.keepmeout.alarm.START"
    const val ALARM_END = "com.me.keepmeout.alarm.END"
    const val ALARM_GRACE = "com.me.keepmeout.alarm.GRACE"
}
