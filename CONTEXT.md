# Pomo

Glossary for the Pomo focus-timer domain. Authorized devices hold peer Replicas
of durable synchronized data, while one temporary Active timer owner controls an
Active phase. This file defines the language we use to talk about the product; it
is not a spec and carries no implementation detail.

## Language

**Work block**:
One continuous run of the focus phase. It is *completed* when it runs to its
scheduled end (including any add-time); it is *partial* when Skip ends it early.
Reset abandons a block and creates no History record, although its Timer command
remains in the Operation journal so every Replica terminates the phase.
Historically a fixed length (e.g. 25 min); once add-time exists it is
variable-length. The unit that history rows and the long-break cadence count.
_Avoid_: session (ambiguous — see below), pomodoro, interval.

**Session**:
Informal synonym for a Work block. Prefer "Work block" when precision matters,
because "session" is also used loosely for "a stretch of using the app".

**Focus minutes**:
Total time actually spent in the focus phase, summed across Work blocks —
including the partial time of a block ended by Skip, and excluding any block
abandoned by Reset. Time-honest: it measures real focus time, independent of
whether a block was completed. The **headline metric** — what Stats lead with
and what any ranking sorts on.
_Avoid_: focus time when a unit is needed, productivity, score.

**Cadence count**:
The running count of completed Work blocks in the current cycle. Drives the
long-break trigger (`longBreakAfter`) and the launch pips. Only completed blocks
increment it; partial (skipped) blocks contribute Focus minutes but never the
count. Kept for cadence even though Focus minutes is the headline metric.
_Avoid_: completed, session count (when it could mean the headline metric).

**Daily goal**:
A target expressed as a count of completed Work blocks per local calendar day.
Remains count-based even though Focus minutes is the headline metric elsewhere.

**History block**:
The durable account of one ended Work or Break phase, identified independently
of its timestamp and preserving its Phase, outcome, monotonic elapsed duration,
tag, time confidence, and factual local-date allocation. Daily totals are
projections of History blocks.
_Avoid_: session row, daily total, start timestamp.

**Initial-merge conflict**:
Two legacy History accounts that claim the same start instant and phase type but
disagree in content. Both remain preserved until the member resolves whether
they describe one corrected block or separate work.
_Avoid_: duplicate row, local winner, import error.

**History correction**:
An attributable amendment to a History block that preserves both its original
account and the correcting intent. It never rewrites the original Operation.
_Avoid_: row update, in-place edit, corrected session.

**History tombstone**:
An explicit Synchronized deletion targeting one History block. A concurrent
correction prevents automatic disappearance until the member settles the conflict.
_Avoid_: missing row, hard delete, empty history.

**History settlement**:
A member-confirmed resolution of concurrent corrections or deletion intent for
one History block. It chooses the materialized account while preserving every
referenced alternative in Data History.
_Avoid_: latest edit, overwrite, conflict winner.

## Session Tags

**Session tag**:
A durable member-defined classification with a stable Tag ID, mutable name, and
stable palette slot. Every new Work block carries one; its Tag ID and authored
name are locked for the duration of the Work phase.
_Avoid_: status tag, label, category, topic.

**Tag ID**:
The permanent identity of a Session tag across renames and archival. History may
group by this identity while retaining the name authored on each Work block.
_Avoid_: tag name, color slot, sort position.

**Work tag**:
The reserved Session tag established with the Member Identity and always
available as a safe Default tag. It cannot be archived or merged away.
_Avoid_: untagged, synthetic tag, device default.

**Archived tag**:
A Session tag removed from future selection while remaining meaningful to
existing History blocks. Archival never deletes or relabels History.
_Avoid_: deleted tag, removed category.

**Tag merge**:
An explicit declaration that separately created Tag IDs represent one category.
Their authored identities and names remain in History while future grouping uses
the selected canonical tag.
_Avoid_: rename, duplicate-name deletion, row rewrite.

**Default tag**:
The shared Session tag pre-selected for the next Work block. It starts as "Work"
and changes only through member intent; archiving it selects a valid replacement.
_Avoid_: auto-tag, preselected tag, last tag.

**Active tag**:
The Session tag fixed in the current Work phase's Phase plan. Before a Work
phase starts it follows the Default tag; after start, later tag changes do not
alter it.
_Avoid_: current tag, running tag.

**Tag palette**:
A set of categorical data colors used to distinguish Session tags in Stats.
It is separate from Pomo's signal accent and state colors; a tag's color should
remain stable across Stats windows and display surfaces.
_Avoid_: theme accent, status color, tag meaning.

## Cues

