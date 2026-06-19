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

## Decision Note 0001-B: Snapshot Crypto Reconciliation

Supersedes the cryptographic specifics of 0001-A. Documents the scheme actually
shipped on `main` as of 2026-06-19. Tracking: issue #33.

0001-A specified NIP-44 v2 with an asymmetric Crew keypair derived from the join
code. The shipped implementation diverges, and this note makes the shipped
behavior the source of truth. We chose to document reality rather than rewrite
shipped crypto: the current scheme is confidential for the honor-system model,
and a NIP-44 migration would be breaking (it invalidates every already-published
[[Snapshot]]) and security-sensitive enough to deserve its own review.

### Snapshot Encryption (As Shipped)

- The join code `key` is a 256-bit random secret used as a **shared symmetric
  passphrase**, not a Crew private key. There is no derived Crew public key and
  no ECDH.
- Snapshot content is encrypted with **AES-256-GCM** (`AES/GCM/NoPadding`). The
  AES key is `SHA-256(key)` over the passphrase's UTF-8 bytes, with a fresh
  12-byte random nonce per Snapshot and a 128-bit authentication tag. The nonce
  and ciphertext+tag are stored base64url (unpadded) in the envelope.
- The membership property is unchanged from 0001-A: anyone holding the join code
  can decrypt every member's Snapshot. Relays still see only ciphertext and
  event metadata.

### Identity (As Shipped)

Two per-device keys operate at different layers:

- **Transport author:** a **secp256k1** key (`CrewNostrKeys`) signs the outer
  Nostr event (`kind 39050`) with a standard Schnorr signature, as Nostr
  requires.
- **Snapshot author / ranked identity:** an **RSA-2048 `SHA256withRSA`** key
  (`CrewIdentityKeys`) signs the inner Snapshot envelope, and its public key is
  the `identityPublicKey` the leaderboard de-dupes and ranks on. A reader rejects
  any envelope whose signature does not verify against the embedded
  `identityPublicKey`.

Both keys live in non-backed-up `pairing_prefs`; reinstall yields new identities,
as 0001-A already accepted.

### Consequences And Future Work

- This scheme provides confidentiality plus per-Snapshot integrity (via the RSA
  envelope signature), but not NIP-44's sender-authenticated ECDH. Stating that
  plainly here is the point of this note.
- A future migration to NIP-44 v2 with a single secp256k1 identity (collapsing
  the RSA key into the Nostr key) remains open. It is breaking and tracked
  separately under issue #33.
