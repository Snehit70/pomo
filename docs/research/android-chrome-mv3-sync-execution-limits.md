# Android and Chrome MV3 synchronization execution limits

Research date: 2026-08-10

Wayfinder question: What do official Android and Chrome Manifest V3 sources establish about background execution, connectivity, wakeups, service-worker suspension, storage durability, LAN discovery, battery constraints, and retry scheduling that bound seamless peer synchronization?

## Executive answer

Pomo cannot safely promise an always-on, seconds-latency peer connection between an Android app and a Chrome Manifest V3 extension. Both platforms deliberately suspend invisible background work:

- Android Doze suspends network access and defers jobs, syncs, and ordinary alarms. WorkManager is durable scheduling, not exact scheduling, and periodic work has a 15-minute minimum.
- Chrome terminates an idle extension service worker after about 30 seconds, can terminate a slow fetch whose response takes more than 30 seconds, and does not let an alarm wake a sleeping device. A sleeping browser coalesces missed repeating alarms.

The platform-compatible contract is therefore **local-first, event-triggered, resumable eventual convergence**:

1. Commit every user operation and its outbox entry locally before attempting transport.
2. Try immediately while the app/extension is active.
3. Use a relay notification or an already-active connection as a hint, never as the only record of work.
4. Retry from durable state on every reliable platform wakeup and connectivity opportunity.
5. Treat LAN as an opportunistic fast path and encrypted store-and-forward relay sync as the asynchronous path.
6. Promise “within seconds while both replicas are awake and reachable”; otherwise promise automatic convergence when the platforms next permit execution. Never promise an exact offline-to-online deadline.

This is a platform constraint, not a transport-library choice.

## Android limits

### Doze and App Standby prevent a permanent invisible peer

In Doze, Android suspends network access, ignores wake locks, and defers `JobScheduler`, WorkManager, sync adapters, and standard alarms until maintenance windows. Maintenance windows become less frequent during long idle periods. These rules apply on Android 6.0 and later regardless of target SDK ([Android: Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)).

Android recommends Firebase Cloud Messaging (FCM), rather than an app-specific persistent socket, for downstream messages during idle. Normal-priority messages used for background data sync are delivered in maintenance windows during Doze. High-priority FCM is intended for time-sensitive, user-visible notifications, not silent database synchronization ([Android: Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)).

**Pomo implication:** a decentralized relay WebSocket cannot guarantee that a sleeping phone receives a sync hint immediately. If Pomo does not adopt FCM and its sender infrastructure, the honest behavior is eventual sync at a maintenance window, app open, active timer service, or another permitted wakeup. Even with FCM, silent sync must not misuse high priority.

### A foreground service is user-visible and cannot be a general wakeup loophole

