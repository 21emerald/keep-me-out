package com.me.keepmeout.data

import android.content.Context
import org.json.JSONArray

class AppPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var preLockSeconds: Int
        get() = sp.getInt(KEY_PRE_LOCK, DEFAULT_PRE_LOCK).coerceIn(0, 300)
        set(value) = sp.edit().putInt(KEY_PRE_LOCK, value.coerceIn(0, 300)).commit().let { }

    var graceSeconds: Int
        get() = sp.getInt(KEY_GRACE, DEFAULT_GRACE).coerceIn(0, 300)
        set(value) = sp.edit().putInt(KEY_GRACE, value.coerceIn(0, 300)).commit().let { }

    var whitelistPackages: Set<String>
        get() = parseStringSet(sp.getString(KEY_WHITELIST_PACKAGES, null))
        set(value) {
            sp.edit().putString(KEY_WHITELIST_PACKAGES, stringSetToJson(value)).commit()
        }

    var whitelistInitialized: Boolean
        get() = sp.getBoolean(KEY_WHITELIST_INITIALIZED, false)
        set(value) = sp.edit().putBoolean(KEY_WHITELIST_INITIALIZED, value).commit().let { }

    var whitelistLimitEnabled: Boolean
        get() = sp.getBoolean(KEY_WHITELIST_LIMIT_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_WHITELIST_LIMIT_ENABLED, value).commit().let { }

    var whitelistLimitSeconds: Int
        get() {
            // Older builds stored whole minutes; migrate on read.
            val seconds = if (sp.contains(KEY_WHITELIST_LIMIT_SECONDS)) {
                sp.getInt(KEY_WHITELIST_LIMIT_SECONDS, DEFAULT_WHITELIST_LIMIT_SECONDS)
            } else {
                sp.getInt(KEY_WHITELIST_LIMIT_MINUTES, DEFAULT_WHITELIST_LIMIT_MINUTES) * 60
            }
            return seconds.coerceIn(1, MAX_WHITELIST_LIMIT_SECONDS)
        }
        set(value) = sp.edit()
            .putInt(KEY_WHITELIST_LIMIT_SECONDS, value.coerceIn(1, MAX_WHITELIST_LIMIT_SECONDS))
            .commit().let { }

    var sessionWhitelistPackages: Set<String>
        get() = parseStringSet(sp.getString(KEY_SESSION_WHITELIST, null))
        private set(value) {
            sp.edit().putString(KEY_SESSION_WHITELIST, stringSetToJson(value)).commit()
        }

    var sessionWhitelistRemainingMillis: Long
        get() = if (sp.getBoolean(KEY_SESSION_WHITELIST_VALID, false)) {
            sp.getLong(KEY_SESSION_REMAINING_MILLIS, -2L)
        } else {
            -2L
        }
        private set(value) = sp.edit().putLong(KEY_SESSION_REMAINING_MILLIS, value).commit().let { }

    var sessionWhitelistTotalMode: Boolean
        get() = sp.getBoolean(KEY_SESSION_TOTAL_MODE, true)
        private set(value) = sp.edit().putBoolean(KEY_SESSION_TOTAL_MODE, value).commit().let { }

    var sessionWhitelistDurationMillis: Long
        get() = sp.getLong(KEY_SESSION_DURATION_MILLIS, DEFAULT_WHITELIST_LIMIT_SECONDS * 1000L)
        private set(value) = sp.edit().putLong(KEY_SESSION_DURATION_MILLIS, value).commit().let { }

    fun snapshotWhitelistForSession() {
        val duration = whitelistLimitSeconds * 1000L
        sp.edit()
            .putString(KEY_SESSION_WHITELIST, stringSetToJson(whitelistPackages))
            .putLong(KEY_SESSION_DURATION_MILLIS, duration)
            .putBoolean(KEY_SESSION_TOTAL_MODE, whitelistLimitEnabled)
            .putLong(KEY_SESSION_REMAINING_MILLIS, duration)
            .putBoolean(KEY_SESSION_WHITELIST_VALID, true)
            .commit()
    }

    fun setSessionWhitelistRemaining(remainingMillis: Long) {
        sessionWhitelistRemainingMillis = if (remainingMillis < 0L) -1L else remainingMillis
    }

    fun clearSessionWhitelist() {
        sp.edit()
            .remove(KEY_SESSION_WHITELIST)
            .remove(KEY_SESSION_DURATION_MILLIS)
            .remove(KEY_SESSION_TOTAL_MODE)
            .remove(KEY_SESSION_REMAINING_MILLIS)
            .remove(KEY_SESSION_WHITELIST_VALID)
            .commit()
    }

    fun ensureWhitelistInitialized(defaults: Set<String>) {
        if (whitelistInitialized) return
        if (whitelistPackages.isEmpty()) {
            whitelistPackages = defaults
        }
        whitelistInitialized = true
    }

    var schedules: List<Schedule>
        get() = Schedule.parseList(sp.getString(KEY_SCHEDULES, null))
        set(value) {
            sp.edit().putString(KEY_SCHEDULES, Schedule.toJsonList(value)).commit()
        }

    fun saveSchedules(value: List<Schedule>) {
        schedules = value
    }

    var manualStartMillis: Long?
        get() = nullableLong(KEY_MANUAL_START)
        set(value) = setNullableLong(KEY_MANUAL_START, value, critical = true)

    var manualEndMillis: Long?
        get() = nullableLong(KEY_MANUAL_END)
        set(value) = setNullableLong(KEY_MANUAL_END, value, critical = true)

    var committedEndMillis: Long?
        get() = nullableLong(KEY_COMMITTED_END)
        set(value) = setNullableLong(KEY_COMMITTED_END, value, critical = true)

    var committedGraceSeconds: Int?
        get() = if (sp.contains(KEY_COMMITTED_GRACE)) sp.getInt(KEY_COMMITTED_GRACE, DEFAULT_GRACE) else null
        set(value) {
            val editor = sp.edit()
            if (value == null) editor.remove(KEY_COMMITTED_GRACE) else editor.putInt(KEY_COMMITTED_GRACE, value)
            editor.commit()
        }

    fun clearManual() {
        manualStartMillis = null
        manualEndMillis = null
    }

    fun commitSession(endMillis: Long, grace: Int) {
        sp.edit()
            .putLong(KEY_COMMITTED_END, endMillis)
            .putInt(KEY_COMMITTED_GRACE, grace.coerceIn(0, 300))
            .commit()
    }

    fun extendCommitted(endMillis: Long) {
        sp.edit().putLong(KEY_COMMITTED_END, endMillis).commit()
    }

    fun clearCommitted() {
        sp.edit().remove(KEY_COMMITTED_END).remove(KEY_COMMITTED_GRACE).commit()
        clearSessionWhitelist()
    }

    fun suppressedStarts(): Set<Long> {
        val raw = sp.getString(KEY_SUPPRESSED, null)
        if (raw.isNullOrBlank()) return emptySet()
        return try {
            val array = JSONArray(raw)
            buildSet {
                for (i in 0 until array.length()) add(array.getLong(i))
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun addSuppressedStarts(values: Collection<Long>) {
        if (values.isEmpty()) return
        val merged = suppressedStarts() + values
        val array = JSONArray()
        merged.forEach { array.put(it) }
        sp.edit().putString(KEY_SUPPRESSED, array.toString()).commit()
    }

    fun pruneSuppressed(nowMillis: Long) {
        val cutoff = nowMillis - 2L * 24 * 60 * 60 * 1000
        val kept = suppressedStarts().filter { it >= cutoff }
        if (kept.size == suppressedStarts().size) return
        val array = JSONArray()
        kept.forEach { array.put(it) }
        sp.edit().putString(KEY_SUPPRESSED, array.toString()).commit()
    }

    fun hasEnabledSchedules(): Boolean = schedules.any { it.enabled }

    var phase: LockPhase
        get() = try {
            LockPhase.valueOf(sp.getString(KEY_PHASE, LockPhase.IDLE.name) ?: LockPhase.IDLE.name)
        } catch (_: Exception) {
            LockPhase.IDLE
        }
        set(value) = sp.edit().putString(KEY_PHASE, value.name).apply()

    var stateLockAtMillis: Long
        get() = sp.getLong(KEY_STATE_LOCK_AT, 0L)
        set(value) = sp.edit().putLong(KEY_STATE_LOCK_AT, value).apply()

    var stateActiveUntilMillis: Long
        get() = sp.getLong(KEY_STATE_ACTIVE_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_STATE_ACTIVE_UNTIL, value).apply()

    var stateGraceEndElapsed: Long
        get() = sp.getLong(KEY_STATE_GRACE_END_ELAPSED, 0L)
        set(value) = sp.edit().putLong(KEY_STATE_GRACE_END_ELAPSED, value).apply()

    var statePeekActive: Boolean
        get() = sp.getBoolean(KEY_STATE_PEEK_ACTIVE, false)
        set(value) = sp.edit().putBoolean(KEY_STATE_PEEK_ACTIVE, value).apply()

    var stateNextStartMillis: Long
        get() = sp.getLong(KEY_STATE_NEXT_START, 0L)
        set(value) = sp.edit().putLong(KEY_STATE_NEXT_START, value).apply()

    var stateError: String
        get() = sp.getString(KEY_STATE_ERROR, "").orEmpty()
        set(value) = sp.edit().putString(KEY_STATE_ERROR, value).apply()

    private fun nullableLong(key: String): Long? =
        if (sp.contains(key)) sp.getLong(key, 0L).takeIf { it > 0L } else null

    private fun parseStringSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return try {
            val array = JSONArray(raw)
            buildSet {
                for (i in 0 until array.length()) {
                    array.optString(i, "").takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun stringSetToJson(values: Set<String>): String {
        val array = JSONArray()
        values.sorted().forEach { array.put(it) }
        return array.toString()
    }

    private fun setNullableLong(key: String, value: Long?, critical: Boolean) {
        val editor = sp.edit()
        if (value == null) editor.remove(key) else editor.putLong(key, value)
        if (critical) editor.commit() else editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "keep_me_out"
        private const val KEY_PRE_LOCK = "pre_lock_seconds"
        private const val KEY_GRACE = "grace_seconds"
        private const val KEY_WHITELIST_PACKAGES = "whitelist_packages_json"
        private const val KEY_WHITELIST_INITIALIZED = "whitelist_initialized"
        private const val KEY_WHITELIST_LIMIT_ENABLED = "whitelist_limit_enabled"
        private const val KEY_WHITELIST_LIMIT_MINUTES = "whitelist_limit_minutes"
        private const val KEY_WHITELIST_LIMIT_SECONDS = "whitelist_limit_seconds"
        private const val KEY_SESSION_WHITELIST = "session_whitelist_json"
        private const val KEY_SESSION_DURATION_MILLIS = "session_whitelist_duration_millis"
        private const val KEY_SESSION_TOTAL_MODE = "session_whitelist_total_mode"
        private const val KEY_SESSION_REMAINING_MILLIS = "session_whitelist_remaining_millis"
        private const val KEY_SESSION_WHITELIST_VALID = "session_whitelist_valid"
        private const val KEY_SCHEDULES = "schedules_json"
        private const val KEY_MANUAL_START = "manual_start_millis"
        private const val KEY_MANUAL_END = "manual_end_millis"
        private const val KEY_COMMITTED_END = "committed_end_millis"
        private const val KEY_COMMITTED_GRACE = "committed_grace_seconds"
        private const val KEY_SUPPRESSED = "suppressed_starts_json"
        private const val KEY_PHASE = "state_phase"
        private const val KEY_STATE_LOCK_AT = "state_lock_at"
        private const val KEY_STATE_ACTIVE_UNTIL = "state_active_until"
        private const val KEY_STATE_GRACE_END_ELAPSED = "state_grace_end_elapsed"
        private const val KEY_STATE_PEEK_ACTIVE = "state_peek_active"
        private const val KEY_STATE_NEXT_START = "state_next_start"
        private const val KEY_STATE_ERROR = "state_error"

        const val DEFAULT_PRE_LOCK = 30
        const val DEFAULT_GRACE = 5
        const val DEFAULT_WHITELIST_LIMIT_MINUTES = 5
        const val DEFAULT_WHITELIST_LIMIT_SECONDS = DEFAULT_WHITELIST_LIMIT_MINUTES * 60
        const val MAX_WHITELIST_LIMIT_SECONDS = 24 * 60 * 60
    }
}
