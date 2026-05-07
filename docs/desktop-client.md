# Desktop Client

The desktop client is a thin companion for the mobile-primary Android app. It
does not own timer state, write history, or reconcile sync conflicts. It stores
pairing details and a stale display cache, then talks to the phone API for every
real command.

## Shape

```text
desktop-client/
├── package.json
├── tsconfig.json
└── src/
    ├── cli.ts       # command entry point
    ├── api.ts       # authenticated phone API calls
    ├── config.ts    # local pairing config and stale cache
    ├── format.ts    # human and Waybar output
    ├── service.ts   # launchd/systemd template output
    ├── types.ts
    └── validate.ts
```

## Commands

```bash
npm --prefix desktop-client run build
node desktop-client/dist/cli.js pair http://PHONE_IP:9876 PAIRING_TOKEN
node desktop-client/dist/cli.js pair-json '{"url":"http://PHONE_IP:9876","token":"PAIRING_TOKEN"}'
node desktop-client/dist/cli.js qr
node desktop-client/dist/cli.js status
node desktop-client/dist/cli.js status --json
node desktop-client/dist/cli.js status --waybar
node desktop-client/dist/cli.js toggle
node desktop-client/dist/cli.js skip
node desktop-client/dist/cli.js reset
node desktop-client/dist/cli.js extend 5
node desktop-client/dist/cli.js service-template
node desktop-client/dist/cli.js service install
node desktop-client/dist/cli.js service start
node desktop-client/dist/cli.js service status
node desktop-client/dist/cli.js service stop
```

The command names intentionally match the old desktop affordances where useful,
but the behavior is inverted: commands call the phone API instead of mutating
local canonical state.

`toggle`, `skip`, `reset`, and `extend` print the phone state returned by the
API. They never fall back to local state for writes.

## Config

Pairing writes:

```json
{
  "phone_url": "http://PHONE_IP:9876",
  "pairing_token": "TOKEN",
  "request_timeout_ms": 10000
}
```

`request_timeout_ms` is optional and defaults to `10000`. Empty URLs or tokens
are rejected. The config file is written with owner-only permissions.

macOS path:

```text
~/Library/Application Support/pomo-remote/desktop-client.json
```

Linux path:

```text
${XDG_CONFIG_HOME:-~/.config}/pomo-remote/desktop-client.json
```

## Cache

`status`, command responses, and `watch` try to write the last successful phone
state to a local cache. If the phone is offline, `status` and `status --waybar`
can display that stale state with an offline marker. The cache is never sent
back to the phone.

Cache writes are best-effort. If the phone request succeeds but the cache cannot
be written, the CLI still prints the fresh phone state and logs a warning. This
prevents local disk issues from making successful remote commands look failed.

Linux cache path:

```text
${XDG_STATE_HOME:-~/.local/state}/pomo-remote/last-state.json
```

macOS cache path:

```text
~/Library/Application Support/pomo-remote/last-state.json
```

## Service

`service-template` prints a launchd plist on macOS and a systemd user unit on
Linux. The `service` command can also write and manage that user service:

```bash
node desktop-client/dist/cli.js service install
node desktop-client/dist/cli.js service start
node desktop-client/dist/cli.js service status
node desktop-client/dist/cli.js service stop
```

On macOS this writes:

```text
~/Library/LaunchAgents/dev.pomoremote.desktop-client.plist
```

On Linux this writes:

```text
${XDG_CONFIG_HOME:-~/.config}/systemd/user/pomo-remote-desktop-client.service
```

The service runs `watch`, which periodically refreshes the stale cache.
Notifications and richer WebSocket behavior can be layered on later without
changing Android ownership.

The generated service uses the absolute Node executable that built the client,
so it does not depend on the reduced PATH that launchd/systemd often use.
Systemd service status is displayed even when the unit is inactive or failed.

On macOS, `service install` reloads an already-loaded launchd agent after
rewriting the plist. `service stop` disables and signals the loaded agent
without unloading it, so `service start` can enable and kickstart it again.

## QR Pairing

The Android app shows a QR code for its pairing payload. After pairing the CLI,
`qr` prints the same payload as a terminal QR code:

```bash
node desktop-client/dist/cli.js qr
```

This is mainly useful when moving pairing details between machines or checking
that the saved desktop config still points at the expected phone.

## Failure Behavior

- Phone/API failures cause command errors.
- `status` may show stale cache when the phone cannot be reached.
- Local cache write failures only warn.
- Pairing config write failures still fail, because the token was not saved.
- The request timeout defaults to 10 seconds.