**Completion cue**:
The sound and/or vibration Pomo emits the moment a phase ends and the timer
parks at the next phase. It is a notice, not a transition: the timer never
auto-advances, so the cue only tells the member a phase finished. Only the
Active timer owner emits it; mirroring devices never replay it from synchronized
history. It has two modes — one-shot and Ring.
_Avoid_: alarm (reserve for the Ring mode), beep, chime, alert.

**One-shot** (cue mode):
The default Completion-cue mode: a single brief sound plus one haptic, then
silence. Honours the phone's sound mode.

**Ring** (cue mode):
An opt-in Completion-cue mode that loops sound and vibration until the member
acknowledges it or a one-minute cap elapses, then self-silences. Applies to all
three phase completions. Audio honours the phone's sound mode (silent/DND stay
quiet; vibration still fires). This is the only context where "alarm" is apt.
_Avoid_: alarm clock, snooze (there is no snooze; the cap just silences).

**Acknowledge / Dismiss**:
Any act that silences a ringing cue — a Dismiss control or any timer command
(Start, Skip, Reset, Add-time). Dismiss only silences; it leaves the timer
parked at the next phase. The one-minute cap silences without acknowledgement.
_Avoid_: snooze, stop alarm, cancel.

## Leaderboard

**Crew**:
A group of people who compare focus stats. There is no server and no admin; a
Crew is just everyone who holds the same join code. Members rank against each
other on Focus minutes.
_Avoid_: server, room, group, channel, lobby.

**Join code**:
The single string that defines and grants entry to a Crew. Carries the Crew id,
its immutable human-readable name, the relay list, and the shared encryption
key. Holding it is the only thing that makes you a member; there is no approval
step.
_Avoid_: server code, invite link, password.

**Crew name**:
The human-readable label chosen when a Crew is created and fixed by its Join
code. It identifies the Crew in product UI but is not its protocol identity.
_Avoid_: Crew id, channel name, mutable title.

**Profile**:
A member-owned identity surface that can carry durable self-described fields
beyond the Display name, including an optional compressed profile photo. A Profile
belongs to one Member Identity, not to a Pomo account, Device Identity, or Crew.
_Avoid_: account, login, persona.

**Profile update pending**:
The state in which a Profile change is known but its content-addressed photo is
not yet available and verified. The last complete Profile remains visible;
missing content never means removal.
_Avoid_: cleared photo, broken Profile, synchronized deletion.

**Crew pseudonym**:
A stable identity used by one member within one Crew and shared only with that
member's Authorized devices. It signs Crew publications without exposing the
Member ID as a cross-Crew identifier.
_Avoid_: Member ID, Device Identity, Display name.

**Member Identity**:
The durable identity of one Pomo member across all of their Authorized devices.
It is named by a stable Member ID and does not depend on a unique username or
Pomo account.
_Avoid_: account, user, username, master device.

**Member ID**:
The permanent content-derived identifier of a Member Identity's Genesis record.
It remains stable when devices or Recovery authorities change.
_Avoid_: username, Device Identity, recovery-key fingerprint.

**Genesis record**:
The immutable origin of a Member Identity. It establishes the first Recovery
authority and first Device Identity; later authority changes extend rather than
replace it.
_Avoid_: account creation, current device list, Recovery file.

**Device Identity**:
The independent cryptographic identity of one Pomo installation authorized under
a Member Identity. It signs that device's activity, identifies where synchronized
changes originated, and can be revoked without replacing the member. Its private
keys are generated and retained by that installation: they are never backed up,
transferred, or silently recreated. If those keys become unavailable, the old
Device Identity ends and a newly generated Device Identity must be admitted.
Key rollover follows the same replacement path: fresh keys always create a fresh
Device Identity and Device feed, while a human-facing device name may be reused
only as descriptive metadata.
_Avoid_: Member Identity, pairing token, device name.

**Device certificate**:
The public cryptographic description of a Device Identity, including its distinct
signing and agreement authorities. Its content-derived identity is what Device
admission authorizes.
_Avoid_: pairing token, device name, Recovery file.

**Device signing key**:
The private authority a Device Identity uses to sign its attributable activity.
It remains local to that installation and is never shared with another device.
_Avoid_: Device agreement key, Recovery authority.

**Device agreement key**:
The private authority a Device Identity uses to receive synchronization secrets
wrapped specifically for it. It remains local to that installation and does not
sign Operations.
_Avoid_: Device signing key, shared content key.

