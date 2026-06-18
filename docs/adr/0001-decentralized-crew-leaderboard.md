# Crew leaderboard runs over open Nostr-style relays, not a backend we own

We want a friends leaderboard ([[Crew]]) but refuse to operate or pay for
backend infrastructure. Truly direct phone-to-phone is impossible across the
internet (phones have no public address and sleep), and a backend we run is a
non-goal. We chose to publish per-member [[Snapshot]]s as signed,
Crew-encrypted events to a set of public Nostr-style [[Relay]]s run by
strangers; every member's app pulls and ranks locally. "Decentralized" therefore
means "no central authority and no infra we own" — not "no servers anywhere."

## Considered Options

- **Our own backend (Firebase/Supabase/custom):** rejected — ongoing cost/ops,
  and it makes us the central authority we explicitly don't want to be.
- **LAN-only P2P (reuse Ktor + NSD):** rejected as the primary path — only works
  when everyone is on the same Wi-Fi; a leaderboard implies remote friends.
- **Open relays (chosen):** internet reach, no central authority, anonymous
  keypair identity, no accounts. Cost is relay flakiness and the engineering of
  encryption + publish/pull over an eventually-consistent transport.

## Consequences

- Identity is a per-device keypair ([[Identity key]]); reinstall = new identity
  and lost board history unless we later add key export. Shipping without export.
- Self-reported metrics are unverifiable and add-time has no cap — the board is
  honor-system by design.
- No admin: nobody can be kicked. Stale members are aged out by last-active, not
  removed.
- Freshness is event-driven + tab-refresh; "focusing now" is really "last seen
  N minutes ago," never a live signal.

## Decision Note 0001-A: Snapshot Protocol Contract

Human sign-off: approved in-thread by Snehit on 2026-06-18 to unblock the Crew
implementation slices.

### Event Kind

Crew Snapshots use one app-specific parameterized replaceable Nostr event kind:

- `kind = 39050`
- `pubkey = Identity key public key`
- `tags` include `["d", crewId]`, so each `{kind, pubkey, crewId}` stores the
  latest Snapshot for one Identity in one Crew.
- `content` is the encrypted Snapshot envelope.

This matches the product model: every member publishes one latest Snapshot per
Crew, relays may discard older versions, and the app ranks locally after pull.

### Encryption And Signing

Snapshots are signed by the member's Identity key using the normal Nostr event
signature. The Identity key is the author identity; display name remains
self-asserted Snapshot content.

Snapshot content is encrypted with NIP-44 v2 semantics:

- The join code carries a Crew private key, not just an opaque symmetric secret.
- The Crew public key is derived from that private key and may be public.
- Publishers encrypt Snapshot JSON from their Identity private key to the Crew
  public key.
- Readers decrypt with the Crew private key and the event author's public key.
- Decryption failure, malformed plaintext, or event signature failure rejects the
  Snapshot.

Relays only see event metadata and ciphertext. Anyone holding the join code can
decrypt Crew Snapshots, which is the intended membership model.

### Default Relays

The bundled relay set is:

- `wss://relay.damus.io`
- `wss://nos.lol`
- `wss://relay.primal.net`

Publishing fans out to all configured relays. A publish is usable if at least
one relay accepts it. Pulling merges events from all reachable relays and keeps
the latest valid Snapshot per Identity key.

Join codes may override the relay list. When a join code omits relays, the app
uses the bundled defaults.

### Client Implementation

Use a small hand-rolled Nostr client for this feature rather than adding a
general-purpose Kotlin Nostr SDK now.

Rationale:

- The first Crew slices need only a narrow subset: connect WebSocket, send
  `REQ`/`EVENT`/`CLOSE`, parse relay responses, build/sign events, and apply
  NIP-44 v2 Snapshot encryption.
- A narrow interface keeps relay transport as the thin edge while the join-code,
  Snapshot codec, and leaderboard aggregator stay JVM-testable.
- A broad SDK can be revisited if later Crew features need more NIPs, relay
  management, or compatibility behavior than this subset.
