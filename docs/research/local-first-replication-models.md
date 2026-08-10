# Proven local-first replication models for Pomo

## Question

Which proven replication models fit Pomo's peer Replicas, append-only Operation
journal, explicit deletion, offline convergence, deterministic recovery, and
small trusted device set?

This report compares invariants and failure modes. It deliberately does **not**
choose Pomo's final design or implementation library.

## Pomo's starting point

The current product is not yet peer-to-peer. Android's `PomodoroService` is the
timer write boundary, Room is canonical history, and desktop clients are not
allowed to author or reconcile history
([architecture](https://github.com/Snehit70/pomo/blob/7fc3fe49774490da62ed18d15d05321b3f87ad42/docs/architecture.md#source-of-truth)).
The Chrome extension nevertheless already has its own IndexedDB history and
timer engine; a completed block is split into calendar-day rows and committed
locally
([extension service worker](https://github.com/Snehit70/pomo/blob/7fc3fe49774490da62ed18d15d05321b3f87ad42/extension/src/background/sw.ts#L85-L128)).
Full sync therefore has to join two existing stores and engines, not add a cache
to a single authority.

Pomo has also tried a much more fragile shape before. The Android sync manager
posted a whole mutable `TimerState` plus an `offline_since` timestamp and accepted
one merged state in return
([removed `SyncManager`](https://github.com/Snehit70/pomo/blob/61aafc8575e64c74284a9b25cfbbcf3d006b01fa/app/src/main/java/com/pomoremote/timer/SyncManager.kt#L31-L70)).
History sync pushed rows marked `synced`, fetched a server-shaped snapshot,
fuzzy-deduplicated it, then replaced all local history
([removed repository](https://github.com/Snehit70/pomo/blob/61aafc8575e64c74284a9b25cfbbcf3d006b01fa/app/src/main/java/com/pomoremote/db/HistoryCacheRepository.kt#L112-L160),
[replacement path](https://github.com/Snehit70/pomo/blob/61aafc8575e64c74284a9b25cfbbcf3d006b01fa/app/src/main/java/com/pomoremote/db/HistoryCacheRepository.kt#L190-L275)).
Commit [`63493cf`](https://github.com/Snehit70/pomo/commit/63493cf) removed that
system when the phone became canonical. This history explains two non-negotiable
requirements for the new design: absence must never imply deletion, and syncing
must exchange identifiable immutable facts rather than overwrite whole current
state.

The present Room DAO still exposes destructive `replaceAllHistory` and
`replaceDayHistory` transactions
([`HistoryDao`](https://github.com/Snehit70/pomo/blob/7fc3fe49774490da62ed18d15d05321b3f87ad42/app/src/main/java/com/pomo/db/HistoryDao.kt#L102-L128)).
Those are valid local maintenance operations, but a future replicator must not
interpret them as permission to manufacture remote deletion Operations.

## Evaluation lens

For each model, the relevant questions are:

1. What immutable unit is exchanged?
2. How does a replica prove what it has and request what it lacks?
3. What happens to concurrent updates and deletes?
4. What survives compaction, corruption, or loss of local metadata?
5. Is transport separate from replication semantics?
6. Which application invariants remain Pomo's responsibility?

## Comparison at a glance

| Model | Replication unit and progress | Concurrent writes | Deletion and recovery | Main lesson for Pomo | Main mismatch |
| --- | --- | --- | --- | --- | --- |
| Automerge | Content-addressed CRDT changes; document heads summarize the change graph | Deterministic merge; same-field losers remain inspectable conflicts | Deletes are operations in document history; compacted snapshots safely subsume known changes | Strong reference for idempotent, order-independent convergence and head-keyed compaction | Generic JSON merge rules do not encode timer ownership, authorization, anomaly quarantine, or Pomo-specific restore semantics |
| CouchDB/PouchDB | Per-document revision trees plus a changes feed and pairwise replication checkpoints | Deterministic winning leaf while conflicting leaves remain available for application resolution | Explicit `_deleted` tombstones replicate; compaction discards old bodies and retains limited ancestry metadata | Strong reference for resumable replication, explicit tombstones, and modeling each Operation as an immutable document | Mutable domain documents still need conflict sweeps; revision history is not a permanent audit log |
| Syncthing | Per-item version vectors, device-local sequence numbers, index incarnation IDs, and hashed content blocks | Detects concurrent file versions and preserves a conflict copy | `deleted` is distinct from `invalid`/unavailable; optional versioning archives remotely replaced data | Strong reference for distinguishing storage loss from intent, detecting index resets, and making health visible | File-level winners are not domain reconciliation; its archive does not protect local overwrites and must not be used to sync live SQLite files |
| Hypercore + Autobase | Signed Merkle append-only logs per writer; causal DAG linearized into deterministic derived views | Each writer appends independently; causal forks are normal and views may reorder until a signed checkpoint | History is verifiable, but writers can truncate/fork and application code defines view/recovery semantics | Closest reference for signed per-device journals, provenance, and replayable materialized views | Membership, revocation, safe truncation, checkpoint availability, Android/browser portability, and all domain conflict rules remain substantial work |
| Git | Immutable content-addressed objects, parent-linked commits, movable refs, local reflogs | Three-way merges; unresolved content conflicts stop for human resolution | Old states are recoverable only while objects remain retained/reachable; reflogs expire | Excellent UX and provenance metaphor for inspect/compare/restore | File snapshots, manual merges, mutable refs, and checkout/rewind are the wrong runtime semantics for Pomo |

## Model 1: Automerge change graph and CRDT materialization

### Invariants

Automerge documents carry their history. It treats concurrent versions as sets
of changes after a common ancestor and merges maps, lists, text, and counters by
datatype-specific rules
([merge rules](https://automerge.org/docs/reference/under-the-hood/merge-rules/)).
Each serialized change records hashes of its dependencies and is itself named by
a SHA-256 change hash
([binary format](https://automerge.org/automerge-binary-format-spec/#change-chunk)).
Concurrent writes to different map keys combine; concurrent writes to the same
property get a deterministic winner, while every concurrent value remains
available through the conflicts API. The winner is based on operation IDs, not
wall-clock time
([conflicts](https://automerge.org/docs/reference/documents/conflicts/)).

Automerge Repo stores incremental changes independently, periodically compacts
loaded changes into snapshots, and keys a snapshot by the document heads. A
compactor deletes only changes it had loaded and included, so a concurrent
writer cannot be erased accidentally
([storage model](https://automerge.org/docs/reference/under-the-hood/storage/#the-storage-model)).
Storage and networking are separate adapters, and a repo may have multiple
network adapters or none
([networking](https://automerge.org/docs/reference/repositories/networking/)).

### Fit and failure modes

This is a strong fit for Pomo's desired convergence properties: independent
concurrent edits merge without one replica becoming a permanent authority, and
heads provide a compact statement of causal state. Its storage-compaction rule
is especially relevant to safe Pomo checkpoints: never remove an Operation
unless the checkpoint identity proves that exact Operation was incorporated.

Automerge does not make arbitrary domain invariants safe. Its documented
delete-versus-update rule makes the concurrent update win
([merge rules](https://automerge.org/docs/reference/under-the-hood/merge-rules/#map-merge-rules));
that may be correct for a collaborative field but wrong for revocation, timer
ownership, or deletion quarantine. A deterministic same-field winner also means
convergence, not correctness. Pomo would still need validation, authenticated
Device Identity, authorization epochs, suspicious-change containment, and a
domain-aware materializer.

A single giant member document would also enlarge the blast radius of a bad
change and couple unrelated histories. If Automerge is later prototyped, the
document boundary itself is a first-class design decision (for example, journal
segments or record families rather than one mutable world object).

## Model 2: CouchDB/PouchDB revision-tree replication

### Invariants

CouchDB replication is a directed source-to-target process over JSON documents.
Its changes feed includes create, update, and delete events; pairwise replication
logs hold checkpoints; peers compare checkpoint ancestry and fall back to full
replication when no common ancestry exists
([protocol definitions and algorithm](https://docs.couchdb.org/en/stable/replication/protocol.html)).
The protocol explicitly expects unstable networks, retries, incomplete data, and
malformed responses
([protocol robustness](https://docs.couchdb.org/en/stable/replication/protocol.html#protocol-robustness)).
Bidirectional sync is two one-way replications; PouchDB exposes that directly and
supports live retry with backoff
([PouchDB replication API](https://pouchdb.com/api.html#replication)).

Concurrent document edits form multiple leaf revisions. Every replica chooses
the same winning leaf, but losing leaves remain queryable until the application
resolves them
([CouchDB conflicts](https://docs.couchdb.org/en/stable/replication/conflicts.html)).
Deletion writes a `_deleted` tombstone at the tip of a revision tree rather than
making a document disappear
([PouchDB deletion model](https://pouchdb.com/guides/updating-deleting.html#deleting-documents)).

### Fit and failure modes

The best Pomo-shaped variant is PouchDB's documented “every doc is a delta”
strategy: create an immutable document for every operation rather than repeatedly
editing one current-state document
([conflict guide](https://pouchdb.com/guides/conflicts.html#accountants-dont-use-erasers)).
That removes most revision-tree conflicts at the replication layer and lets a
Pomo materializer decide the domain result. Checkpoint ancestry plus full-sync
fallback is a mature pattern for a lost or reset replica index.

Revision trees are **not** permanent user-facing history. CouchDB compaction
replaces old revision bodies with limited tombstone metadata, governed by
`_revs_limit`
([compaction](https://docs.couchdb.org/en/stable/maintenance/compaction.html)).
Therefore Pomo's recovery journal cannot be “whatever CouchDB revisions happen
to remain.” Each durable Operation and recovery checkpoint must itself be an
application record with an explicit retention policy.

The model also does not provide Pomo's end-to-end encryption or trusted-device
authorization. A remote CouchDB would become an operational authority unless it
stored only opaque authenticated ciphertext, and native Android integration
would differ substantially from browser PouchDB. Those are later technology
questions, not replication invariants.

## Model 3: Syncthing version-vector file reconciliation

### Invariants

Syncthing's Block Exchange Protocol advertises an index and incremental index
updates. Every file entry has a version vector, a device-local monotonic
sequence, an explicit `deleted` bit, and a separate `invalid` bit for content
that is temporarily unavailable
([BEP index fields](https://docs.syncthing.net/v2.1.0/specs/bep-v1.html#index-and-index-update)).
Each local index also has a random index ID. If the index is reset or removed,
the device generates a new ID rather than pretending that sequence zero follows
the old index; peers use `(index ID, maximum sequence)` for safe delta exchange
([delta index exchange](https://docs.syncthing.net/v2.1.0/specs/bep-v1.html#delta-index-exchange)).

Concurrent file updates create conflict copies rather than silently dropping one
version. A modification concurrently racing with a deletion resurrects the
modified file
([conflict behavior](https://docs.syncthing.net/users/faq.html#what-if-there-is-a-conflict)).
Optional file versioning archives a file before a remote replacement or deletion,
but it is disabled by default and cannot archive a version overwritten locally
([file versioning](https://docs.syncthing.net/users/versioning.html)).

### Fit and failure modes

Syncthing supplies the clearest answer to the motivating failure case. “I cannot
read my local store” must be represented as invalid/unavailable or as a new
replica/index incarnation; it is never equivalent to an authenticated deletion.
Pomo should similarly bind progress to a `replicaIncarnationId` plus monotonic
local sequence. If Chrome storage is cleared, the new empty installation must
rehydrate from peers and cannot claim that its empty projection is newer than
the previous incarnation.

Syncthing is not an appropriate Pomo engine. File conflict copies have no idea
whether two Work blocks should union, a setting should resolve deterministically,
or a timer takeover is valid. Its optional archive is incomplete protection
because local overwrites are outside its scope. Directly syncing Room's SQLite
file would be unsafe: SQLite documents that copying a live database without its
journal/WAL can yield an inconsistent or corrupt copy and prescribes the backup
API or `VACUUM INTO` for consistent snapshots
([SQLite corruption guidance](https://sqlite.org/howtocorrupt.html#_backup_or_restore_while_a_transaction_is_active)).
Pomo must replicate logical Operations, never live database files.

## Model 4: Hypercore per-writer logs plus Autobase views

### Invariants

Hypercore is a signed-Merkle, sparsely replicated log. Its stated focus is
securely distributing a stream, and a reader can verify content against the
writer's key
([Hypercore README](https://github.com/holepunchto/hypercore#hypercore)).
This naturally maps one Authorized device to one authenticated writer feed.

Autobase combines multiple writer cores using event sourcing. Writer entries
form a causal DAG, nodes never precede nodes they reference, and ordering is
eventually consistent. A deterministic application function builds a combined
view; new causal information may temporarily reorder and reapply view entries.
Signed checkpoints eventually establish a prefix whose ordering cannot change
([Autobase ordering and views](https://github.com/holepunchto/autobase#ordering)).
Writer membership is explicit through `addWriter` and `removeWriter`
([Autobase host calls](https://github.com/holepunchto/autobase#autobasehostcalls)).

### Fit and failure modes

This is the closest existing model to Pomo's proposed signed Operation journal,
periodic checkpoints, and rebuilt materialized state. Per-device feeds make
attribution straightforward, prevent one writer from forging another writer's
sequence, and avoid a single multi-writer append race. The view function is the
natural home for deterministic history union and settings resolution.

“Append-only” still needs a precise threat model. Hypercore 10 explicitly allows
an authorized writer to truncate its feed and advances a fork ID
([truncate API](https://github.com/holepunchto/hypercore#await-coretruncatenewlength-options)).
Pomo would have to decide whether any Device is allowed to fork, how peers retain
the abandoned branch for recovery, and whether a suspicious truncation is
quarantined. Autobase checkpoints require a majority of indexers to keep
advancing, which may be awkward for two devices when one is offline; that is a
product availability trade-off, not an implementation detail.

Hypercore/Autobase also supplies infrastructure rather than Pomo semantics.
Revocation timing, writes concurrent with revocation, deletion rules, timer
ownership, encryption-key rotation, anomalous bulk changes, checkpoint retention,
and selective restore remain application decisions. Platform portability across
Android/Kotlin and a Manifest V3 browser extension also requires a focused
prototype before treating this stack as viable.

## Git: adopt the recovery language, not the runtime

Git is useful because its immutable objects have content-derived IDs, commits
point to parent commits, and refs name graph tips
([Git data model](https://git-scm.com/docs/gitdatamodel.html)). This is an
excellent provenance model for Pomo's hidden Data History console: inspect a
point, compare it with current state, identify divergence, and prepare a restore.

It is not a suitable runtime sync algorithm. Git snapshots files, performs
three-way content merges, and stops for manual resolution when it cannot merge.
Its refs are movable, while Pomo's ordinary recovery should append compensating
Operations rather than move every replica backward. Git recovery is also
retention-dependent: reflogs are local and, by default, expire reachable entries
after 90 days and unreachable entries after 30 days
([reflog expiry](https://git-scm.com/docs/git-reflog#Documentation/git-reflog.txt---expireltimegt)).
Pomo must make retention a product guarantee instead of inheriting Git-like
garbage-collection assumptions.

## Cross-model findings

### 1. Missing state is never a replicated instruction

All suitable models exchange positive knowledge: Automerge changes, CouchDB
revisions/tombstones, Syncthing index entries with explicit status, or signed log
blocks. Pomo should never diff two materialized databases and infer deletes from
missing rows. Only a valid, authorized `Delete*` Operation can delete shared data.

Consequently, a storage reset needs its own state transition:

```text
known replica incarnation disappears
        -> create a new local incarnation
        -> mark projection incomplete
        -> fetch and verify journal/checkpoint
        -> materialize
        -> only then permit new replicated writes
```

### 2. Convergence and correctness are separate layers

CRDT winners, CouchDB winning leaves, Syncthing global files, and Autobase
linearization can all converge deterministically while producing a result that
violates user intent. Pomo's domain layer must independently validate:

- Device authorization and revocation epoch;
- Operation schema and causal prerequisites;
- immutable Work-block identity and idempotency;
- active-timer lineage, generation, revision, and ownership transfer;
- explicit deletion and restoration;
- anomaly thresholds and quarantine;
- invariants for settings, Identity, memberships, and achievements.

The active timer should therefore not be modeled as an ordinary last-writer-wins
field. Replication can deliver timer Operations, but Pomo must decide which
lineage owns the displayed timer.

### 3. The durable journal and materialized stores have different jobs

Room and IndexedDB should remain fast local projections. The replicated journal
is the source for provenance and rebuild; checkpoints bound replay time. A
checkpoint may replace earlier Operations for normal reconstruction only after
its manifest proves exactly which causal frontier it includes. Recovery retention
may keep older journal segments or checkpoints longer than the fast path needs.

This separation also prevents a projection bug—an empty query, corrupt index, or
failed migration—from becoming a replicated destructive event.

### 4. Recovery is a new forward change

Git-like checkout is useful for preview, but the shared result should follow the
ledger model: inspect a historical checkpoint, calculate a domain-aware diff,
and append compensating restore Operations. That preserves newer unrelated work,
keeps the audit trail, and lets offline replicas converge without a global
rewind.

### 5. Transport must remain replaceable

Automerge explicitly separates network adapters, CouchDB's replication algorithm
runs over ordinary HTTP, Syncthing separates index semantics from connection
discovery, and Hypercore exposes replication streams. Pomo should likewise make
LAN and encrypted internet store-and-forward transports carry the same immutable
Operation envelopes. A transport acknowledges durable possession; it never
decides conflicts, timer ownership, or deletion.

### 6. Small trusted sets simplify scale, not failure semantics

Two or three Authorized devices make full-journal anti-entropy and redundant
checkpoints affordable. They do not remove partitions, concurrent writes,
revocation races, device loss, storage reset, or corrupted projections. A
two-device set also makes majority-based “stable prefix” mechanisms less
available while one device is offline, so Pomo should not accidentally require
quorum merely to record a local Work block.

## Decisions this research unlocks

The comparison narrows the next Wayfinder decisions without selecting a winner:

1. Define the Operation envelope and causal frontier: per-device sequence,
   replica incarnation, dependencies, signature, authorization epoch, and
   payload hash.
2. Choose journal topology: one causal change graph, immutable operation
   documents, or one signed feed per Authorized device with a deterministic
   combined view.
3. Define explicit deletion, restoration, revocation, and timer-ownership
   semantics above the replication layer.
4. Define checkpoint manifests and separate fast-path compaction from recovery
   retention.
5. Define reset/corruption detection and rehydration before any empty replica can
   publish.
6. Prototype the two most plausible engines on **both** Android and Manifest V3
   Chrome, including service-worker suspension, before making a library choice.

## Bottom line

No examined system is a drop-in answer. The proven pattern is a composition:

```text
authenticated immutable facts
        + causal progress / anti-entropy
        + explicit tombstones
        + deterministic domain materializer
        + safe checkpoints and retained recovery history
        + transport-independent delivery
```

Automerge demonstrates convergent change graphs and safe compaction; CouchDB
demonstrates resumable revision transfer and tombstones; Syncthing demonstrates
replica-incarnation and unavailable-versus-deleted safety; Hypercore/Autobase
demonstrates signed per-writer provenance and replayable views; Git demonstrates
the recovery language users already understand. Pomo can borrow those invariants
without inheriting any system's mismatched conflict policy.