**Content epoch**:
A generation of member-private synchronization authority with a fresh shared
secret wrapped independently to each currently Authorized device and Recovery
authority. Revocation begins a new epoch; possession of an older epoch never
grants access to future data. Concurrent offline authorization branches may
temporarily produce overlapping epochs. Devices use the newest epoch valid in
their causal view; after reconciliation, a convergence epoch targets the final
authorized set without invalidating historical ciphertext.
_Avoid_: login session, key version, Crew shared key.

**Cryptographic suite**:
An indivisible versioned set of signing, agreement, encryption, derivation,
hashing, password protection, and canonical-encoding rules for synchronized
objects. A suite identifier permits no algorithm substitution or fallback.
_Avoid_: cipher choice, negotiated algorithms, platform crypto defaults.

**Suite generation**:
The causal succession position of the Cryptographic suite authorized for new
Pomo objects. Historical suites remain readable, while concurrent adoption or
use beyond an activation frontier is quarantined rather than ranked by strength.
_Avoid_: app version, preferred cipher, connection negotiation.

**Causal revocation**:
A device revocation interpreted against causal knowledge rather than arrival time.
Earlier activity remains eligible, activity knowingly authored after revocation
is rejected, and concurrent activity is quarantined for explicit resolution.
_Avoid_: remote wipe, latest-timestamp revocation, retroactive deletion.

**Authorized device**:
A Pomo installation whose Device Identity is trusted by a Member Identity. It
may hold and synchronize a Replica; authorization can later be revoked.
_Avoid_: logged-in device, paired client, master device.

**Full device**:
An Authorized Android or Chrome Replica with equal day-to-day authority to read,
author, admit and revoke devices, and participate in recovery. No Full device is
permanently primary.
_Avoid_: admin device, primary phone, owner device.

**Dormant device**:
An Authorized device that has not acknowledged synchronization for an extended
period. Dormancy is a visible reachability condition, never automatic revocation;
the member must explicitly keep or revoke the device.
_Avoid_: revoked device, inactive member, offline session.

**Device admission**:
The explicit ceremony in which an Authorized device verifies a new Device
Identity through a short-lived in-person exchange and records its authority to
act for the same Member Identity. Pairing data proves only the ceremony and is
discarded afterward.
_Avoid_: login, permanent pairing token, account sign-in.

**Authorization ledger**:
The causally interpreted history of Device admissions, revocations, and Recovery-
authority changes from which the current trusted-device set is derived. It
preserves admission lineage and never uses timestamps as authority.
_Avoid_: device list, access-control database, latest settings row.

**Recovery required**:
The Member-Identity state in which no Full device can safely exercise ordinary
authority and current Recovery authority must re-establish access. Known data
remains preserved while ordinary authoring is unavailable.
_Avoid_: signed out, data loss, offline.

**Recovery reset**:
The forward authority transition through which current Recovery authority admits
a fresh Full device, retires every previous device, advances authorization and
Content epochs, and rotates Recovery authority without rewriting history.
_Avoid_: database restore, identity replacement, historical checkout.

**Provisional recovery**:
A restricted recovery state used when current authorization state cannot yet be
verified. Locally recoverable data may be inspected, but the reset is not final
and destructive or externally authoritative changes remain unavailable.
_Avoid_: recovered, offline mode, new Member Identity.

**Replica**:
The complete local copy of a member's synchronized durable Pomo data held by an
Authorized device. Replicas are peers; none is a permanent source of truth.
_Avoid_: cache, backup, master copy, secondary copy.

**Active timer owner**:
The Authorized device temporarily entitled to author commands for one Active
phase. Ownership belongs to that identified phase and ownership term, not
permanently to the phone, browser, or member's timer. Cue responsibility follows
ownership.
_Avoid_: master device, source of truth, primary device.

**Active phase**:
One identified running or paused Work, Short-break, or Long-break phase together
with its current Active timer owner and ownership term. Starting a parked phase
creates it; Completion, Skip, or Reset terminates it and releases ownership. A
parked next phase has no owner. Partitions may expose competing branches without
making either branch disappear.
_Avoid_: Work block, timer setting, global timer row.

**Phase plan**:
The Active phase's immutable identity, type, planned duration, Work tag,
originating timezone context, and selected next phase. Handoff and takeover carry
the plan unchanged; settings affect only future phases, while Add-time explicitly
extends the active plan.
_Avoid_: timer settings, current countdown display, history row.

**Timer command**:
An owner-authored Operation that advances one Active phase from its exact current
ownership term and command head. Pause, Resume, Add-time, Skip, Reset, and
Completion are Timer commands; each is durable before it affects instruments.
Start, Handoff, Provisional takeover, and Timer settlement are the explicit
ownership-boundary Operations around that command chain.
_Avoid_: Command request, button press, timer snapshot.

