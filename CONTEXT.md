# Pomo

Glossary for the Pomo focus-timer domain. The phone is the source of truth for
timer state, history, stats, and Crew. Desk, Omarchy, Waybar, and Chrome are
hybrid instruments: they follow the phone when it is reachable and run a local
timer when it is not. This file defines the language we use to talk about the product;
it is not a spec and carries no implementation detail.

## Language

**Work block**:
One continuous run of the focus phase. It is *completed* when it runs to its
scheduled end (including any add-time); it is *partial* when Skip ends it early.
Reset abandons a block and records nothing at all. Historically a fixed length
(e.g. 25 min); once add-time exists it is variable-length. The unit that history
rows and the long-break cadence count.
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

## Session Tags

**Session tag**:
A freeform string label the member assigns to a Work block *before* it starts.
Tags classify blocks for later review — Work, Study, Personal, or whatever the
member invents. Every new Work block carries one; there are no untagged blocks
going forward. The tag is locked (immutable) for the duration of the Work phase.
_Avoid_: status tag, label, category, topic.

**Default tag**:
The tag that is pre-selected for the next Work block. Persists in TagStore
across app restarts. Initial value is "Work". Updated whenever the member
explicitly picks a different tag; that choice carries forward until changed
again.
_Avoid_: auto-tag, preselected tag, last tag.

**Active tag**:
The tag carried by the current session in TimerState. Before a Work block it
equals the Default tag; once the timer starts it is locked. Survives process
death because it lives on TimerState. Read at session-completion time to record
the tag in Room.
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
auto-advances, so the cue only tells the member a phase finished. It has two
modes — one-shot and Ring.
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
other on Focus minutes. Crew lives on the phone.
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
belongs to one member identity, not to a Pomo account or a Crew.
_Avoid_: account, login, persona.

**Identity key**:
The per-device keypair that signs a member's snapshots. It is the stable
cryptographic identity of a member inside Crew and underlies that member's
Profile.
_Avoid_: account, user id.

**Display name**:
A member's self-asserted human-readable label. It is one field on the member's
Profile, is not unique, and does not identify the member; the Identity key
does. One name per member, the same in every Crew.
_Avoid_: username, handle, account name.

**Key fingerprint**:
A short, human-readable rendering of a member's Identity key public key, shown
under their Display name. Because Display names are not unique, this is what
answers "which of these two is which". It identifies; it does not authenticate.
_Avoid_: user id, account number, hash.

**Recovery file**:
A passphrase-protected portable backup of an Identity key and its Crew
memberships. It restores the same member; it is never an invitation.
_Avoid_: Join code, account backup, public QR.

**Snapshot**:
The small, signed, Crew-encrypted bundle of stats a member publishes to the
relays for others to rank. It shares aggregate performance, never individual
Work block timestamps; it is append-only and the latest one wins.
_Avoid_: report, sync payload, update.

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

## Hybrid instruments

**Instrument**:
Any display-and-control surface that renders the timer. The phone's screens,
widget, and notification are instruments; so are the desk, the Omarchy bar, Waybar, and
the extension surfaces (New Tab, side panel, popup, badge).
_Avoid_: screen, panel, view (when the concept is the state-ownership rule).

**Hybrid instrument**:
A client that follows the phone clock while the phone API is reachable, runs its
own local engine while the phone is gone, then flushes completed Work blocks and
may hand a live timer to the phone on reconnect. PomoLink, the Omarchy plugin, and Waybar
already are this. Chrome is this.
_Avoid_: Replica, peer, Full device, account.

**Adopt**:
The reconnect rule for a live timer. The phone always takes a running/paused
hybrid timer when the phone is stopped. The same session is always allowed. When
both are live on different sessions, the hybrid wins only if its remaining time
is strictly less; otherwise the phone keeps the clock and the hybrid snaps.
_Avoid_: merge, latest write, dual ownership.

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
on the phone.
_Avoid_: stream, long poll, connection.

**Observe**:
The long-lived relay subscription that streams live snapshots. It exists only
while a Crew instrument is visible on the phone; closing the instrument closes
the subscription.
_Avoid_: subscribe, listen (when the lifetime rule matters).

**Surface**:
One named render target of an instrument family — the extension's New Tab page,
side panel, popup, or badge. Surfaces share one engine state and one theme token
set; they differ only in size and density.
_Avoid_: page, window (generic), widget (the Android term).

## Design process

**Mock**:
A disposable, high-fidelity rendering of a candidate UI built from the app's
real design tokens, used to reach agreement before implementation. Never
shipped, and never partially mirrored into code — the picked design is
implemented once, fully.
_Avoid_: prototype (implies evolving into code), wireframe, screenshot.

**UI round**:
One focused pass that takes a surface to fully consistent with the shared
design tokens and string resources — no hardcoded measurements, colors,
shapes, or copy. A round defines shared components when a surface forces the
question; those components then apply app-wide.
_Avoid_: redesign (too broad), polish (too vague).

**Page shell**:
The shared chrome every full-screen page composes from — back header with the
page title, background, grouped cards with section headers, and edge spacing.
Content varies between pages; shell anatomy does not.
_Avoid_: layout, scaffold (a Compose type), theme.
