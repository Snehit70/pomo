# Active phase ownership and timer settlement prototype

Throwaway planning evidence for [Model-check Active phase ownership and timer settlement](https://github.com/Snehit70/pomo/issues/100).

Open [`timer-settlement-prototype.html`](./timer-settlement-prototype.html) directly in a browser. It has no server, dependencies, persistence, production storage, or production cryptography.

## Question settled

A deterministic timer Materializer can converge equal Android and Chrome Operation sets without silently losing an authentic branch when:

- ownership is causal and scoped to one identified Active phase;
- every Timer command names its exact ownership claim and parent command head;
- late arrival is distinguished from knowingly stale authorship;
- independent offline claims and terminal outcomes become visible conflict heads;
- duplicate stable Operation IDs have one effect;
- missing causal prerequisites remain Pending;
- wall-clock and lifecycle evidence never chooses authority;
- Timer settlement references every known conflicting head; and
- Checkpoint restoration retains the complete unresolved head set.

The member accepted this behavior on 2026-08-12.

## Scenarios exercised

1. Simultaneous offline Start from one Parked phase.
2. Normal Handoff, replayed delivery, and a knowingly stale old-owner command.
3. Provisional takeover concurrent with Pause, Resume, Extend, Skip, Reset, and Completion.
4. Duplicate, missing, reordered, and replayed commands and Completion.
5. Wall-clock jump, Android reboot, and Chrome runtime loss.
6. One-branch selection and recovered-focus composition.
7. Concurrent incompatible Timer settlements.
8. Checkpoint restoration with unresolved timer heads.

## Executable evidence

The prototype enumerates all delivery permutations for each selected Operation set and compares normalized materializations.

| Scenario | Unique delivery orders | Final normalized outcomes | Expected heads |
| --- | ---: | ---: | --- |
| Offline starts | 2 | 1 | both Start heads |
| Handoff and stale command | 120 | 1 | Chrome Resume only; stale Extend rejected |
| Each takeover race | 6 | 1 | takeover and concurrent owner-action heads |
| Delivery faults | 24 | 1 | one semantic Completion |
| Clock and lifecycle | 6 | 1 | confirmed time-uncertain takeover |
| Composed settlement | 120 | 1 | one Resolution head |
| Concurrent settlements | 24 | 1 | both Resolution heads |
| Checkpoint restore | 6 | 1 | restored Chrome head and trailing Android head |

Replaying the full set produces the same normalized projection in every scenario. A knowingly superseded ownership claim cannot advance a head. Every reported conflict head remains backed by an accepted immutable Operation.

## Important interpretation

Delivery delay alone does not make an Operation stale. An old-owner command authored before learning of a Handoff or takeover is authentic concurrent evidence and remains recoverable. A command authored with causal knowledge that its claim was superseded is rejected from the canonical projection while remaining in the immutable audit record.

## Boundary

This is planning evidence, not a production timer kernel. It does not validate signatures, canonical encoding, Room or IndexedDB transactions, transport behavior, crash atomicity, cues, Android scheduling, Chrome lifecycle delivery, NodeMCU, or physical-device behavior.