**Parked phase**:
The fully planned Work, Short-break, or Long-break phase waiting to be started.
It has no owner and never starts automatically. A terminal Timer command records
which Parked phase follows so every Replica derives the same instrument state.
_Avoid_: Active phase, paused phase, next-phase setting.

**Abandoned phase**:
An Active phase terminated by Reset. It creates no Work/Break History record, but
the terminating Timer command remains durable so no Replica mistakes the phase
for still active.
_Avoid_: deleted Work block, partial Work block, missing timer state.

**Command request**:
A transient authenticated request from a non-owning Instrument asking the Active
timer owner to author a Timer command. It has no domain effect until the owner
durably accepts it and never transfers ownership by itself.
_Avoid_: Timer command, Operation, implicit handoff.

**Handoff**:
An explicit two-device transition that moves one unchanged Active phase to a new
owner and ownership term. The target accepts the exact Phase plan and command
head; the old owner remains responsible until the transition is durable. A stale
or incomplete Handoff changes nothing.
_Avoid_: sync, takeover, least-remaining merge.

**Projected expiry**:
The local display condition in which an Active phase appears to have reached zero
before its owner has durably authored Completion. Mirroring devices show that
Completion is awaiting the owner; they never manufacture history from projection
alone.
_Avoid_: Completion, stopped phase, timer failure.

**Provisional takeover**:
An explicit offline ownership claim made from a Full device's last observed Active-
phase heads when the current owner is unreachable. The device may control its
local branch immediately, but the claim becomes uncontested only after sync shows
no concurrent owner activity. Absence or timeout never creates it automatically.
_Avoid_: Handoff, lease expiry, automatic failover.

**Settlement required**:
The Active-timer projection state produced when synchronization reveals concurrent
ownership or terminal branches. Every authentic branch remains visible and
recoverable; none gains authority from timestamp, remaining duration, generation,
Device ID, Operation ID, or arrival order. Canonical timer controls, cues,
next-phase advancement, and branch History effects pause while unrelated Pomo
data remains usable.
_Avoid_: merge winner, sync failure, latest timer.

**Timer settlement**:
A member-confirmed Resolution that references every known conflicting timer head.
It may choose one branch, preserve or compose genuine focus intervals, discard a
branch's product effect while retaining its audit history, and either park the
timer or establish one new owner.
_Avoid_: automatic merge, history deletion, conflict winner.

**Provisional settlement**:
A Timer settlement authored while other Replicas may be unreachable. It becomes
uncontested only after synchronization reveals no competing Resolution; concurrent
settlements themselves require another settlement.
_Avoid_: final resolution, latest settlement, offline overwrite.

**Owner unavailable**:
The Active-phase state in which its last uncontested owner is revoked or
permanently lost. The phase remains preserved but cannot accept ordinary commands
until an Authorized Full device explicitly takes over.
_Avoid_: Settlement required, stopped timer, automatic takeover.

**Time uncertain**:
An Active-phase condition in which reboot, browser-runtime loss, or clock
discontinuity prevents exact elapsed reconstruction. Pomo shows the estimated
remaining duration and requires confirmation before takeover; clock evidence
never determines command order or ownership.
_Avoid_: paused, offline, Settlement required.

**Semantic completion**:
The single idempotent Completion fact for one Phase ID, ownership claim, and
terminal command head. Duplicate callbacks or delivery cannot create another
Work block; concurrent branch completions remain distinct candidates until
settlement.
_Avoid_: callback, History insertion, Completion cue.

## Synchronization and Recovery

**Shared durable fact**:
A member-domain fact that every Replica retains and materializes, such as a
History block, Profile change, membership intent, or Timer command.
_Avoid_: synchronized database row, cache entry, current screen state.

**Shared preference**:
Member intent that should converge across Replicas but affects future behavior
rather than rewriting existing History or an Active phase.
_Avoid_: device setting, Phase plan, latest settings object.

**Device-local state**:
A preference or observation belonging to one installation, including presentation,
hardware capability, navigation choice, and route diagnostics.
_Avoid_: unsynchronized shared preference, member setting.

**Derived projection**:
Rebuildable state calculated from accepted durable facts, including daily Stats,
Achievements, Crew rankings, and cached summaries. It is never synchronized as
an independent source of truth.
_Avoid_: shared fact, backup authority, canonical total.

**Encrypted capability**:
A secret granting narrowly scoped access to a Crew or Transport provider. It is
distributed only through protected capability flows, never as an ordinary setting.
_Avoid_: shared preference, Member Identity, authorization proof.

**Operation**:
An immutable, attributable statement made by an Authorized device about a
synchronized domain change. Replicas may accept, quarantine, or reject an
Operation, but never overwrite it; current state is derived from accepted
Operations.
_Avoid_: database row, current state, sync payload, mutable event.

