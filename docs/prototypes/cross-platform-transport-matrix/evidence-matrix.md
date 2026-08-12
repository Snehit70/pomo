# Cross-platform transport evidence matrix

Captured 2026-08-12. This is a living prototype artifact. `PASS_HOST_MODEL`
means only the deterministic coordinator sketch behaved as stated. It is never
physical Android, packaged Chrome, provider, or network proof.

Environment discovery:

- `adb devices -l`: no connected targets.
- Google Chrome: not installed and not running; default browser is Helium.
- ChatGPT Chrome native-host manifest: present, but no Chrome profile/extension
  runtime was available.
- Current Pomo extension: no `offscreen` permission or WebRTC transport yet.
- Existing Android `PhoneServer` is the legacy thin-client protocol and is out
  of scope for modification or reuse as proof of the new Replica transport.

| Contract row | Host model | Packaged Chrome | Physical Android | Multi-network/provider | Current evidence |
| --- | --- | --- | --- | --- | --- |
| Route preference: LAN → internet-direct → TURN | `PASS_HOST_MODEL` | `BLOCKED_RUNTIME` | `BLOCKED_RUNTIME` | `NOT_RUN` | Coordinator selects the first available live route without changing durability. |
| TURN receipt is not durable acknowledgment | `PASS_HOST_MODEL` | `BLOCKED_RUNTIME` | `BLOCKED_RUNTIME` | `NOT_RUN` | Receipt leaves the delivery obligation pending. |
| Signed durable peer acknowledgment | `PASS_HOST_MODEL` | `BLOCKED_RUNTIME` | `BLOCKED_RUNTIME` | `NOT_RUN` | Only the modeled durable frontier acknowledgment marks peer redundancy. |
| Android↔Android direct LAN | `NOT_MODELED` | n/a | `BLOCKED_RUNTIME` | `NOT_RUN` | Requires two physical Android targets and a non-production probe. |
| Android↔Chrome direct LAN and LNA allowed/denied/revoked | `NOT_MODELED` | `BLOCKED_RUNTIME` | `BLOCKED_RUNTIME` | `NOT_RUN` | Requires visible permission ceremony and both runtimes. |
| Chrome↔Chrome reliable ordered DataChannel | `NOT_MODELED` | `BLOCKED_RUNTIME` | n/a | `NOT_RUN` | Requires two packaged stable-Chrome profiles. |
| Nostr encrypted rendezvous and wrong-recipient/replay rejection | `NOT_MODELED` | `BLOCKED_RUNTIME` | `BLOCKED_RUNTIME` | `NOT_RUN` | No real signaling adapter or relay was exercised. |
| Direct ICE classification | `NOT_MODELED` | `BLOCKED_RUNTIME` | n/a | `NOT_RUN` | Must inspect selected candidate pair on same and distinct networks. |
| Forced TURN over UDP and TCP/TLS | `PASS_HOST_MODEL` | `BLOCKED_RUNTIME` | n/a | `NOT_RUN` | Selection semantics only; no TURN packets or credentials exercised. |
| WebDAV immutable conditional creation | `PASS_HOST_MODEL` | `BLOCKED_RUNTIME` | `BLOCKED_RUNTIME` | `NOT_RUN` | Proof semantics only; no provider request executed. |
| Two WebDAV implementations, pagination and CORS | `NOT_MODELED` | `BLOCKED_RUNTIME` | `BLOCKED_RUNTIME` | `NOT_RUN` | Needs two configured providers and packaged clients. |
| Multiple independent Mailboxes | `PASS_HOST_MODEL` | `BLOCKED_RUNTIME` | `BLOCKED_RUNTIME` | `NOT_RUN` | Rollback of one modeled Mailbox downgrades only that provider. |
| Quota, rollback and credential rotation | `PASS_HOST_MODEL` | `BLOCKED_RUNTIME` | `BLOCKED_RUNTIME` | `NOT_RUN` | Failures remove protection without creating domain deletion. |
| MV3 worker termination and wake | `PASS_HOST_MODEL` | `BLOCKED_RUNTIME` | n/a | n/a | Durable obligation survives modeled generation loss; real IndexedDB proof missing. |
| Offscreen termination, browser restart, extension reload/update | `PASS_HOST_MODEL` | `BLOCKED_RUNTIME` | n/a | n/a | Live route disappears while durable obligation remains; packaged proof missing. |
| Android process death, Doze, reboot and WorkManager | `NOT_MODELED` | n/a | `BLOCKED_RUNTIME` | n/a | Requires physical Android lifecycle control. |
| Duplicate, reordered, interrupted, missing and replayed envelopes | `PARTIAL` | `BLOCKED_RUNTIME` | `BLOCKED_RUNTIME` | `NOT_RUN` | Operation-kernel prototype covers domain ingestion; transport byte-path proof missing. |
| Large-backlog backpressure | `NOT_MODELED` | `BLOCKED_RUNTIME` | `BLOCKED_RUNTIME` | `NOT_RUN` | Requires DataChannel `bufferedAmount` and bounded mailbox batches. |
| Network handoff and ICE restart | `NOT_MODELED` | `BLOCKED_RUNTIME` | `BLOCKED_RUNTIME` | `NOT_RUN` | Requires real Wi-Fi/mobile/network transitions. |
| Accurate LAN/direct/TURN/Mailbox diagnostics | `PARTIAL` | `BLOCKED_RUNTIME` | `BLOCKED_RUNTIME` | `NOT_RUN` | Host labels are distinct; physical candidate/provider attribution is unproven. |

## Exit condition

This ticket cannot resolve from the host model. Every `BLOCKED_RUNTIME` and
required `NOT_RUN` row must gain dated evidence with device/browser versions,
network topology, provider implementation, exact scenario, observed route,
durable frontier result, and retained diagnostic artifact. Code/CI, packaged
runtime, physical device, and provider evidence remain separate columns.
