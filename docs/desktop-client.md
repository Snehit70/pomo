# Desktop Client

The desktop client is a thin companion for the mobile-primary Android app. It
does not own timer state, write history, or reconcile sync conflicts. It only
stores pairing details and a stale display cache.

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

## Config

Pairing writes:

```json
{
  "phone_url": "http://PHONE_IP:9876",
  "pairing_token": "TOKEN"
}
```

macOS path:

```text
~/Library/Application Support/pomo-remote/desktop-client.json
```

Linux path:

```text
${XDG_CONFIG_HOME:-~/.config}/pomo-remote/desktop-client.json
```

## Cache

`status` writes the last successful phone state to a local cache. If the phone
is offline, `status` and `status --waybar` can display that stale state with an
offline marker. The cache is never sent back to the phone.

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

## QR Pairing

The Android app shows a QR code for its pairing payload. After pairing the CLI,
`qr` prints the same payload as a terminal QR code:

```bash
node desktop-client/dist/cli.js qr
```

This is mainly useful when moving pairing details between machines or checking
that the saved desktop config still points at the expected phone.