**Operation journal**:
The append-only, attributable history of synchronized changes made by Authorized
devices. It preserves how Replicas reached their current state and is the basis
for convergence and recovery. Transport delay or age never expires an Operation;
its complete logical history is retained even when immutable segments are packed
or compressed.
_Avoid_: database dump, sync payload, Git repository.

**Device feed**:
The append-only, hash-linked sequence of Operations authored by one Device
Identity during one Replica incarnation. Device feeds combine causally; there is
no single global log.
_Avoid_: global sequence, server log, database transaction log.

**Feed position**:
The unique place claimed by an Operation in a Device feed, formed from its Device
Identity, Replica incarnation, and monotonic sequence. One position may contain
at most one valid Operation.
_Avoid_: timestamp, database row id, global sequence.

**Operation ID**:
The content-derived identity of an Operation's canonical signed meaning. It
makes duplicate delivery harmless and exposes different Operations claiming the
same Feed position.
_Avoid_: Feed position, timestamp, transport message id.

**Durable peer acknowledgment**:
A signed statement that an Authorized device has authenticated and durably
journaled Operations through a stated causal frontier. Network receipt, relay
acceptance, and transient in-memory processing are not acknowledgments.
_Avoid_: delivery receipt, connection success, sync cursor.

**Saved locally**:
The durability state reached when an Operation, its Device-feed head, and its
delivery obligation are atomically committed on the authoring Replica.
_Avoid_: synchronized, protected, delivered.

**Peer-redundant**:
The durability state reached when at least one other Authorized device has issued
a Durable peer acknowledgment. It does not imply Mailbox protection or permanent
availability.
_Avoid_: Protected sync, fully synced, backed up.

**Transport envelope**:
An authenticated, replay-safe carrier for Operations, feed segments, Checkpoints,
or acknowledgments. LAN, Rendezvous relays, and Mailboxes deliver the same
envelopes into one validation path; transport never chooses domain outcomes.
_Avoid_: Operation, transport-specific state, database export.

**Direct peer route**:
An authenticated live connection between simultaneously reachable Replicas. It
accelerates catch-up and live updates but supplies no offline retention and is
never required for authority or convergence through a Mailbox.
_Avoid_: Device admission, Mailbox, permanent connection.

**Relayed peer route**:
An authenticated live connection carried through a replaceable user-selected
network relay when Replicas cannot establish a Direct peer route. It accelerates
live delivery but is neither direct nor a durable Mailbox copy.
_Avoid_: Direct peer route, Rendezvous relay, Mailbox protection.

**Durable catch-up**:
The frontier exchange and replay of missing durable Operations when Replicas
reconnect. Catch-up completes before transient live-timer updates are trusted and
uses the same validation path regardless of transport.
_Avoid_: live subscription, database replacement, latest-state download.

**Causal frontier**:
A compact statement of the latest Feed positions a Replica had observed when it
authored an Operation. It expresses causal knowledge without imposing a global
order on concurrent Operations.
_Avoid_: wall-clock order, sync cursor, latest timestamp.

**Feed fork**:
Two different Operations claiming the same Feed position. The uncontested prefix
remains valid, while every divergent tail is isolated for review rather than
chosen by timestamp or arrival order.
_Avoid_: concurrent Operations, merge conflict, latest version.

**Replica incarnation**:
One continuous lifetime of a Replica's local storage, identified independently
from its Device Identity. A reset, loss, or untrusted rebuild starts a new
incarnation so missing local data cannot masquerade as newer state.
_Avoid_: app version, device session, synchronization attempt.

**Legacy archive**:
A sealed read-only safety copy of one installation's independent pre-sync data
after Migration cutover. It remains inspectable and importable but never
participates in current projections or authoring.
_Avoid_: Replica, backup, rollback database, source of truth.

**Legacy dataset**:
Independent Pomo data authored outside a Member Identity, either before an
installation migrates or by incompatible old software after Migration cutover.
It has no synchronized authority and enters only through explicit import proposals.
_Avoid_: Replica, missing feed, current local data, deletion intent.

**Import proposal**:
An attributable candidate derived from one item in a Legacy dataset during
migration. It affects no synchronized projection until classified as imported,
duplicate, conflicting, quarantined, or explicitly excluded by the member.
_Avoid_: restored row, merged data, Accepted Operation, automatic migration.

**Migration inventory**:
The complete accounting that binds every durable item in one Legacy dataset to
its Import proposal and final disposition. Migration cutover requires zero
unexplained omissions.
_Avoid_: row count, backup manifest, import summary.

