# CLAUDE.md

Lean repo guidance for agents working here.

## Project

- Android app: `com.pomoremote`
- Language: Kotlin
- Min SDK: 26
- Target SDK: 34
- Source of truth: Android phone

The phone owns timer state, settings, Room history, notifications, widgets, and
the embedded desktop-client API. Do not reintroduce laptop/server authority.

## Build

Requires JDK 17+.

```bash
./build_apk.sh
```

If local Gradle and Android SDK are configured:

```bash
gradle assembleDebug
```

This repo currently has no `./gradlew`.

## Useful ADB

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.pomoremote/.MainActivity
adb logcat -s PomodoroService PhoneServer
```

## Architecture

```text
service/PomodoroService.kt   # canonical timer owner
timer/OfflineTimer.kt        # local countdown engine
db/                          # Room history and stats
network/PhoneServer.kt       # Ktor REST/WebSocket API
ui/                          # Timer, Stats, History, Settings, About
widget/TimerWidgetProvider.kt
```

State flow:

```text
PomodoroService -> OfflineTimer/Room -> UI, notification, widget, PhoneServer
```

## Development Rules

- Keep changes minimal and Kotlin-first.
- Read relevant files before editing.
- Do not restore old laptop sync paths.
- Treat Room as canonical history.
- Update `versionCode` and `versionName` for significant app changes.
- Run the narrowest relevant build/check before finishing.
