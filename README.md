# Keep Me Out

**English** | [中文](README.zh-CN.md)

Keep Me Out is a small, personal Android app that helps you use your phone less by enforcing timed lock periods. It uses the real system lock screen, exact alarms, and a small non-blocking countdown overlay.

| Main screen | Schedule editor |
| --- | --- |
| <img src="docs/screenshots/main-en.png" width="280" alt="Keep Me Out main screen" /> | <img src="docs/screenshots/editor-en.png" width="280" alt="Schedule editor" /> |

## Features

- **Lock now** — choose a duration in minutes, show a configurable pre-lock countdown, then lock the phone for the full duration. The pre-lock countdown is not counted as lock time.
- **Scheduled locks** — weekly schedules (select Monday–Sunday; all seven means every day) or one-time schedules with a date and start/end time. If the end time is earlier than the start time, the window ends the next day. Multiple schedules can overlap.
- **Warning before scheduled locks** — a floating countdown starts `preLockSeconds` before the configured start, and the phone locks at the start time.
- **Grace period** — every unlock during an active lock gives `graceSeconds` of use, then the phone locks again. This repeats until the session ends.
- **Floating countdown** — a small pill at the top of the screen shows the remaining seconds. It does not intercept touches.
- **Persistent notification** — shows the next lock, current countdown, or current session status.
- **Cancel a warning** — before the lock starts, cancel that occurrence from the app or the notification.
- **Exit this lock** — while locked, unlock and open the app, then tap **Exit this lock** to stop re-locking for the current occurrence. The schedule itself is kept.
- **One-time cleanup** — a one-time schedule is removed automatically after it finishes, or immediately when its only occurrence is cancelled or exited.
- **Language** — switch between Chinese and English in the app. The UI, date/day formats, notifications and overlay follow the choice and it is remembered.
- **No network access** — no INTERNET permission and no analytics.

## Requirements

- Android 13 (API 33) or newer.
- A secure lock screen (PIN, pattern, or password). Swipe-only is not enough.
- Special access/permissions:
  - Notifications
  - Display over other apps
  - Accessibility service (recommended; keeps fingerprint unlock)
  - Device admin (optional fallback; may require PIN/pattern/password)
  - Battery optimization whitelist (recommended)
- Exact alarms: the app declares `USE_EXACT_ALARM`, intended for personal sideloading. It is granted at install and is not user-revocable.

## Build

Requirements: JDK 17 and Android SDK 35.

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The package name is `com.me.keepmeout`.

## First-time setup

Open the app and complete the rows in **Setup**:

1. **Notifications** — allow notifications.
2. **Display over other apps** — required for the floating countdown.
3. **Secure lock screen** — set a PIN, pattern, or password.
4. **Accessibility** — enable **Keep Me Out Screen Lock**. On Android 13+, if restricted settings appears, allow restricted settings from the app info menu first.
5. **Device admin** (optional fallback) — note that it can require a PIN/pattern/password on every unlock.
6. **Battery optimization** — add the app to the whitelist. On Xiaomi/Redmi, OPPO/OnePlus, vivo and similar devices, also allow autostart or background activity.
7. **Exact alarms** — normally available automatically; the app checks and reports any problem.

For the first test, use short values: pre-lock **10 s**, grace **5 s**, and lock now **1 minute**.

## Usage

### Lock now

1. Enter the lock duration in minutes (for example, 29).
2. Optionally adjust **Countdown before locking** and **Allowed use after unlock**.
3. Tap **Start**.
4. The floating countdown appears. At zero, the real system lock screen is shown.
5. Each unlock grants the grace period, then the phone locks again.
6. During the grace period, open the app and tap **Exit this lock** if you need to end this occurrence.

### Scheduled locks

- Tap **Add schedule**.
- Choose **Weekly** and select days, or **One-time** and pick a date plus start/end times.
- If the end time is earlier than the start time, the window ends on the next day.
- A warning appears `preLockSeconds` before the start; the phone locks at the start time.
- During the warning, **Cancel this lock** skips that occurrence; the schedule remains.
- A one-time schedule is removed after its end, or immediately if its only occurrence is cancelled/exited.

### Language

Tap **Language** and choose **中文** or **English**. The choice is stored by Android's per-app language setting and also appears in system app-language settings.

## Behavior details

- Lock duration starts after the phone actually locks; the pre-lock warning is not included.
- During an active session, every unlock gets `graceSeconds`; turning the screen off stops the grace timer.
- At the end time, the app stops re-locking but **cannot unlock the phone automatically**. Unlock it normally.
- Once locked, changing or deleting schedules/settings does not shorten the current session.
- One-time schedules are cleaned from both saved data and the task list.
- The status bar shows the system alarm icon because exact alarms use `setAlarmClock`.
- The service is a foreground service; exact alarms and a battery whitelist keep it reliable.

## Known limitations

- This is a personal self-control tool. It does not resist uninstalling the app, disabling permissions, or Force Stop.
- Device admin mode may require a PIN/pattern/password on each unlock and may disable fingerprint for that unlock.
- `USE_EXACT_ALARM` fits personal sideloading. Google Play restricts it to alarm clock/timer/calendar use cases; for Play distribution, switch to `SCHEDULE_EXACT_ALARM` with a user grant.
- OEM battery managers can kill background services. Whitelist the app and enable autostart.
- Changing the system clock can affect wall-clock schedules; the app reschedules on time/timezone changes.
- The app cannot auto-unlock the phone at session end.

## Project layout

```text
app/src/main/java/com/me/keepmeout/
  MainActivity.kt                 Main screen, permissions, lock now, schedules, language
  ScheduleEditActivity.kt         Weekly / one-time schedule editor
  data/AppPrefs.kt                Settings, schedules, session and suppression state
  data/Schedule.kt                Schedule model and JSON storage
  schedule/ScheduleCalculator.kt  Pure time-window calculation
  schedule/AlarmScheduler.kt      Exact warning/start/end alarms
  service/LockService.kt          Foreground service and lock state machine
  service/LockAccessibilityService.kt
  admin/LockDeviceAdminReceiver.kt
  ui/OverlayController.kt         Top floating countdown
  ui/NotificationController.kt    Foreground notification
```

## Tests

```bash
./gradlew testDebugUnitTest
```

Unit tests cover daily/weekly windows, overnight windows, one-time schedules, overlap, pre-lock boundaries, cancellation suppression, `enabledAt`, committed sessions, manual windows, and one-time cleanup.

## License

[MIT](LICENSE) © 2026 21emerald
