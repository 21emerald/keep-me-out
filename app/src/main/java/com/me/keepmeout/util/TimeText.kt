package com.me.keepmeout.util

import android.content.Context
import com.me.keepmeout.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeText {
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun clock(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clockFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun full(
        context: Context,
        epochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val value = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = LocalDate.now(zone)
        return when (value.toLocalDate()) {
            today -> clockFormatter.format(value)
            today.plusDays(1) -> context.getString(R.string.tomorrow, clockFormatter.format(value))
            today.minusDays(1) -> context.getString(R.string.yesterday, clockFormatter.format(value))
            else -> "${date(context, value.toLocalDate())} ${clockFormatter.format(value)}"
        }
    }

    fun date(context: Context, date: LocalDate): String =
        formatter(context, R.string.date_month_day_pattern).format(date)

    fun fullDate(context: Context, date: LocalDate): String =
        formatter(context, R.string.date_full_pattern).format(date)

    private fun formatter(context: Context, patternRes: Int): DateTimeFormatter {
        val locale = context.resources.configuration.locales[0]
        return DateTimeFormatter.ofPattern(context.getString(patternRes), locale)
    }

    fun remaining(remainingMillis: Long): String {
        val totalSeconds = (remainingMillis / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
