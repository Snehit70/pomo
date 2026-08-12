# Physical transport prototype handoff

This checklist is for the person responsible for physical Android and packaged
Chrome testing. It must be run against the throwaway transport probe once that
probe is packaged; it must not modify NodeMCU firmware or reuse NodeMCU results
as Replica-sync evidence.

## Required lab inventory

- Two physical Android devices covering API 26-30 and API 31+ custody paths.
- Two clean profiles in Google Chrome: minimum supported Chrome 120 and current
  stable, with the packaged Pomo probe loaded.
- Two internet paths that can be separated, such as Wi-Fi and mobile hotspot.
- Configurable STUN and TURN endpoints; TURN must expose UDP and TCP/TLS paths.
- Two distinct WebDAV implementations with test-only credentials and enough
  control to force quota, rollback, pagination, CORS, and credential rotation.
- At least two Nostr relays, with the ability to disconnect or delay one relay.
- A packet/network observation method that does not record Pomo plaintext or
  private credentials.

Record exact device model, OS/browser version, extension build hash, network
topology, provider implementation/version, and timestamp for every run.

## Live-route matrix

1. Android↔Android on one LAN: prove authenticated catch-up and signed durable
   acknowledgments; repeat with discovery unavailable but an already-known
   address.
2. Android↔Chrome on one LAN: run Local Network permission allowed, denied, and
   revoked. Only the LAN route may degrade.
3. Chrome↔Chrome on one LAN: prove the DataChannel lives in the offscreen
   document, uses an mDNS-obfuscated host candidate where Chrome does so, and is
   diagnosed as direct LAN.
4. Chrome↔Chrome on separate networks: capture the selected server-reflexive
   direct candidate pair when possible.
5. Block direct ICE and force TURN over UDP, then TCP/TLS. Diagnose both as
   relayed and never as protected.
6. Disable Nostr rendezvous: existing sessions may continue, new sessions wait,
   and Mailbox catch-up remains available.

## Durable Mailbox matrix

Run every case independently against both WebDAV implementations:

1. Conditional immutable creation followed by read/hash/size verification.
2. Duplicate upload and interrupted upload retry.
3. Paginated listing with direct known-object challenge; listing order and
   timestamps must not define truth.
4. CORS allowed and denied from packaged Chrome.
5. Quota exhaustion without loss of local or peer durability.
6. Provider rollback or missing object: downgrade only that Mailbox and repair
   from another source without emitting deletion.
7. Credential failure and rotation: stale Recovery-file warning where a shared
   credential requires re-export.
8. Two Mailboxes configured: failure of either one leaves the other's verified
   protection intact.

## Lifecycle and fault matrix

- Terminate the MV3 worker during signaling, ingress staging, journal commit,
  acknowledgment scheduling, and backlog drain.
- Close/recreate the offscreen document; restart Chrome; reload and update the
  extension; change networks; force ICE failure and restart.
- Kill the Android process; enter and leave Doze; reboot; delay and retry unique
  WorkManager work.
- Deliver duplicate, reordered, interrupted, missing, replayed, expired, and
  wrongly addressed envelopes through every route.
- Transfer a large backlog while observing DataChannel `bufferedAmount`, bounded
  batches, memory, and resumption frontier.
- At every crash point confirm that `send()` or HTTP success alone never clears
  a delivery obligation and that no false durable acknowledgment appears.

## Required evidence per scenario

- Expected and observed route classification.
- Sender and receiver causal frontiers before and after.
- Saved-locally, peer-redundant, and per-Mailbox protected states.
- Durable acknowledgment identity and commit point.
- Pending/quarantined/rejected disposition counts.
- Lifecycle generation/restart observation and retry/backoff state.
- Sanitized logs or screenshots showing the result.
- Explicit result: `PASS_PHYSICAL`, `FAIL_PHYSICAL`, or `BLOCKED`, never a
  blended “works” status.

The Wayfinder ticket remains open until the required rows in
[`evidence-matrix.md`](evidence-matrix.md) have this evidence.
