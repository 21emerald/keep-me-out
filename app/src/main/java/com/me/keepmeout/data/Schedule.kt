package com.me.keepmeout.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class ScheduleType {
    WEEKLY,
    ONCE,
}

data class Schedule(
    val id: Long,
    val type: ScheduleType,
    val days: Set<DayOfWeek>,
    val date: LocalDate?,
    val startMinute: Int,
    val endMinute: Int,
    val enabled: Boolean,
    val enabledAtMillis: Long,
) {
    val startTime: LocalTime
        get() = LocalTime.of(startMinute / 60, startMinute % 60)

    val endTime: LocalTime
        get() = LocalTime.of(endMinute / 60, endMinute % 60)

    /**
     * Returns the start/end epoch millis for an occurrence beginning on [date].
     * An end time earlier than the start time belongs to the following day.
     */
    fun windowFor(date: LocalDate, zone: ZoneId): Pair<Long, Long>? {
        if (startMinute !in 0..1439 || endMinute !in 0..1439 || startMinute == endMinute) return null
        val endDate = if (endMinute < startMinute) date.plusDays(1) else date
        val start = ZonedDateTime.of(date, startTime, zone).toInstant().toEpochMilli()
        val end = ZonedDateTime.of(endDate, endTime, zone).toInstant().toEpochMilli()
        if (end <= start) return null
        return start to end
    }

    fun onceWindow(zone: ZoneId): Pair<Long, Long>? {
        if (type != ScheduleType.ONCE) return null
        return date?.let { windowFor(it, zone) }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("startMinute", startMinute)
        put("endMinute", endMinute)
        put("enabled", enabled)
        put("enabledAtMillis", enabledAtMillis)
        put("days", JSONArray(days.map { it.value }.sorted()))
        put("date", date?.toString() ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): Schedule? {
            return try {
                val id = json.getLong("id")
                val type = ScheduleType.valueOf(json.getString("type"))
                val startMinute = json.getInt("startMinute")
                val endMinute = json.getInt("endMinute")
                if (startMinute !in 0..1439 || endMinute !in 0..1439 || startMinute == endMinute) return null

                val daysArray = json.optJSONArray("days") ?: JSONArray()
                val days = buildSet {
                    for (i in 0 until daysArray.length()) {
                        daysArray.optInt(i, 0).takeIf { it in 1..7 }?.let {
                            add(DayOfWeek.of(it))
                        }
                    }
                }
                val date = if (json.isNull("date")) {
                    null
                } else {
                    json.optString("date").takeIf { it.isNotBlank() }?.let(LocalDate::parse)
                }
                when (type) {
                    ScheduleType.WEEKLY -> if (days.isEmpty() || date != null) return null
                    ScheduleType.ONCE -> if (date == null) return null
                }
                Schedule(
                    id = id,
                    type = type,
                    days = days,
                    date = date,
                    startMinute = startMinute,
                    endMinute = endMinute,
                    enabled = json.optBoolean("enabled", true),
                    enabledAtMillis = json.optLong("enabledAtMillis", 0L),
                )
            } catch (_: Exception) {
                null
            }
        }

        fun parseList(raw: String?): List<Schedule> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                buildList {
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.let(::fromJson)?.let(::add)
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun toJsonList(schedules: List<Schedule>): String {
            val array = JSONArray()
            schedules.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        /**
         * A one-off task has only one occurrence. If that occurrence is canceled, the task
         * will never run again and should be removed rather than left until its old end time.
         */
        fun removeOneOffOccurrences(
            schedules: List<Schedule>,
            occurrenceStarts: Set<Long>,
            zone: ZoneId,
        ): List<Schedule> {
            if (occurrenceStarts.isEmpty()) return schedules
            return schedules.filterNot { schedule ->
                if (schedule.type != ScheduleType.ONCE) return@filterNot false
                val start = schedule.onceWindow(zone)?.first ?: return@filterNot true
                occurrenceStarts.contains(start)
            }
        }
    }
}
