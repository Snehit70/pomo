# Sync health, conflict, and Data History recovery prototype

Throwaway planning evidence for [Prototype sync health, conflict, and Data History recovery](https://github.com/Snehit70/pomo/issues/91).

Open [`sync-health-recovery-prototype.html`](./sync-health-recovery-prototype.html) directly in a browser. Use the bottom arrows or `?variant=A`, `?variant=B`, and `?variant=C` to compare the three structural approaches. The state control simulates Healthy, Offline, Stalled, Quarantine, Timer conflict, and Recovery incomplete conditions.

## Accepted combination

The member accepted a deliberate combination rather than one complete variant:

- **Variant A, Signal rail, defines everyday status.** Keep the timer primary. Show separate compact dimensions for Saved locally, Peer-redundant, Protected sync, and Attention. Selecting the status opens diagnostics. Manual action is `Retry now`, which schedules the ordinary bounded drain without bypassing safety rules.
- **Variant B, History lens, defines Data History.** Present the permanent logical record chronologically with disposition, provenance, causal frontier, and exact projection effect. Accepted, Pending, Quarantined, and Rejected Operations remain distinguishable. Known-good state stays visible while unresolved material is inspected.
- **Variant C, Recovery workbench, defines recovery.** Compare the current verified frontier with a named Recovery anchor by domain. Let the member select History, tags, preferences, Profile, and Crew effects. Prepare a forward recovery plan that creates a Safety checkpoint and appends attributable compensating Operations. Never rewind the journal or resurrect an old Active phase, revoked Device authority, Content epoch, or Recovery authority.

This produces one progressive product shape:

1. Everyday timer surfaces only the four sync-health dimensions and actionable attention count.
2. Sync diagnostics explain the condition without collapsing it into one `synced` flag.
3. Data History provides chronological provenance and dispositions.
4. Recovery opens only when requested or required, starting from historical comparison.
5. An agent may prepare a recovery plan, but the member confirms it. Agent-proposed or destructive changes retain the accepted independent-confirmation requirement.

## UX rules settled

- Offline is not failure. State whether new work is Saved locally, Peer-redundant, and Mailbox-protected.
- Missing, empty, unreadable, or corrupt local data is Replica failure, never deletion intent.
- A stalled route does not imply lost data. Explain the durable obligation and offer `Retry now`.
- Quarantine is protective. Known-good projections remain usable and suspicious Operations remain attributable.
- Conflict status identifies the affected domain. Unrelated Pomo data remains usable.
- Recovery incomplete states the exact gaps and the authoring capabilities they restrict.
- Historical comparison is domain-aware. Raw database rows, timestamps, or provider listings never define recovery truth.
- Recovery restore is selective, forward-only, attributable, and reversible through a later forward restore.
- No screen promises `fully synced`, permanent retention, silent repair, or automatic destructive reconciliation.

## Boundary

This artifact is UI planning evidence only. It contains static sample data and in-memory interactions. It does not implement the Operation kernel, journal storage, Materializer, transport, Checkpoints, Recovery authority, Android UI, Chrome UI, NodeMCU, or physical-device behavior. The embedded JavaScript was syntax-checked; repository lint and tests were not run under project rules. Visual browser automation was unavailable in the authoring session.