**Migration cutover**:
The atomic transition through which one installation activates its verified
Operation-journal projection and permanently retires independent legacy
authoring. It occurs only from a Parked phase and never starts dual-write.
_Avoid_: database migration, sync enablement, in-place merge.

**Safe mode**:
A protective local state that freezes synchronized authoring and transport while
retaining verified inspection, diagnostics, and export. It preserves the journal
for a forward repair release and never reactivates legacy authoring.
_Avoid_: offline mode, rollback, reset sync, legacy mode.

**Checkpoint**:
A verified frontier-bound representation of synchronized state derived from the
Operation journal and linked to the journal packs and blobs that prove it. It
accelerates rehydration without replacing history or expressing a domain change.
_Avoid_: backup when referring to the derived state, commit.

**Journal pack**:
A content-addressed lossless encoding of a contiguous, fork-free Device-feed
prefix. It may replace loose encodings after atomic verification but never removes
the Operations or their provenance.
_Avoid_: Checkpoint, pruned history, database archive.

**Routine checkpoint**:
A replaceable Checkpoint created to bound ordinary replay work. It carries no
special recovery meaning and may be replaced by a causally newer verified one.
_Avoid_: Recovery anchor, backup, save point.

**Recovery anchor**:
A named Checkpoint retained to make a meaningful historical frontier inspectable,
such as before migration, recovery rotation, initial merge, or member-requested
backup.
_Avoid_: rollback point, current state, Routine checkpoint.

**Safety checkpoint**:
A Recovery anchor created before provisionally applying a high-impact destructive
change. It preserves the exact known-good frontier while safety confirmation is
unresolved.
_Avoid_: automatic backup, accepted deletion, undo timer.

**Pending Operation**:
A known Operation that cannot yet be evaluated because causal prerequisites or a
supported schema are unavailable. It affects no current projection while pending.
_Avoid_: rejected Operation, failed sync.

**Accepted Operation**:
An authenticated, authorized, causally ready, and domain-valid Operation. Accepted
Operations affect synchronized projections exactly once.
_Avoid_: received Operation, acknowledged transport message.

**Quarantined Operation**:
A well-formed Operation withheld from normal convergence because it is suspicious,
conflicting, or confirmation-gated. Known-good projections remain usable while it
awaits resolution.
_Avoid_: rejected Operation, pending Operation.

**Rejected Operation**:
An Operation proven invalid by its signature, content identity, Feed continuity,
or structural rules. Rejection is attributable and never mutates current state.
_Avoid_: unsupported Operation, quarantined Operation.

**Anti-entropy**:
The transport-independent exchange through which Replicas compare causal knowledge
and obtain missing Operations. It is resumable and treats every delivery as
repeatable.
_Avoid_: database diff, full replacement, real-time connection.

**Materializer**:
The deterministic domain interpreter that derives current synchronized state from
Accepted Operations. It applies explicit rules for concurrency rather than a
universal latest-write policy.
_Avoid_: merge algorithm, transport adapter, database migration.

**Protocol generation**:
A causally activated generation of Operation meanings, Materializers, and
Checkpoint formats authorized for new synchronized facts. Reader support precedes
authoring activation; historical generations remain permanently interpretable.
_Avoid_: app version, Suite generation, negotiated protocol, database version.

**Projection health**:
The relationship between a materialized view and the journal frontier and
Materializer version it claims to represent. An incomplete or incompatible
projection is rebuilt or reported honestly; it never emits corrective Operations.
_Avoid_: sync status, data freshness, database availability.

**Compatibility profile**:
An authenticated statement of the Operation schemas, Materializers, Checkpoint
formats, Cryptographic suites, Recovery formats, and authoring capabilities one
device build can safely use. It describes capability without negotiating weaker
meaning or authority.
_Avoid_: app version, feature flags, protocol fallback, supported devices list.

**Compatibility limited**:
The state of an Authorized device that can preserve authenticated synchronized
facts but cannot interpret every meaning required for ordinary Full-device
authoring. Its known-good projection remains inspectable while affected authoring
waits for a compatible update.
_Avoid_: revoked, offline, quarantined, unsupported Operation.

**Authoring prerequisite**:
The verified causal or domain knowledge an Authorized device must hold before it
may author a particular kind of Operation. An incomplete Replica may author only
Operations whose prerequisites it satisfies.
_Avoid_: full synchronization, network availability, transport acknowledgement.

**Rehydration**:
Rebuilding a missing, empty, or invalid Replica from a verified Checkpoint and
the union of available journal sources. It stages and verifies the result before
activation; missing local data is replica failure, never deletion intent.
_Avoid_: merge, restore, synchronization.

