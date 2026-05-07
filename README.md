# PomoRemote

PomoRemote is a mobile-first Pomodoro timer for Android. The phone is the
canonical app: it owns the timer, settings, session history, notifications, and
widgets. Desktop integrations can pair with the phone and act as thin remote
clients.

This branch intentionally stops treating a laptop/server process as the source
of truth. Existing laptop history is not imported or merged.

## What It Does

- Runs a Pomodoro timer locally in an Android foreground service.
- Persists timer state across app restarts.
- Stores completed sessions and daily stats in Room.
- Updates the Timer, Stats, History, notification, and home-screen widget from
  phone-owned state.
- Hosts a local HTTP/WebSocket API for desktop clients.
- Protects remote control with a pairing token.

## Build

Requires JDK 17+.

The repository does not currently include a Gradle wrapper. Use the lightweight
builder, which uses the local SDK/Gradle setup in this checkout:

```bash
./build_apk.sh
```

Or, if Gradle and the Android SDK are already on your path:

```bash
gradle assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Run On A Device

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.pomoremote/.MainActivity
```

Useful logs:

```bash
adb logcat -s PomodoroService PhoneServer
```

## Pair A Desktop Client

1. Open the Android app.
2. Go to Settings.
3. Tap "Pair desktop client".
4. Use the displayed JSON payload in the desktop client:

```json
{
  "url": "http://<phone-ip>:9876",
  "token": "<pairing-token>"
}
```

The phone must be reachable on the same network. The default API port is
`9876`, configurable in Settings.

## Architecture

```text
app/src/main/java/com/pomoremote/
├── MainActivity.kt
├── service/
│   ├── PomodoroService.kt        # Canonical timer owner
│   ├── NotificationHelper.kt
│   └── NotificationActionReceiver.kt
├── timer/
│   ├── TimerState.kt             # JSON-compatible state model
│   └── OfflineTimer.kt           # Local countdown engine
├── db/
│   ├── AppDatabase.kt
│   ├── HistoryDao.kt
│   ├── HistoryCacheRepository.kt # Room-backed canonical history access
│   ├── SessionEntity.kt
│   └── DayStatsEntity.kt
├── network/
│   └── PhoneServer.kt            # Embedded Ktor REST/WebSocket API
├── ui/
│   ├── TimerFragment.kt
│   ├── StatsFragment.kt
│   ├── HistoryFragment.kt
│   ├── SettingsFragment.kt
│   └── AboutFragment.kt
├── util/
│   ├── UtilPreferenceManager.kt
│   └── SoundManager.kt
└── widget/
    └── TimerWidgetProvider.kt
```

### State Flow

```text
User/notification/widget/API command
        ↓
PomodoroService
        ↓
OfflineTimer + Room history
        ↓
State broadcast
        ↓
Timer UI, Stats UI, History UI, notification, widget, WebSocket clients
```

`PomodoroService` is the write boundary. UI, notification buttons, widgets, and
remote clients all go through service methods. Room is the canonical history
store. The embedded API exposes the phone state; it does not merge state from a
desktop process.

## Remote API

See [docs/protocol.md](docs/protocol.md) for endpoint details, authentication,
payload shapes, and WebSocket behavior.

For a deeper implementation map, see [docs/architecture.md](docs/architecture.md).

For the new thin TypeScript laptop client, see
[docs/desktop-client.md](docs/desktop-client.md).

## Validation

Build check:

```bash
gradle assembleDebug
```

Manual checks worth doing on device:

- App starts and can run with no laptop/server process.
- Start, pause, resume, skip, reset, and extend all mutate phone state.
- Completed focus sessions appear in Today, Stats, History, notification, and
  widget.
- Restarting the app restores stopped/paused/running timer state sensibly.
- `GET /api/status` rejects missing tokens and returns state with a valid token.
- `/ws` accepts a valid hello token and streams state updates.

## Releases

Releases are automated from `main`.

When a PR is merged, `.github/workflows/version-bump.yml` inspects the commit
messages in that push, bumps `versionCode` and `versionName` in
`app/build.gradle.kts`, commits the version bump back to `main`, and creates a
tag like `v1.5.1`.

The bump type follows Conventional Commits:

- `feat:` creates a minor release.
- `fix:` or `perf:` creates a patch release.
- `!` or `BREAKING CHANGE:` creates a major release.
- Anything else defaults to a patch release, so every merged PR can still ship.

When a `v*` tag is pushed, `.github/workflows/release.yml` builds debug and
unsigned release APKs, uploads them as workflow artifacts, and publishes a
GitHub Release with generated release notes.

## Notes

- Cleartext local-network traffic is allowed by
  `network_security_config.xml`.
- The pairing token is generated and stored in shared preferences.
- Legacy laptop/server sync classes were removed from the Android app.
