package com.me.keepmeout

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import com.me.keepmeout.data.AppPrefs
import com.me.keepmeout.data.Schedule
import com.me.keepmeout.data.ScheduleType
import com.me.keepmeout.service.LockService
import com.me.keepmeout.service.ServiceActions
import com.me.keepmeout.util.PermissionState
import com.me.keepmeout.util.TimeText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle

class ScheduleEditActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private var existing: Schedule? = null
    private var selectedDate: LocalDate = LocalDate.now()
    private var startMinute: Int = 23 * 60
    private var endMinute: Int = 7 * 60

    private lateinit var typeGroup: RadioGroup
    private lateinit var weeklyGroup: View
    private lateinit var onceGroup: View
    private lateinit var dayContainer: LinearLayout
    private lateinit var dateButton: Button
    private lateinit var startTimeButton: Button
    private lateinit var endTimeButton: Button
    private lateinit var deleteButton: Button

    private val dayButtons = LinkedHashMap<DayOfWeek, ToggleButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        setContentView(R.layout.activity_schedule_edit)

        typeGroup = findViewById(R.id.typeGroup)
        weeklyGroup = findViewById(R.id.weeklyGroup)
        onceGroup = findViewById(R.id.onceGroup)
        dayContainer = findViewById(R.id.dayContainer)
        dateButton = findViewById(R.id.dateButton)
        startTimeButton = findViewById(R.id.startTimeButton)
        endTimeButton = findViewById(R.id.endTimeButton)
        deleteButton = findViewById(R.id.deleteScheduleButton)

        val id = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L)
        existing = if (id >= 0L) prefs.schedules.firstOrNull { it.id == id } else null
        if (id >= 0L && existing == null) {
            finish()
            return
        }

        val schedule = existing
        findViewById<TextView>(R.id.editTitle).setText(
            if (schedule == null) R.string.edit_title_new else R.string.edit_title_existing
        )

        if (schedule != null) {
            selectedDate = schedule.date ?: LocalDate.now()
            startMinute = schedule.startMinute
            endMinute = schedule.endMinute
        } else {
            // New tasks start near "now" so the time picker is easy to adjust.
            val defaultStart = ZonedDateTime.now().plusMinutes(1)
            selectedDate = defaultStart.toLocalDate()
            startMinute = defaultStart.hour * 60 + defaultStart.minute
            endMinute = (startMinute + 60) % (24 * 60)
        }

        buildDayButtons(schedule)

        if (schedule?.type == ScheduleType.ONCE) {
            typeGroup.check(R.id.typeOnce)
        } else {
            typeGroup.check(R.id.typeWeekly)
        }
        updateTypeVisibility()
        updateDateTimeLabels()

        typeGroup.setOnCheckedChangeListener { _, _ -> updateTypeVisibility() }
        dateButton.setOnClickListener { pickDate() }
        startTimeButton.setOnClickListener {
            pickTime(startMinute) {
                startMinute = it
                updateDateTimeLabels()
            }
        }
        endTimeButton.setOnClickListener {
            pickTime(endMinute) {
                endMinute = it
                updateDateTimeLabels()
            }
        }
        findViewById<Button>(R.id.saveScheduleButton).setOnClickListener { save() }

        deleteButton.visibility = if (schedule == null) View.GONE else View.VISIBLE
        deleteButton.setOnClickListener { confirmDelete() }
    }

    override fun onResume() {
        super.onResume()
        updateDateButton()
    }

    private fun buildDayButtons(schedule: Schedule?) {
        dayContainer.removeAllViews()
        dayButtons.clear()
        val checked = when {
            schedule?.type == ScheduleType.WEEKLY -> schedule.days
            schedule == null -> DayOfWeek.values().toSet()
            else -> DayOfWeek.values().toSet()
        }
        DayOfWeek.values().forEachIndexed { index, day ->
            val button = ToggleButton(this).apply {
                val label = day.getDisplayName(
                    TextStyle.NARROW,
                    resources.configuration.locales[0],
                )
                textOn = label
                textOff = label
                text = label
                isAllCaps = false
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(0, dp(8), 0, dp(8))
                gravity = Gravity.CENTER
                backgroundTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(),
                    ),
                    intArrayOf(
                        getColor(R.color.accent),
                        getColor(R.color.card_bg_alt),
                    ),
                )
                setTextColor(
                    ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf(),
                        ),
                        intArrayOf(Color.WHITE, themeColor(android.R.attr.textColorPrimary)),
                    )
                )
                isChecked = checked.contains(day)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    marginStart = if (index == 0) 0 else dp(4)
                }
            }
            dayContainer.addView(button)
            dayButtons[day] = button
        }
    }

    private fun updateTypeVisibility() {
        val weekly = typeGroup.checkedRadioButtonId == R.id.typeWeekly
        weeklyGroup.visibility = if (weekly) View.VISIBLE else View.GONE
        onceGroup.visibility = if (weekly) View.GONE else View.VISIBLE
    }

    private fun updateDateTimeLabels() {
        updateDateButton()
        startTimeButton.text = formatMinute(startMinute)
        endTimeButton.text = formatMinute(endMinute)
    }

    private fun updateDateButton() {
        dateButton.text = TimeText.fullDate(this, selectedDate)
    }

    private fun pickDate() {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                selectedDate = LocalDate.of(year, month + 1, day)
                updateDateButton()
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth,
        ).show()
    }

    private fun pickTime(initialMinute: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            initialMinute / 60,
            initialMinute % 60,
            true,
        ).show()
    }

    private fun save() {
        val type = if (typeGroup.checkedRadioButtonId == R.id.typeOnce) {
            ScheduleType.ONCE
        } else {
            ScheduleType.WEEKLY
        }

        if (startMinute == endMinute) {
            Toast.makeText(this, R.string.invalid_time_range, Toast.LENGTH_SHORT).show()
            return
        }

        val days = if (type == ScheduleType.WEEKLY) {
            dayButtons.filterValues { it.isChecked }.keys
        } else {
            emptySet()
        }
        if (type == ScheduleType.WEEKLY && days.isEmpty()) {
            Toast.makeText(this, R.string.need_one_day, Toast.LENGTH_SHORT).show()
            return
        }

        val old = existing
        val enabled = old?.enabled ?: PermissionState.canStart(this)
        val schedule = Schedule(
            id = old?.id ?: System.currentTimeMillis(),
            type = type,
            days = days,
            date = if (type == ScheduleType.ONCE) selectedDate else null,
            startMinute = startMinute,
            endMinute = endMinute,
            enabled = enabled,
            enabledAtMillis = System.currentTimeMillis(),
        )

        if (type == ScheduleType.ONCE) {
            val start = schedule.onceWindow(ZoneId.systemDefault())?.first
            if (start == null || start <= System.currentTimeMillis()) {
                Toast.makeText(this, R.string.once_in_past, Toast.LENGTH_SHORT).show()
                return
            }
        }

        val current = prefs.schedules.toMutableList()
        val index = current.indexOfFirst { it.id == schedule.id }
        if (index >= 0) {
            current[index] = schedule
        } else {
            current.add(schedule)
        }
        prefs.saveSchedules(current)

        Toast.makeText(
            this,
            if (enabled) R.string.task_saved else R.string.task_saved_disabled,
            Toast.LENGTH_SHORT,
        ).show()
        notifyService()
        finish()
    }

    private fun confirmDelete() {
        val schedule = existing ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_schedule_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val current = prefs.schedules.filterNot { it.id == schedule.id }
                prefs.saveSchedules(current)
                Toast.makeText(this, R.string.task_deleted, Toast.LENGTH_SHORT).show()
                notifyService()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun notifyService() {
        try {
            startForegroundService(
                Intent(this, LockService::class.java)
                    .setAction(ServiceActions.ACTION_RECONCILE)
            )
        } catch (_: Exception) {
            // MainActivity will retry from onResume if the service is still relevant.
        }
    }

    private fun formatMinute(minute: Int): String =
        "%02d:%02d".format(minute / 60, minute % 60)

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return Color.DKGRAY
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_SCHEDULE_ID = "com.me.keepmeout.extra.SCHEDULE_ID"
    }
}
