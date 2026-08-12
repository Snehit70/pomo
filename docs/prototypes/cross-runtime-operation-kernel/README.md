# Cross-runtime Operation kernel — throwaway prototype

This bundle answers one Wayfinder question: can Kotlin and TypeScript share a
small Operation-kernel interface and still make identical decisions about
canonical identity, causal delivery, Device-feed forks, Checkpoint restore, and
incomplete-Replica authoring?

It is planning evidence, not production sync code. It does not touch Room,
IndexedDB, transport, Android services, the Chrome service worker, NodeMCU, or
physical-device behavior.

## Result

Yes, with one important seam: both runtimes must implement the same deep
`OperationKernel` module with only four caller-facing operations:

- `author(kind, context)` applies explicit Authoring prerequisites.
- `ingest(operation)` owns validation ordering, deduplication, Feed continuity,
  causal readiness, gap handling, fork quarantine, and exactly-once acceptance.
- `summarize()` exposes transport-independent Feed heads, gaps, causal waits,
  forks, and disposition counts.
- `restore(checkpoint, trailing)` stages a verified multi-feed frontier and then
  replays later Operations through the same ingestion path.

The prototype discovered that a Checkpoint cannot provide only one Feed head.
It must expose the covered Operation identity at every indexed Feed position in
its verified Journal packs. Otherwise an old duplicate and a new fork inside the
checkpointed prefix are indistinguishable.

It also confirmed that Feed continuity and causal readiness are separate. An
Operation may be next in its own Feed but remain `PENDING_CAUSAL` until another
Device Feed reaches the exact dependency named by its Causal frontier.

## Evidence

- [`typescript/operation-kernel.ts`](typescript/operation-kernel.ts) is the
  Chrome-side executable sketch.
- [`kotlin/OperationKernel.kt`](kotlin/OperationKernel.kt) is the Android-side
  executable sketch.
- [`golden-output.txt`](golden-output.txt) is the complete expected output.
- [`operation-kernel-prototype.html`](operation-kernel-prototype.html) explains
  the same cases as a non-technical guided walkthrough.

Both executable sketches emitted byte-for-byte identical 15-line output in the
prototype environment. This includes identical deterministic CBOR and Operation
ID bytes plus identical results for invalid identity, reordered and duplicate
delivery, cross-feed causal waiting, Feed forks, multi-feed Checkpoint restore,
tampered Checkpoints, and incomplete authoring.

The TypeScript sketch runs with:

```bash
bun docs/prototypes/cross-runtime-operation-kernel/typescript/operation-kernel.ts
```

The Kotlin file is standalone Kotlin/JVM source. It was compiled with the
repository's cached Kotlin 1.9.24 compiler and JDK 17, then executed directly.

## Deliberate limits

The prototype uses fixture booleans at the cryptographic verification seam. It
does not claim production POMO-SUITE-1 signature, COSE, HPKE, Argon2id, parser
fuzzing, Keystore/WebCrypto lifecycle, or durable database proof. Those remain
mandatory cross-runtime golden/negative corpus and fault-injection work for the
verification and implementation handoff. The production kernel must accept raw
authenticated bytes through an injected verifier, never trust transport claims.

The prototype also does not materialize Pomo domain projections. A versioned
Materializer consumes only `ACCEPTED` Operations after the kernel; pending,
quarantined, and rejected Operations never affect ordinary state.
