package com.me.keepmeout.schedule

import com.me.keepmeout.data.Schedule
import com.me.keepmeout.data.ScheduleType
import java.time.Instant
import java.time.ZoneId

object ScheduleCalculator {

    data class Window(
        val scheduleId: Long,
        val startMillis: Long,
        val endMillis: Long,
    )

    data class Snapshot(
        val active: Boolean,
        val activeUntilMillis: Long?,
        val activeWindowStarts: Set<Long>,
        val warningStartMillis: Long?,
        val warningWindowStarts: Set<Long>,
        val nextWarningMillis: Long?,
        val nextStartMillis: Long?,
        val nextEndMillis: Long?,
        val hasUpcoming: Boolean,
    )

    fun snapshot(
        schedules: List<Schedule>,
        manualStartMillis: Long?,
        manualEndMillis: Long?,
        committedEndMillis: Long?,
        suppressedStarts: Set<Long>,
        preLockSeconds: Int,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Snapshot {
        val preLockMillis = preLockSeconds.coerceIn(0, 300) * 1000L
        val windows = ArrayList<Window>()
        val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

        schedules.filter { it.enabled }.forEach { schedule ->
            when (schedule.type) {
                ScheduleType.WEEKLY -> {
                    var date = nowDate.minusDays(1)
                    val lastDate = nowDate.plusDays(8)
                    while (!date.isAfter(lastDate)) {
                        if (schedule.days.contains(date.dayOfWeek)) {
                            schedule.windowFor(date, zone)?.let { (start, end) ->
                                addIfEligible(windows, schedule, start, end, nowMillis, suppressedStarts)
                            }
                        }
                        date = date.plusDays(1)
                    }
                }

                ScheduleType.ONCE -> {
                    schedule.date?.let { date ->
                        schedule.windowFor(date, zone)?.let { (start, end) ->
                            addIfEligible(windows, schedule, start, end, nowMillis, suppressedStarts)
                        }
                    }
                }
            }
        }

        if (manualStartMillis != null && manualEndMillis != null &&
            manualStartMillis < manualEndMillis && manualEndMillis > nowMillis
        ) {
            windows += Window(MANUAL_ID, manualStartMillis, manualEndMillis)
        }

        val activeWindows = windows.filter { it.startMillis <= nowMillis && nowMillis < it.endMillis }
        val futureWindows = windows.filter { it.startMillis > nowMillis }

        var activeUntil: Long? = activeWindows.maxOfOrNull { it.endMillis }
        if (committedEndMillis != null && committedEndMillis > nowMillis) {
            activeUntil = if (activeUntil == null) committedEndMillis else maxOf(activeUntil, committedEndMillis)
        }

        val active = activeUntil != null

        var warningStart: Long? = null
        val warningStarts = LinkedHashSet<Long>()
        if (!active) {
            futureWindows
                .filter { it.startMillis - preLockMillis <= nowMillis }
                .sortedBy { it.startMillis }
                .forEach {
                    warningStarts += it.startMillis
                    if (warningStart == null) warningStart = it.startMillis
                }
        }

        val nextWarning = if (!active) {
            futureWindows
                .map { it.startMillis - preLockMillis }
                .filter { it > nowMillis }
                .minOrNull()
        } else {
            null
        }

        val nextStart = futureWindows.minOfOrNull { it.startMillis }
        val nextEnd = activeUntil ?: futureWindows.minOfOrNull { it.endMillis }

        return Snapshot(
            active = active,
            activeUntilMillis = activeUntil,
            activeWindowStarts = activeWindows.mapTo(LinkedHashSet()) { it.startMillis },
            warningStartMillis = warningStart,
            warningWindowStarts = warningStarts,
            nextWarningMillis = nextWarning,
            nextStartMillis = nextStart,
            nextEndMillis = nextEnd,
            hasUpcoming = active || nextStart != null,
        )
    }

    private fun addIfEligible(
        destination: MutableList<Window>,
        schedule: Schedule,
        start: Long,
        end: Long,
        now: Long,
        suppressedStarts: Set<Long>,
    ) {
        if (end <= now) return
        if (start < schedule.enabledAtMillis) return
        if (suppressedStarts.contains(start)) return
        destination += Window(schedule.id, start, end)
    }

    private const val MANUAL_ID = -1L
}
