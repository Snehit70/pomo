# Offline timer ownership and reconciliation

Research for [Compare offline timer ownership and reconciliation models](https://github.com/Snehit70/pomo/issues/99).

## Executive conclusion

Pomo cannot simultaneously guarantee both of these properties while two Authorized devices are partitioned:

1. every device remains able to change the timer offline; and
2. exactly one device is the real-time exclusive owner everywhere.

That is a distributed-systems limit, not an implementation gap. Gilbert and Lynch prove that an asynchronous partition-tolerant system cannot provide both availability and atomic consistency. Raft obtains one ordered command log only through a majority, and explicitly loses write availability without one.[^cap][^raft]

Pomo should therefore use **causal ownership claims**, not wall-clock leases:

- An uncontested owner issues commands for one identified Active phase.
- A normal handoff is a causally authorized transition from the current owner to a named Device.
- An offline takeover remains available, but it is visibly provisional until synchronization proves it uncontested.
- Commands carry the exact ownership claim and timer heads from which they descend.
- A Device that has observed a newer causal claim fences itself immediately.
- Independently created offline claims remain concurrent branches. No timestamp, “largest generation,” Device ID, or shortest remaining time silently chooses one.
- Replicas converge deterministically on either an uncontested timer or `SETTLEMENT REQUIRED`; they retain every authentic branch and settle conflicts with a later Operation that references all heads.

This gives Pomo honest offline availability, deterministic convergence, and no silent data loss. It does **not** pretend to provide impossible cross-partition mutual exclusion.

## What established systems actually guarantee

### Consensus can provide one owner, but only to a communicating quorum

Raft terms are monotonic logical clocks that let servers reject obsolete leaders. A candidate must contact a majority to become leader, and a command is committed after majority replication. Raft's log safety does not depend on correct physical clocks; bad clocks and long delays affect availability instead.[^raft]

etcd exposes leases, locks, and elections on top of a linearizable, consensus-backed store. Its own failure documentation says the cluster cannot accept writes after majority failure.[^etcd-api][^etcd-failure]

These are strong precedents for two Pomo rules:

- Attach an ownership term to every protected command and reject terms known to be obsolete.
- Never describe a term independently minted by an offline Replica as globally exclusive. There was no quorum or ordered lock service that could establish that fact.

Requiring a Pomo-operated coordinator, WebDAV provider, Nostr relay, or TURN server to arbitrate ownership would also make that service an availability and trust dependency. Treating several user devices themselves as a Raft cluster would make a one-of-two partition unwritable, contrary to the accepted offline requirement.

### A lease is not a substitute for ordering

Gray and Cheriton's leases are time-bounded contracts designed around explicit assumptions about physical clocks and bounded clock-rate behavior.[^leases] Chubby implements client leases through a consensus-backed master, uses conservative client timeouts and a known bound on relative clock rates, and stops trusting cached state while the session is uncertain.[^chubby]

Chubby also explains the stale-writer problem: an old lock holder may send a delayed request after another holder has acquired the lock. Its solution is a **sequencer** containing the lock generation; the protected resource checks the sequencer and rejects it if stale.[^chubby]

For Pomo:

- A “last seen 30 seconds ago” timeout may influence presence UI or suggest takeover, but it cannot grant authority.
- Timer expiry cannot revoke an owner. Completion terminates a phase through domain semantics; it is not lease expiry.
- An ownership claim only fences stale work when the journal/materializer checks it at the mutation boundary.
- A scalar generation is meaningful along a causal succession chain. Two disconnected Devices can both write `generation + 1`; comparing the numbers afterward does not turn either into a legitimate fence.

### Generic CRDT convergence does not resolve timer meaning

Invariant-confluence research establishes that coordination-free execution is safe exactly when independently valid states can merge without violating the application's invariant.[^iconfluence] Additive observations and immutable history are good candidates. “At most one accepted current owner branch,” however, is not preserved when two offline Replicas independently claim the timer.

Automerge retains concurrent values even while choosing an arbitrary but deterministic visible winner. Its conflict API exposes the hidden alternatives.[^automerge] CouchDB likewise replicates all revision-tree leaves, warns that its deterministic winner can look like lost work, and delegates semantic merging to the application.[^couch]

The lesson is not “use their arbitrary winner.” It is:

- preserve causal branches;
- make a conflict a first-class projection state;
- resolve it using Pomo's domain rules; and
- never confuse deterministic presentation with semantic correctness.

### Event streams provide useful command mechanics

KurrentDB's maintained client contract combines unique event IDs for idempotent retry with an expected stream revision for optimistic concurrency. A mismatched revision is a conflict, not an overwrite.[^kurrent]

Pomo already chose independent signed Device feeds rather than a central leader stream. It should borrow the mechanics:

- stable command identity;
- expected base/head;
- atomic local append;
- idempotent replay; and
- explicit conflict classification.

It should not borrow KurrentDB's centralized leader requirement.

## Recommended Pomo protocol model

### Scope of ownership

The **Active timer owner** owns the right to issue non-commutative commands for one identified **Active phase**. It does not own the Member's whole timer, history, Profile, or synchronization system.

All Replicas may:

- display/project the accepted phase;
- extrapolate its countdown locally;
- replicate Operations;
- show notifications according to local notification policy; and
- retain divergent authentic work for settlement.

Only the Device holding the current uncontested ownership claim may author normal phase commands. A parked next phase has no owner. Starting it creates a new Phase ID and initial ownership claim. Completion, Skip, Reset, or another accepted terminal transition ends that phase and releases ownership.

### Required identifiers and references

Every ownership or timer-control Operation should carry, directly or by its signed Operation envelope:

- `phaseId`: random/content-derived identity of the Active phase;
- `commandId`: stable across retry and process restart;
- `issuerDeviceId`;
- `ownershipClaimId`: the exact claim authorizing the command;
- `expectedHeads`: timer/phase heads the issuer had materialized;
- `causes`: the command or claim Operations this Operation causally follows;
- phase kind and command payload; and
- clock evidence for projection/diagnostics, never precedence.

An **ownership claim** is identified by its Operation ID. Claims form a causal graph. A human-friendly generation number may be included for display and validation along one chain, but the claim ID and causal ancestry are authoritative.

### Normal command path

1. Read the current local projection and its phase heads.
2. Verify that the local Device holds the current uncontested ownership claim.
3. Construct the command against those exact heads.
4. Atomically append the signed Operation, Device-feed head, and outbox entry before reporting success.
5. Materialize it locally and execute side effects from the durable result.

A duplicated `commandId` is a no-op. A command missing its causal prerequisites remains Pending. A command against a causally superseded claim is Rejected or Quarantined according to whether the issuer had already observed the successor. A command concurrent with a takeover/other branch is retained as part of the conflict; arrival order does not decide it.

### Seamless online control and handoff

When a user presses a control on a non-owner Device and the owner is reachable, Pomo can keep the UX smooth without weakening the model:

1. The non-owner sends an authenticated command/handoff request. A request is not a domain mutation.
2. The current owner durably emits `TransferOwnership`, naming the target Device, current command heads, and successor claim.
3. The old owner stops authoring immediately after its local commit.
4. The target may author only after it has received and durably journaled the transfer.
5. Its first command references the successor claim.

The transfer itself can establish the successor. An acknowledgement improves UX but does not retroactively alter authority. If the target never receives it, the phase may temporarily stall; ordinary sync or explicit offline takeover recovers it.

### Explicit offline takeover

If the owner is unreachable, another Authorized Full Device may create `TakeOverOffline` against its last observed phase heads. The UI must state that the old Device may still be active. The new claim is **provisional**, not a hidden lease election.

If synchronization later shows no concurrent old-owner command or competing claim, the takeover becomes the uncontested causal successor. If the old owner continued from the same base, the histories are concurrent and the timer projection becomes `SETTLEMENT REQUIRED`.

A timeout, missing presence ping, closed browser, empty Nostr result, or absent Mailbox object may offer the takeover action but must never perform it automatically.

### Concurrent claims and settlement

When the materializer observes multiple live ownership branches:

- All Replicas deterministically expose the same conflicted head set.
- Destructive and sequence-advancing effects are frozen in the canonical projection.
- No new normal control command may pretend the conflict is settled.
- Each branch remains inspectable, including its commands, elapsed evidence, terminal facts, and notifications already emitted.
- A stable branch may be shown as a **preview**, but must be labelled and must not silently affect canonical History, Stats, Crew, awards, or next-phase cadence.

An Authorized Device—or an agent acting only with explicit user confirmation—emits `ResolveTimerConflict`. It references every conflicting head and may:

- choose one branch as canonical;
- preserve a losing completed Work interval as a separate recovered session;
- explicitly compose genuinely separate focus intervals; or
- discard a branch's effect while retaining its immutable audit history.

The Resolution creates one new uncontested claim or parks the timer. It cannot delete the losing Operations. Because cross-device wall times may be wrong, Pomo must not automatically infer that two focus intervals overlap or are distinct solely from their timestamps.

### Completion and duplicate suppression

Natural expiry should be an idempotent terminal transition of a specific phase branch, not “insert another history row now.” Before any completion side effect, the owner durably records a stable completion intent derived from the Phase ID, ownership claim, and terminal command head. Retries reuse the same semantic completion key.

Consequences:

- repeated Android callbacks, Chrome alarms, restarts, network replay, and duplicate envelopes materialize one completion for that branch;
- a delayed completion under a causally obsolete claim cannot advance the accepted timer;
- completions on concurrent ownership branches remain separate authentic candidates until settlement; and
- notifications may already have occurred on multiple partitioned Devices, but duplicate notification side effects never justify duplicate canonical history.

Completed/partial work is not silently erased. Only an accepted uncontested completion—or a later Resolution—may enter canonical History and affect counts.

## Clock and lifecycle rules

### Ordering

Lamport's happened-before relation orders local events and actual message delivery without using physical clocks; concurrent events do not have a factual “first” relation.[^lamport] Pomo's causal frontier and Device feeds should therefore order ownership and commands. Wall time is metadata.

Never choose a timer branch because it has:

- the latest wall timestamp;
- the least remaining duration;
- the greatest independently minted generation;
- the lexicographically largest Device ID or Operation hash; or
- the first envelope received by a relay/Mailbox/peer.

Those rules converge, but they manufacture precedence and can hide user work.

### Countdown projection

Android documents that `System.currentTimeMillis()` may jump, while `SystemClock.elapsedRealtime()` is monotonic and includes deep sleep. The elapsed clock is measured since boot, so reboot ends that continuity.[^android-clock]

The High Resolution Time specification provides `performance.now()` as monotonic within a user-agent execution context, but directs multi-execution designs back to wall time and warns that wall time can move backward or forward.[^hr-time] Chrome extension service workers terminate when idle; ordinary timers can be cancelled. Chrome alarms do not wake a sleeping device and may be delayed arbitrarily.[^chrome-worker][^chrome-alarm]

Therefore each Device should:

- measure a running segment with its local monotonic clock while that clock's continuity is known;
- persist configured duration, remaining-at-transition, wall anchor, monotonic anchor, and a boot/runtime continuity identifier;
- compute and persist remaining duration at Pause, Resume, Extend, Handoff, Takeover, and terminal transitions;
- transfer a duration/state snapshot during handoff, not an absolute deadline as authority;
- use wall anchors for human display, restart approximation, and anomaly diagnostics only; and
- mark `TIME UNCERTAIN` when reboot/runtime loss plus clock discontinuity makes exact elapsed time unrecoverable.

Remote Replicas may extrapolate an owner's recent state for display using their own monotonic clock. That projection is never a command, completion, ownership transfer, or proof that the owner's platform executed a callback.

## Failure classification

| Scenario | Required result |
| --- | --- |
| Same command retried | Deduplicate by stable command/semantic ID. |
| Envelope arrives before feed predecessor | Pending until feed continuity is available. |
| Old-owner command causally after observed handoff | Reject; the Device knowingly used a stale claim. |
| Delayed old-owner command causally before handoff | Replay in feed order; the handoff descends from it. |
| Old owner continues concurrently with offline takeover | Preserve both; `SETTLEMENT REQUIRED`. |
| Two Devices start from the same parked phase offline | Preserve both phase/claim branches; `SETTLEMENT REQUIRED`. |
| Duplicate natural-completion callback on one branch | One semantic completion and one canonical history effect. |
| Different branches both complete | Preserve both candidates; do not double-count before settlement. |
| Wall clock jumps | Continue local monotonic segment if valid; record anomaly; never reorder Operations. |
| Device/browser restarts | Rehydrate anchors; if continuity is unknowable, approximate visibly as `TIME UNCERTAIN`. |
| Handoff committed but target never receives it | Old owner remains locally fenced; sync or explicit takeover recovers the stalled phase. |
| Concurrent Resolution Operations | Preserve as a new conflict; a later Resolution must reference all Resolution heads. |

## What Pomo should replace

The current phone-primary protocol accepts a live desk timer using “least remaining wins.” This can discard a longer genuine session, treats a projection value as ownership authority, and depends on mutable wall/remaining estimates. It should not survive into peer replication.

An older planning rule—“highest ownership generation wins, then stable Device ID”—also needs refinement. It is valid only for causally authorized succession. If disconnected Devices independently mint generations, the higher number/tie-break is an arbitrary presentation rule, not fencing. Concurrent claims must remain visible branches.

## Prototype and model-check gate

Before production implementation, a deterministic model or cross-runtime kernel prototype should cover at least:

1. simultaneous offline Start from a parked phase;
2. offline takeover while the old owner Pauses, Extends, Skips, Resets, or completes;
3. duplicate/reordered completion and command delivery;
4. a delayed old-owner command after normal handoff;
5. handoff committed but lost before the target observes it;
6. two concurrent takeovers from the same base;
7. clock jump forward/backward while running and paused;
8. Android reboot and Chrome worker/browser termination;
9. settlement choosing one branch and settlement composing recovered work;
10. concurrent settlements; and
11. restoration from a Checkpoint with unresolved timer branches.

Core properties:

- At most one **accepted uncontested** owner branch is materialized.
- Every accepted normal command descends from that branch's exact ownership claim and expected heads.
- A known-stale claim cannot mutate the canonical projection.
- Duplicate delivery has no additional semantic effect.
- Operation arrival order and wall time do not change the result.
- Every authentic concurrent branch remains recoverable and auditable.
- All Replicas with the same Operation set materialize the same uncontested state or the same conflict set.

## Sources

[^cap]: Seth Gilbert and Nancy Lynch, [“Brewer's Conjecture and the Feasibility of Consistent, Available, Partition-Tolerant Web Services”](https://www.comp.nus.edu.sg/~gilbert/pubs/BrewersConjecture-SigAct.pdf), SIGACT News, 2002.
[^raft]: Diego Ongaro and John Ousterhout, [“In Search of an Understandable Consensus Algorithm”](https://raft.github.io/raft.pdf), USENIX ATC, 2014.
[^etcd-api]: etcd, [API guarantees](https://etcd.io/docs/v3.5/learning/api_guarantees/).
[^etcd-failure]: etcd, [Failure modes](https://etcd.io/docs/v3.7/op-guide/failures/).
[^leases]: Cary Gray and David Cheriton, [“Leases: An Efficient Fault-Tolerant Mechanism for Distributed File Cache Consistency”](https://web.stanford.edu/class/cs240/readings/leases.pdf), SOSP, 1989.
[^chubby]: Mike Burrows, [“The Chubby Lock Service for Loosely-Coupled Distributed Systems”](https://research.google.com/archive/chubby-osdi06.pdf), OSDI, 2006.
[^iconfluence]: Peter Bailis et al., [“Coordination Avoidance in Database Systems”](https://arxiv.org/abs/1402.2237), PVLDB, 2014.
[^automerge]: Automerge, [Conflicts](https://automerge.org/docs/reference/documents/conflicts/).
[^couch]: Apache CouchDB, [Replication and conflict model](https://docs.couchdb.org/en/stable/replication/conflicts.html).
[^kurrent]: KurrentDB, [Appending events](https://docs.kurrent.io/clients/node/v1.1/appending-events).
[^lamport]: Leslie Lamport, [“Time, Clocks, and the Ordering of Events in a Distributed System”](https://lamport.azurewebsites.net/pubs/time-clocks.pdf), Communications of the ACM, 1978.
[^android-clock]: Android Developers, [`SystemClock`](https://developer.android.com/reference/android/os/SystemClock).
[^hr-time]: W3C, [High Resolution Time Level 3](https://www.w3.org/TR/hr-time-3/).
[^chrome-worker]: Chrome for Developers, [Migrate to a service worker](https://developer.chrome.com/docs/extensions/develop/migrate/to-service-workers).
[^chrome-alarm]: Chrome for Developers, [`chrome.alarms`](https://developer.chrome.com/docs/extensions/reference/api/alarms).
