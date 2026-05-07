# PomoRemote Phone Protocol

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

Adds minutes to the current timer. The server clamps accepted values to a
reasonable positive range.

```bash
curl -X POST \
  -H "X-Pomo-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"minutes":5}' \
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
  "daily_goal": 8,
  "day_start_hour": 3
}
```

### POST /api/config

Replaces timer configuration on the phone. If the timer is not running, the
current phase duration is recalculated from the new config.

```bash
curl -X POST \
  -H "X-Pomo-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "durations": { "work": 25, "short_break": 5, "long_break": 15 },
    "long_break_after": 4,
    "daily_goal": 8,
    "day_start_hour": 3
  }' \
  "$PHONE_URL/api/config"
```

### GET /api/history

Returns Room-backed canonical history keyed by logical date:

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

The logical date respects the phone's `day_start_hour`.

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
- Never write canonical timer or history state locally.