Apps targeting Android 12 or later generally cannot start a foreground service from the background except for documented exemptions. A foreground service must represent work noticeable to the user and display a notification ([Android: foreground-service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Android: services overview](https://developer.android.com/develop/background-work/services)).

The current Pomo app targets SDK 34 and runs `PomodoroService` as a `specialUse` foreground service. That existing, user-visible timer lifetime is a valid opportunity to synchronize and host the LAN server. It does **not** justify keeping the service alive indefinitely solely to make sync instantaneous when no timer or other user-visible operation is active.

**Pomo implication:** bind opportunistic immediate sync to visible app use and the legitimate timer foreground-service lifetime. Outside those periods, persist work and hand it to WorkManager rather than manufacturing a permanent sync notification.

### WorkManager is durable but inexact

Android recommends WorkManager for work that must survive app exits and device reboots ([Android: persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent)). A periodic request has a minimum 15-minute interval; its actual execution depends on constraints and system optimization, and a constrained run may be delayed or skipped. One-time retries support linear or exponential backoff; the first retry delay cannot be below 10 seconds, and the default is exponential starting at 30 seconds ([Android: define work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)).

`ConnectivityManager.NetworkCallback` can react immediately while Pomo's process is alive, but Android explicitly points background downloads and “run when connectivity returns” work to WorkManager. Manifest-declared `CONNECTIVITY_ACTION` is not a durable wake mechanism for apps targeting Android 7 or later ([Android: read network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state), [Android: broadcasts overview](https://developer.android.com/develop/background-work/background-tasks/broadcasts)).

**Pomo implication:** use one unique, network-constrained, one-time sync work chain as the durable retry mechanism; enqueue or update it whenever the outbox becomes non-empty. A periodic 15-minute request may be a repair/safety net, not the primary latency mechanism. Also attempt a drain from live `NetworkCallback` events while the process exists.

### Android supports LAN discovery, but permission and lifecycle rules apply

Android NSD implements DNS-SD/mDNS service registration and discovery. Android recommends choosing an available port rather than assuming a fixed one, and notes that advertised instance names can be changed to resolve LAN conflicts ([Android: use network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd)). Pomo already advertises its `PhoneServer` through `NsdManager`, so Android has the correct primitive for its side of discovery.

Android 16's Local Network Protection rollout documents that outgoing and incoming TCP, UDP multicast/broadcast, `.local` resolution, and `NsdManager` access are within the future runtime-permission boundary. The Android 16 phase is opt-in, but the documentation explicitly tells developers to prepare for denial and revocation in the later enforcement release ([Android 16: Local Network Permission](https://developer.android.com/about/versions/16/behavior-changes-16#local-network-permission)).

**Pomo implication:** model LAN availability as a revocable capability. The product must continue through relay/store-and-forward when Nearby/Local Network permission is denied, revoked, or unavailable; a permission failure must not look like data corruption or authorization loss.

### Android local storage is not a backup contract

Android app-specific files and databases are removed on uninstall ([Android: data and file storage overview](https://developer.android.com/training/data-storage)). Auto Backup is Google-account-dependent, stores only the most recent backup, and is limited to 25 MB per app; device-specific identifiers should be excluded ([Android: Auto Backup](https://developer.android.com/identity/data/autobackup)).

**Pomo implication:** Room can be the transactional local journal, but it cannot be the last durable copy. Android Auto Backup may be defense in depth only; it cannot replace replicated encrypted journal/checkpoints or user-controlled recovery exports, and Device Identity secrets/tokens require deliberate backup exclusions.

## Chrome Manifest V3 limits

### The service worker is ephemeral by design

Chrome normally terminates an extension service worker after 30 seconds of inactivity, when one event/API request runs longer than five minutes, or when a `fetch()` response takes more than 30 seconds to arrive. Incoming extension events revive a dormant worker, but Chrome requires designs to tolerate unexpected termination. Global variables disappear at shutdown, so durable state must be stored ([Chrome: extension service-worker lifecycle](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle)).

Event listeners must be registered synchronously at top level or the waking event can be missed. Timers are cancelled when the worker terminates and should be replaced with `chrome.alarms` ([Chrome: events in service workers](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/events), [Chrome: migrate to a service worker](https://developer.chrome.com/docs/extensions/develop/migrate/to-service-workers)).

**Pomo implication:** each sync wake must reconstruct state from IndexedDB, claim a bounded batch, make idempotent requests, durably checkpoint acknowledgement, and safely stop. Never keep correctness-critical cursors, locks, retry times, or peer state only in worker globals. Register message, alarm, storage, startup, and connection listeners at module top level.

### Chrome alarms are a coarse repair trigger, not a clock

Chrome limits packaged extensions to alarms no more frequent than once every 30 seconds and may delay them arbitrarily. Alarms do not wake a sleeping device; after wake, missed alarms fire at most once and repeating schedules restart from wake time. The current API also advises checking/recreating important alarms whenever the service worker starts because alarm persistence can be unpredictable on Chrome versions before 150 ([Chrome: `chrome.alarms`](https://developer.chrome.com/docs/extensions/reference/api/alarms)). Pomo currently supports Chrome 120+, so it must follow that conservative rule.

**Pomo implication:** use an alarm as a coalesced retry/repair hint. Store the authoritative next-attempt time and backoff state in IndexedDB, recreate the alarm on every worker start, and drain all due work when one alarm fires. Do not schedule one alarm per operation or derive correctness from the number of missed ticks.

### WebSockets improve active latency but do not create an availability guarantee

Since Chrome 116, sending or receiving WebSocket messages resets the extension worker idle timer. Chrome nevertheless advises avoiding indefinite worker lifetimes, and the official migration guidance says indefinite artificial keepalive is unsupported for ordinary consumer extensions ([Chrome: extension service-worker lifecycle](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle), [Chrome: migrate to a service worker](https://developer.chrome.com/docs/extensions/develop/migrate/to-service-workers)).

**Pomo implication:** a WebSocket to an internet relay is useful while naturally active and can deliver low-latency hints. Pomo must not depend on heartbeat traffic solely to pin the worker alive. Browser closure, profile suspension, OS sleep, crashes, and extension updates still require durable catch-up on the next wake.

### Durable extension state has quotas and deletion boundaries

Extension IndexedDB is available in service workers. Extension-origin storage can still be evicted under storage pressure unless the extension requests `unlimitedStorage` or obtains persistent storage; `unlimitedStorage` exempts extension storage from quotas and eviction. Extension storage is not cleared with browsing-data deletion, but `chrome.storage.local` is cleared when the extension is removed ([Chrome: storage and cookies](https://developer.chrome.com/docs/extensions/develop/concepts/storage-and-cookies), [Chrome: `chrome.storage`](https://developer.chrome.com/docs/extensions/reference/api/storage)).

`chrome.storage.sync` is approximately 100 KB total and 8 KB per item and is coupled to the user's Chrome-account sync setting ([Chrome: `chrome.storage`](https://developer.chrome.com/docs/extensions/reference/api/storage)). It is unsuitable for Pomo's journal, history, checkpoints, or decentralized identity.

**Pomo implication:** keep the operation journal/outbox in transactional IndexedDB, evaluate `unlimitedStorage` if the complete history and recovery journal require it, and detect quota/transaction failures explicitly. No Chrome-local mechanism survives extension removal, so replication and recovery exports remain mandatory.

### Network access requires declared hosts; automatic LAN discovery is the gap

An extension service worker can make cross-origin requests only when the extension has matching `host_permissions` ([Chrome: cross-origin network requests](https://developer.chrome.com/docs/extensions/develop/concepts/network-requests)). Chrome's current extension API reference does not expose stable raw TCP/UDP or mDNS discovery APIs to ordinary extensions; those APIs are documented under the discontinued Chrome Apps platform, not the Extensions platform ([Chrome Extensions API reference](https://developer.chrome.com/docs/extensions/reference/api), [Chrome Apps API reference](https://developer.chrome.com/docs/apps/reference)). This comparison is an inference from the two official API surfaces.

Chrome 142 also introduced Local Network Access permission gating for web-origin requests to local and loopback endpoints. Its official guidance says a service worker cannot itself trigger the prompt and requires its origin to have received permission from a document first. The published guidance is framed around websites and does not establish an extension-specific exemption ([Chrome: Local Network Access](https://developer.chrome.com/blog/local-network-access)).

**Pomo implication:** do not make multicast discovery from MV3 or an unprompted background LAN fetch a prerequisite. Pairing should provide a durable peer endpoint/name or relay rendezvous, and the extension should request/test any needed host and Local Network Access capability from a visible extension page. Pomo must feature-test current Chrome behavior because the official LNA article does not settle the extension-origin case. Relay fallback remains required.

## Recommended Pomo execution model

### Shared invariants

- **Write-ahead:** a user-visible mutation succeeds only after its signed operation and outbox intent are atomically durable locally.
- **At-least-once transport, exactly-once effect:** retries may resend; globally unique operation IDs and idempotent application prevent duplicate effects.
- **Hints are disposable:** WebSocket frames, connectivity callbacks, alarms, notifications, and NSD discoveries only say “try now.” The journal/outbox says what must happen.
- **No absence-as-deletion:** an empty/failed local read or lost replica is never converted into delete operations.
- **Bounded resumable drains:** process small batches, checkpoint acknowledgements transactionally, and yield before platform timeouts. A fetch with no response before Chrome's 30-second boundary must be aborted and retried.
- **Independent backoff:** store per-route/per-peer exponential backoff with jitter, but let fresh local user work or a new connectivity hint advance the next attempt.
- **Catch up before live:** after any wake or reconnect, exchange journal frontiers and missing durable operations before trusting transient live-timer frames.

### Wakeup matrix

| Opportunity | Android action | Chrome MV3 action | Guarantee |
| --- | --- | --- | --- |
| Local operation | Commit journal + outbox, drain immediately; enqueue unique WorkManager work | Commit journal + outbox in IndexedDB, wake worker via extension message and drain | Immediate attempt, not delivery |
| UI opens/resumes | Drain and register live network callback | Top-level worker listeners drain; foreground surface may request LAN permission | Strong opportunity while visible |
| Network returns | Live `NetworkCallback` drains if process exists; constrained WorkManager handles durable case | No general OS connectivity wake guarantee; active WebSocket/event or retry alarm may wake worker | Eventual, timing unspecified |
| Timer is active | Existing foreground service can drain and host LAN server | Worker can use active relay/LAN connection but must survive termination | Seconds-latency is reasonable while reachable |
| Relay has new data | Normal FCM only if Pomo chooses FCM; otherwise socket works only while Android may run | WebSocket event while browser/worker is active | Hint only |
| Scheduled repair | One-time WorkManager retry; 15-minute-or-greater periodic safety net | `chrome.alarms`, minimum 30 seconds, coalesced after sleep | No exact deadline |
| LAN peer appears | Android NSD plus known peer endpoints, subject to permission | Connect to paired/known endpoint; no stable extension mDNS API | Optional fast path |
| App/extension removed | Local replica is gone | Local replica is gone | Recover from another replica, relay checkpoint, or export |

### Product promise the platforms can support

Pomo may state:

> Changes synchronize automatically within a few seconds when authorized devices are awake and reachable. If a device, browser, network, or platform background scheduler is unavailable, Pomo keeps changes locally and converges automatically at the next permitted connection. Sync status shows pending work and the last confirmed replica/relay checkpoint.

Pomo should not state “always real-time,” “instant after internet returns,” or “LAN discovery always works.”

## Decisions unblocked by this research

1. **Transport topology:** the specification needs both opportunistic LAN and encrypted store-and-forward internet relay paths. LAN-only cannot meet asynchronous device availability.
2. **Scheduling:** use event-driven immediate drains plus durable platform retries; polling is repair-only.
3. **Chrome architecture:** IndexedDB is the journal/outbox source of truth; the worker is a stateless orchestrator reconstructed on every event.
4. **Android architecture:** Room is the journal/outbox source of truth; live callbacks/foreground service accelerate sync and unique WorkManager work provides durable retry.
5. **Discovery:** Android NSD can advertise the phone, but authorization must also establish a relay rendezvous or reusable endpoint because MV3 lacks equivalent discovery and LAN permission can be denied.
6. **Latency SLO:** seconds while awake/reachable; unspecified eventual catch-up while slept, closed, idle, permission-blocked, or offline.
7. **Durability:** neither platform's local storage nor vendor backup is sufficient as the last copy; replicated encrypted operations/checkpoints and explicit recovery exports are required.

## Validation required before implementation claims

Official documentation establishes design bounds, not Pomo behavior. The implementation plan should include:

- Android device tests forcing Doze/App Standby, process death, reboot, network changes, Local Network permission denial/revocation, and foreground-service absence/presence.
- Chrome packaged-extension tests at the declared minimum Chrome version and current stable for worker termination mid-batch, browser restart, OS sleep, missed/coalesced alarms, extension update, quota failure, LNA denial/revocation, and LAN fetches from a worker after permission was requested in a visible extension page.
- Cross-device tests where each delivery and acknowledgement is duplicated, reordered, interrupted, or lost, proving recovery from durable frontiers rather than lucky wake timing.
- Explicit evidence separation: green unit/CI checks do not prove OS wakeups, Doze behavior, Chrome lifecycle behavior, LAN permissions, or real-device convergence latency.