**Recovery incomplete**:
A recovery state with verified usable data but known feed, blob, authorization,
or schema gaps. Inspection remains available while authoring is limited by the
missing prerequisites.
_Avoid_: recovered, empty account, data deletion.

**Synchronized deletion**:
An explicit authenticated Operation expressing the member's intent to remove
shared durable data from every Replica. Absence of data is never a deletion.
_Avoid_: missing row, empty database, storage wipe.

**Destructive change set**:
A deletion or correction batch whose unusual scope could indicate corruption or
accidental loss. It remains quarantined until another Full device or current
Recovery authority independently confirms it, while known-good state stays visible.
_Avoid_: empty Replica, normal catch-up, database migration.

**Membership decision required**:
The state produced by concurrent join and leave intent for the same Crew. New
publication pauses while cached data and the sealed Crew capability remain
available for an explicit member decision.
_Avoid_: left Crew, active membership, latest membership change.

**Recovery restore**:
A member-approved recovery that writes new compensating Operations to recreate
selected historical domain state while preserving unrelated newer work. It never
rewinds authorization, Recovery authority, Content epochs, or an Active phase.
_Avoid_: checkout, rollback, reset.

**Quarantine**:
A protective state that withholds suspicious Operations from normal convergence
until the member reviews them. Known-good Replica data remains usable while the
quarantined change is unresolved.
_Avoid_: sync failure, deletion, rejection.

**Suspended feed**:
A Device feed whose unresolved volume or storage pressure has stopped further
payload download. Its authenticated frontier evidence remains known so suspension
cannot masquerade as rejection, revocation, or deletion.
_Avoid_: revoked device, Feed fork, ignored changes.

**Recovery authority**:
The current recovery-grade cryptographic authority for a Member Identity. It may
restore access when no Authorized device remains and may rotate without changing
the Member ID.
_Avoid_: Member ID, Device Identity, account password.

**Recovery generation**:
The monotonic succession position of current Recovery authority. A normal change
is confirmed by both current Recovery authority and an Authorized device; when
the Recovery file is unavailable, one Full device may explicitly perform an
emergency rotation. Concurrent rotations are quarantined and leave the previous
generation current until an Authorized device resolves them.
_Avoid_: Content epoch, app version, backup version.

**Display name**:
A member's self-asserted human-readable label. It is one field on the member's
Profile, is not unique, and does not identify the member; the Member Identity
does. One name per member, the same in every Crew.
_Avoid_: username, handle, account name.

**Key fingerprint**:
A short, human-readable rendering of a member's Member ID, shown
under their Display name. Because Display names are not unique, this is what
answers "which of these two is which". It identifies; it does not authenticate.
_Avoid_: user id, account number, hash.

**Recovery file**:
A small passphrase-protected portable copy of current Recovery authority, Crew
capabilities, Mailbox locators, and recovery-only Transport capabilities. It
restores access to the same member rather than carrying their complete data, and
ordinary synchronization never unlocks or uses it. It identifies its Recovery generation and known
Authorization-ledger frontier. A stale file may expose recoverable historical
data through Provisional recovery, but cannot authorize, rotate, reset, or
publish until verified against current authority.
_Avoid_: Join code, account backup, public QR.

**Recovery archive**:
A passphrase-encrypted portable bundle of verified journal packs, Checkpoints,
required blobs, and their manifest. It can rehydrate member data without a
Mailbox but grants authority only when paired with valid recovery credentials.
_Avoid_: Recovery file, database export, plaintext portable backup.

**Mailbox**:
A user-selected, replaceable internet store that holds immutable end-to-end
encrypted synchronization objects until intermittently connected Replicas can
retrieve them. A Mailbox contributes availability, not identity, authorization,
domain ordering, or plaintext trust.
_Avoid_: Relay, Replica, source of truth, Pomo account.

**Mailbox manifest**:
A signed, versioned, frontier-bound inventory of immutable Mailbox objects. It
makes missing or rolled-back provider data detectable without treating provider
timestamps, listing order, or mutable "latest" files as synchronized truth.
_Avoid_: Checkpoint, Operation journal, provider directory listing.

**Peer sync**:
Automatic synchronization whose durable copies exist only on Authorized devices.
Outgoing work remains pending until another device durably acknowledges it; loss
of the only holding device before that acknowledgment can still lose the work.
_Avoid_: Protected sync, local backup, fully synchronized.

**Protected sync**:
Synchronization backed by one or more configured Mailboxes that have verified an
independently recoverable encrypted copy of the reported frontier. Each Mailbox
reports protection separately; protection does not mean every device observed the
copy or that a provider guarantees permanent retention.
_Avoid_: Peer sync, device acknowledgment, backup guarantee.

