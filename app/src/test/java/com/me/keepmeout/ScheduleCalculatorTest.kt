package com.me.keepmeout

import com.me.keepmeout.data.Schedule
import com.me.keepmeout.data.ScheduleType
import com.me.keepmeout.schedule.ScheduleCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleCalculatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 24) // Saturday

    @Test
    fun dailyOvernightWindowBeforeDuringAndAfter() {
        val task = weekly(allDays(), 23 * 60, 7 * 60)

        val before = snapshot(task, at(date, 22, 0))
        assertFalse(before.active)
        assertEquals(at(date, 23, 0), before.nextStartMillis)
        assertEquals(at(date, 22, 59, 30), before.nextWarningMillis)
        assertNull(before.warningStartMillis)

        val warning = snapshot(task, at(date, 22, 59, 40))
        assertFalse(warning.active)
        assertEquals(at(date, 23, 0), warning.warningStartMillis)
        assertEquals(setOf(at(date, 23, 0)), warning.warningWindowStarts)

        val during = snapshot(task, at(date, 23, 10))
        assertTrue(during.active)
        assertEquals(at(date.plusDays(1), 7, 0), during.activeUntilMillis)
        assertEquals(setOf(at(date, 23, 0)), during.activeWindowStarts)

        val earlyMorning = snapshot(task, at(date.plusDays(1), 6, 59))
        assertTrue(earlyMorning.active)
        assertEquals(at(date.plusDays(1), 7, 0), earlyMorning.activeUntilMillis)

        val after = snapshot(task, at(date.plusDays(1), 7, 1))
        assertFalse(after.active)
        assertEquals(at(date.plusDays(1), 23, 0), after.nextStartMillis)
    }

    @Test
    fun selectedWeekdaysOnly() {
        val task = weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), 23 * 60, 7 * 60)
        // 2026-10-26 is Monday.
        val monday = LocalDate.of(2026, 10, 26)

        assertTrue(snapshot(task, at(monday, 23, 10)).active)
        assertFalse(snapshot(task, at(monday.plusDays(1), 23, 10)).active)
        assertEquals(
            at(monday.plusDays(2), 23, 0),
            snapshot(task, at(monday.plusDays(1), 23, 10)).nextStartMillis,
        )
    }

    @Test
    fun onceOvernightAndExpired() {
        val task = once(date, 23 * 60, 7 * 60)
        assertTrue(snapshot(task, at(date, 23, 10)).active)
        assertEquals(
            at(date.plusDays(1), 7, 0),
            snapshot(task, at(date, 23, 10)).activeUntilMillis,
        )
        assertFalse(snapshot(task, at(date.plusDays(1), 7, 1)).active)
        assertNull(snapshot(task, at(date.plusDays(1), 7, 1)).nextStartMillis)
    }

    @Test
    fun overlappingWindowsUseLatestEnd() {
        val first = weekly(allDays(), 22 * 60, 23 * 60).copy(id = 1)
        val second = weekly(allDays(), 22 * 60, 1 * 60).copy(id = 2)
        val snapshot = ScheduleCalculator.snapshot(
            schedules = listOf(first, second),
            manualStartMillis = null,
            manualEndMillis = null,
            committedEndMillis = null,
            suppressedStarts = emptySet(),
            preLockSeconds = 30,
            nowMillis = at(date, 22, 30),
            zone = zone,
        )
        assertTrue(snapshot.active)
        assertEquals(at(date.plusDays(1), 1, 0), snapshot.activeUntilMillis)
        assertEquals(
            setOf(at(date, 22, 0)),
            snapshot.activeWindowStarts,
        )
    }

    @Test
    fun cancelingOneOffOccurrenceRemovesTaskButKeepsWeeklyTask() {
        val oneOff = once(date, 23 * 60, 7 * 60).copy(id = 10L)
        val weeklyTask = weekly(allDays(), 23 * 60, 7 * 60).copy(id = 20L)
        val remaining = Schedule.removeOneOffOccurrences(
            schedules = listOf(oneOff, weeklyTask),
            occurrenceStarts = setOf(at(date, 23, 0)),
            zone = zone,
        )
        assertEquals(listOf(weeklyTask), remaining)
    }

    @Test
    fun suppressingActiveWindowExitsSessionButKeepsFutureOccurrence() {
        val task = weekly(allDays(), 23 * 60, 7 * 60)
        val now = at(date, 23, 10)
        val active = snapshot(task, now)
        assertTrue(active.active)
        assertEquals(setOf(at(date, 23, 0)), active.activeWindowStarts)

        val afterExit = ScheduleCalculator.snapshot(
            schedules = listOf(task),
            manualStartMillis = null,
            manualEndMillis = null,
            committedEndMillis = null,
            suppressedStarts = active.activeWindowStarts,
            preLockSeconds = 30,
            nowMillis = now,
            zone = zone,
        )
        assertFalse(afterExit.active)
        assertEquals(at(date.plusDays(1), 23, 0), afterExit.nextStartMillis)
    }

    @Test
    fun warningBoundaryAndZeroPreLock() {
        val task = weekly(allDays(), 23 * 60, 7 * 60)
        val justBefore = snapshot(task, at(date, 22, 59, 29))
        assertEquals(at(date, 22, 59, 30), justBefore.nextWarningMillis)
        assertNull(justBefore.warningStartMillis)

        val atBoundary = snapshot(task, at(date, 22, 59, 30))
        assertEquals(at(date, 23, 0), atBoundary.warningStartMillis)

        val zero = ScheduleCalculator.snapshot(
            schedules = listOf(task),
            manualStartMillis = null,
            manualEndMillis = null,
            committedEndMillis = null,
            suppressedStarts = emptySet(),
            preLockSeconds = 0,
            nowMillis = at(date, 22, 59, 40),
            zone = zone,
        )
        assertNull(zero.warningStartMillis)
        assertEquals(at(date, 23, 0), zero.nextStartMillis)
    }

    @Test
    fun suppressedOccurrenceIsSkippedButNextDayStillWorks() {
        val task = weekly(allDays(), 23 * 60, 7 * 60)
        val suppressed = at(date, 23, 0)

        val warning = ScheduleCalculator.snapshot(
            schedules = listOf(task),
            manualStartMillis = null,
            manualEndMillis = null,
            committedEndMillis = null,
            suppressedStarts = setOf(suppressed),
            preLockSeconds = 30,
            nowMillis = at(date, 22, 59, 40),
            zone = zone,
        )
        assertNull(warning.warningStartMillis)
        assertEquals(at(date.plusDays(1), 23, 0), warning.nextStartMillis)

        val during = ScheduleCalculator.snapshot(
            schedules = listOf(task),
            manualStartMillis = null,
            manualEndMillis = null,
            committedEndMillis = null,
            suppressedStarts = setOf(suppressed),
            preLockSeconds = 30,
            nowMillis = at(date, 23, 10),
            zone = zone,
        )
        assertFalse(during.active)

        val tomorrowWarning = ScheduleCalculator.snapshot(
            schedules = listOf(task),
            manualStartMillis = null,
            manualEndMillis = null,
            committedEndMillis = null,
            suppressedStarts = setOf(suppressed),
            preLockSeconds = 30,
            nowMillis = at(date.plusDays(1), 22, 59, 40),
            zone = zone,
        )
        assertEquals(at(date.plusDays(1), 23, 0), tomorrowWarning.warningStartMillis)
    }

    @Test
    fun newlyEnabledTaskDoesNotStartMidWindow() {
        val late = at(date, 23, 30)
        val task = weekly(allDays(), 23 * 60, 7 * 60).copy(enabledAtMillis = late)

        val snapshot = snapshot(task, late)
        assertFalse(snapshot.active)
        assertEquals(at(date.plusDays(1), 23, 0), snapshot.nextStartMillis)

        val enabledBeforeWindow = task.copy(enabledAtMillis = at(date, 22, 0))
        assertTrue(snapshot(enabledBeforeWindow, late).active)
    }

    @Test
    fun committedSessionSurvivesTaskChanges() {
        val now = at(date, 23, 30)
        val committedEnd = at(date.plusDays(1), 7, 0)
        val snapshot = ScheduleCalculator.snapshot(
            schedules = emptyList(),
            manualStartMillis = null,
            manualEndMillis = null,
            committedEndMillis = committedEnd,
            suppressedStarts = emptySet(),
            preLockSeconds = 30,
            nowMillis = now,
            zone = zone,
        )
        assertTrue(snapshot.active)
        assertEquals(committedEnd, snapshot.activeUntilMillis)
        assertEquals(committedEnd, snapshot.nextEndMillis)
        assertNull(snapshot.nextStartMillis)
    }

    @Test
    fun manualWindowWarnsThenBecomesActive() {
        val warningAt = at(date, 22, 0)
        val start = at(date, 22, 0, 30)
        val end = start + 60_000L
        val snapshot = ScheduleCalculator.snapshot(
            schedules = emptyList(),
            manualStartMillis = start,
            manualEndMillis = end,
            committedEndMillis = null,
            suppressedStarts = emptySet(),
            preLockSeconds = 30,
            nowMillis = warningAt,
            zone = zone,
        )
        assertFalse(snapshot.active)
        assertEquals(start, snapshot.warningStartMillis)

        val active = ScheduleCalculator.snapshot(
            schedules = emptyList(),
            manualStartMillis = start,
            manualEndMillis = end,
            committedEndMillis = null,
            suppressedStarts = emptySet(),
            preLockSeconds = 30,
            nowMillis = start,
            zone = zone,
        )
        assertTrue(active.active)
        assertEquals(end, active.activeUntilMillis)
    }

    private fun snapshot(task: Schedule, now: Long): ScheduleCalculator.Snapshot =
        ScheduleCalculator.snapshot(
            schedules = listOf(task),
            manualStartMillis = null,
            manualEndMillis = null,
            committedEndMillis = null,
            suppressedStarts = emptySet(),
            preLockSeconds = 30,
            nowMillis = now,
            zone = zone,
        )

    private fun weekly(
        days: Set<DayOfWeek>,
        startMinute: Int,
        endMinute: Int,
    ): Schedule = Schedule(
        id = 1L,
        type = ScheduleType.WEEKLY,
        days = days,
        date = null,
        startMinute = startMinute,
        endMinute = endMinute,
        enabled = true,
        enabledAtMillis = 0L,
    )

    private fun once(date: LocalDate, startMinute: Int, endMinute: Int): Schedule = Schedule(
        id = 1L,
        type = ScheduleType.ONCE,
        days = emptySet(),
        date = date,
        startMinute = startMinute,
        endMinute = endMinute,
        enabled = true,
        enabledAtMillis = 0L,
    )

    private fun allDays(): Set<DayOfWeek> = DayOfWeek.values().toSet()

    private fun at(date: LocalDate, hour: Int, minute: Int, second: Int = 0): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, minute, second), zone)
            .toInstant()
            .toEpochMilli()
}
