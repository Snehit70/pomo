# Pomo Phone Protocol

The Android app hosts a local API from `PomodoroService` using Ktor CIO. Desktop
clients are remote controls and displays; the phone remains the source of truth.

Default base URL:

```text
http://<phone-ip>:9876
```

The pairing payload shown in Android Settings contains the base URL and token:

```json
{
  "url": "http://<phone-ip>:9876",
  "token": "<pairing-token>"
}
```

Android Settings can also render this payload as a QR code. Desktop tooling may
print or consume the same JSON payload; it does not change the protocol.
The Android scanner entry uses the same payload shape and only compares it to
the current phone pairing token; scanning does not mutate canonical phone state.

## Authentication

REST requests must include:

```text
X-Pomo-Token: <pairing-token>
```

Missing or invalid REST tokens return:

```http
401 Unauthorized
```

```json
{
  "success": false,
  "error": "unauthorized"
}
```

WebSocket clients authenticate with their first message:

```json
{
  "type": "hello",
  "token": "<pairing-token>"
}
```

Invalid WebSocket tokens are closed without subscribing the client.

## Timer State

Timer state is JSON-compatible with `TimerState.kt`:

```json
{
  "status": "running",
  "phase": "work",
  "next_phase": "short",
  "start_time": 1710000000.0,
  "duration": 1500.0,
  "remaining": 1432.0,
  "completed": 2,
  "daily_goal": 8,
  "date": "2026-05-07",
  "last_action_time": 1710000000,
  "version": 2
}
```

Allowed values:

```text
status: stopped | running | paused
phase:  work | short | long
```

## REST Endpoints

### GET /api/status

Returns the current phone-owned timer state.

```bash
curl -H "X-Pomo-Token: $TOKEN" "$PHONE_URL/api/status"
```

### POST /api/toggle

Starts, pauses, or resumes the current timer.

```bash
curl -X POST -H "X-Pomo-Token: $TOKEN" "$PHONE_URL/api/toggle"
```

Response:

```json
{
  "success": true,
  "state": { "...": "TimerState" }
}
```

### POST /api/skip

Skips to the next phase and returns the new state.

```bash
curl -X POST -H "X-Pomo-Token: $TOKEN" "$PHONE_URL/api/skip"
```

### POST /api/reset

Resets the current phase timer and returns the new state.

```bash
curl -X POST -H "X-Pomo-Token: $TOKEN" "$PHONE_URL/api/reset"
```

### POST /api/extend

Adds a positive seconds delta to the currently running timer. Add-time is
uncapped by design; stopped and paused timers are left unchanged.

```bash
curl -X POST \
  -H "X-Pomo-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"seconds_delta":300}' \
  "$PHONE_URL/api/extend"
```

### GET /api/config

Returns timer configuration:

```json
{
  "durations": {
    "work": 25,
    "short_break": 5,
    "long_break": 15
  },
  "long_break_after": 4,
  "daily_goal": 8
}
```

### POST /api/config

Updates timer configuration on the phone. Payloads may be partial; omitted or
invalid values keep the existing phone setting. If the timer is not running, the
current phase duration is recalculated from the merged config.

```bash
curl -X POST \
  -H "X-Pomo-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "durations": { "work": 25, "short_break": 5, "long_break": 15 },
    "long_break_after": 4,
    "daily_goal": 8
  }' \
  "$PHONE_URL/api/config"
```

Accepted fields:

```text
durations.work       positive integer minutes
durations.short_break positive integer minutes
durations.long_break positive integer minutes
long_break_after     positive integer
daily_goal           non-negative integer
```

### GET /api/history

Returns Room-backed canonical history keyed by the phone's local calendar date:

```json
{
  "2026-05-07": {
    "completed": 3,
    "work_minutes": 75,
    "break_minutes": 10,
    "sessions": [
      {
        "type": "work",
        "start": 1710000000,
        "duration": 1500,
        "completed": true
      }
    ]
  }
}
```

Sessions that cross midnight are split into per-date segments. Work and break
seconds are rounded up to minutes for each date segment. A completed work
session increments the completed-session count only on the final segment, so a
single session never double-counts against the daily goal.

## WebSocket

Connect to:

```text
GET /ws
```

First message:

```json
{
  "type": "hello",
  "token": "<pairing-token>"
}
```

State messages are sent immediately after authentication and after every state
change:

```json
{
  "type": "state",
  "data": { "...": "TimerState" }
}
```

Clients should treat WebSocket updates as display/cache updates. Commands should
still use the authenticated REST endpoints.

## Client Contract

Desktop clients should:

- Store `url` and `token` from the pairing payload.
- Use REST endpoints for commands.
- Use WebSocket updates or polling for display.
- Cache the last successful state only for stale/offline display.
- Treat cache writes as best-effort and local-only.
- Never write canonical timer or history state locally.