**Transport capability**:
A rotatable secret or provider credential that permits a device to use a Mailbox.
It is transferred explicitly during admission, remains separate from Member and
Device authority, and never makes its holder an Authorized device. Recovery-only
capabilities remain sealed in the Recovery file; ordinary device capabilities are
independently scoped when the provider permits it.
_Avoid_: Device agreement key, Recovery authority, pairing secret.

**Rendezvous relay**:
A public Nostr-style relay used for encrypted synchronization hints and bounded
best-effort Transport envelopes. Acceptance is never a durable acknowledgment;
the source retains work until a peer or Mailbox confirms it.
_Avoid_: Mailbox, durable copy, Crew Relay.

**Retry now**:
A member-requested immediate attempt of the normal bounded synchronization drain.
It rechecks available routes but never bypasses authorization, validation,
quarantine, backoff history, or durability rules and never rewrites cursors.
_Avoid_: force sync, reset sync, repair history.

**Snapshot**:
The small, signed, Crew-encrypted bundle of stats a member publishes to the
relays for others to rank. It shares aggregate performance and its source History
frontier, never individual Work block timestamps; only causal domination may
replace an uncontested Snapshot.
_Avoid_: report, sync payload, update.

**Snapshot settling**:
The Crew presentation state produced by concurrent Snapshots from one member.
Peers retain the last uncontested Snapshot until a reconciled Snapshot causally
dominates every branch.
_Avoid_: latest Snapshot, stale member, merge totals.

**Ranking window**:
The span of recent Focus minutes compared by a Crew: each member's phone-local
Today, current local date plus the previous 6 dates, current local date plus the
previous 29 dates, or All-time.
_Avoid_: week, month (both imply calendar boundaries).

**Rank**:
A member's position by Focus minutes within a Ranking window. Equal totals share
the same Rank; row order among tied members is not a competitive result. A member
with zero Focus minutes in that window is unranked.
_Avoid_: place when it implies a tie-break winner.

**Hidden member**:
An Identity omitted from one phone's view of a Crew. Hiding is a local filter,
not removal from the Crew and not an administrative action.
_Avoid_: banned member, kicked member, blocked account.

**Inactive member**:
A member whose latest completed Focus Work block is more than 30 days old.
Inactive members keep their aggregate stats but do not participate in active
Ranks.
_Avoid_: deleted member, former member (membership cannot be observed remotely).

**Relay**:
A public Nostr-style server, run by strangers, that stores and forwards
snapshots. Pomo uses several for redundancy and owns none of them. Their
existence is what makes "decentralized" mean "no central authority", not "no
servers anywhere".

## Desktop Instruments

**Instrument**:
Any display-and-control surface that renders the timer engine's state. The
phone's screens, widget, and notification are instruments; so are the extension
surfaces (New Tab, side panel, popup, badge). Instruments render; they never own
state. Any instrument may control its device's Replica, while the Active timer
owner determines which live Work block lineage accepts commands.
_Avoid_: screen, panel, view (when the concept is the state-ownership rule).

**Engine**:
The single owner of timer state on a device — the phone's `OfflineTimer` and the
extension's shared state machine. Exactly one engine exists per device; every
instrument on that device reads the same engine state. The engine persists state
and history; instruments are disposable.
_Avoid_: controller, service, logic layer.

**Reconciliation**:
The engine's act of catching up to reality when it wakes after being asleep —
browser or process suspended. Remaining time is always derived from the stored
end point, never from counting ticks; a session that elapsed while the device
slept completes as if it had finished on time. The phone reconciles after process
death; the extension reconciles after every service-worker suspension.
_Avoid_: sync, recovery, fix-up.

**Burst**:
A short-lived, self-contained relay exchange that opens, transacts, and closes
within its timeout. Publishing one snapshot and refreshing the board are bursts
on both phone and extension. The extension's service worker can only hold
connections while it is awake, so burst discipline is mandatory there.
_Avoid_: stream, long poll, connection.

**Observe**:
The long-lived relay subscription that streams live snapshots. It exists only
while a Crew instrument is visible; closing the instrument closes the
subscription. On the extension, Observe runs from the page context (New Tab or
side panel), never from the service worker.
_Avoid_: subscribe, listen (when the lifetime rule matters).

**Surface**:
One named render target of an instrument family — the extension's New Tab page,
side panel, popup, or badge. Surfaces share one engine state and one theme token
set; they differ only in size and density.
_Avoid_: page, window (generic), widget (the Android term).
