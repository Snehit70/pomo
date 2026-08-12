# Complete transport matrix — throwaway prototype

This prototype asks whether Pomo's accepted transport topology preserves one
delivery contract across Android LAN, Chrome WebRTC, Nostr rendezvous, TURN, and
multiple WebDAV Mailboxes while runtimes and providers fail.

The current artifact is intentionally incomplete because no Android target is
connected and Google Chrome is not installed. It does not claim physical proof.

## Host-model finding

The transport coordinator should be a deep module above replaceable route
Adapters. Its interface records durable facts rather than socket events:

- `enqueue(operationId)` records a durable delivery obligation.
- `observeRoute(route, health)` updates diagnostics and route preference.
- `recordReceipt(operationId, route)` records only transient transport receipt.
- `recordDurablePeerAck(operationId)` marks peer redundancy only after signed,
  durable journal acknowledgment.
- `recordMailboxProof(operationId, mailbox)` marks protection independently for
  a specific verified immutable Mailbox copy.
- `wake()` schedules a bounded drain from persisted state without clearing
  backoff, diagnostics, or obligations.

[`transport-model.ts`](transport-model.ts) demonstrates that TURN receipt does
not complete delivery, worker/offscreen loss does not erase an outbox,
independent Mailboxes degrade independently, and rollback/credential rotation
never imply domain deletion.

Run it with:

```bash
bun docs/prototypes/cross-platform-transport-matrix/transport-model.ts
```

[`evidence-matrix.md`](evidence-matrix.md) is the canonical proof ledger. It
separates host-model, packaged-Chrome, physical-Android, and real
network/provider evidence so a simulated pass cannot masquerade as product
readiness.

## Scope boundary

This branch does not modify production Android, Chrome, Room, IndexedDB,
NodeMCU, firmware, or the existing phone protocol. The existing `PhoneServer`
is evidence of current LAN capability only; it is not the new Replica transport
and is not changed here.
