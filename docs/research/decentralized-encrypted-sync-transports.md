# Decentralized encrypted sync transports

## Question

What LAN and internet store-and-forward transports can carry end-to-end encrypted
Pomo Operations without a mandatory Pomo account or backend, and what guarantees
do they actually provide?

This report evaluates transport and mailbox behavior only. It does not choose the
Operation format, conflict model, key hierarchy, or final Pomo architecture.

## Required separation

No single candidate below gives all three properties automatically:

1. **Discovery** — finding another authorized device.
2. **Live transport** — exchanging bytes while both endpoints are reachable.
3. **Durable mailbox** — retaining bytes until an endpoint that was offline returns.

Encryption does not add delivery or retention, and replication does not make a
relay a backup. Pomo therefore needs to judge each layer independently.

## Candidate summary

| Candidate | Delivery and persistence | Ordering | Revocation | Metadata exposed to operator | Chrome MV3 | Replaceability |
|---|---|---|---|---|---|---|
| mDNS + phone HTTP/WebSocket | Live only; no delivery while phone is unreachable | Ordered within one WebSocket connection; reconnect needs application cursors/deduplication | Pomo authorization layer must reject the device | LAN peers see addresses, timing, sizes, and service advertisement | Feasible as an outbound client, with permissions/lifecycle constraints | High; ordinary DNS-SD and HTTP/WebSocket |
| WebRTC data channel | Live peer-to-peer only; no mailbox; signaling and sometimes TURN still required | Configurable reliable/ordered channel, not a durable cross-session order | Pomo key/authorization layer | Signaling/TURN peers see connection metadata; remote peer learns network information | Feasible, but MV3 suspension and signaling remain | Medium; standard browser API, but signaling/TURN are separate dependencies |
| Nostr relays + NIP-44/NIP-59 envelope | Relays accept/query events, but public-relay retention and future retrieval are not guaranteed | Event IDs deduplicate; timestamps/relay arrival do not form a trustworthy total order | Stop wrapping for a revoked device and rotate future keys; old ciphertext/copies cannot be recalled | Relay sees IP, time, size and routing recipient key; Gift Wrap hides the real sender and content | Feasible over WebSocket; current repo already uses bounded relay bursts | High; clients can publish/query several independently operated relays |
| Encrypted immutable objects on WebDAV | Durable to the chosen provider's storage/retention terms; no standard push | No global log order; conditional writes prevent overwrite, while Pomo IDs/causality supply order | Revoke provider credential and rotate Pomo keys; already downloaded objects remain | Provider sees account, paths/object count, sizes, access timing and IP | Feasible via `fetch`, host permission, and polling/wake triggers | High at the protocol level; providers differ operationally |
| Encrypted documents in CouchDB | Incremental HTTP replication and continuous changes feed; durability follows the deployed database | Change sequence is a replication cursor, while conflicts need application resolution | Database credentials plus Pomo key rotation | Operator sees account/database, document IDs/revisions, sizes, timing and IP unless names are also opaque | HTTP/CORS makes it feasible, but it adds a full replication client | Medium; open protocol/software, but moving providers is heavier than copying immutable objects |
| Matrix encrypted room/to-device events | Homeserver provides durable sync and device/key machinery; availability and retention follow homeserver policy | `/sync` gives cursors, but arrival order can differ from graph order and clients must deduplicate | Mature device deletion/cross-signing machinery, with the normal impossibility of erasing downloaded plaintext | Homeserver sees account, room/device graph, event sizes and timing despite encrypted content | Technically feasible over HTTPS long polling | Medium; federated/self-hostable, but every user still needs a homeserver account |
| Automerge Repo sync server | A disk-backed server can store documents for later peers; the public community server explicitly gives no reliability or data-safety guarantee | Automerge sync handles document convergence, not Pomo's business ordering | No suitable authorization/revocation in the reference sync server | Reference server is explicitly unsecured and receives document/sync identifiers and traffic metadata | JS, IndexedDB, and WebSocket adapters fit Chrome | High adapters, low production readiness without operating/hardening a server |

## 1. Direct LAN fast path

DNS-SD/mDNS is appropriate for discovery, not authorization. DNS-Based Service
Discovery specifies how clients discover named service instances and resolve them
to a host and port; it does not authenticate the discovered service
([RFC 6763](https://www.rfc-editor.org/rfc/rfc6763)). Pomo already advertises
`_pomo._tcp` and serves an authenticated phone HTTP/WebSocket API
([current protocol](../protocol.md#discovery)), so the repo contains a useful
starting seam rather than a blank slate.

An authenticated HTTP exchange can catch up immutable Operations, followed by a
WebSocket subscription for low-latency updates. WebSocket supplies ordering only
for frames on that connection. After interruption, Pomo still needs durable local
outboxes, stable Operation IDs, acknowledgement/cursor rules, and replay-safe
deduplication. The LAN route cannot deliver anything when the phone is asleep,
off-network, or both devices are not online together.

Chrome extensions can make cross-origin `fetch()` calls from an extension service
worker when the destination is covered by host permissions
([Chrome cross-origin requests](https://developer.chrome.com/docs/extensions/develop/concepts/network-requests)).
Chrome also documents an `mdns` permission, although it cannot be optional and
therefore increases installation privilege
([Chrome permissions](https://developer.chrome.com/docs/extensions/reference/api/permissions)).
Chrome's newer Local Network Access permission adds another user-visible boundary
for requests to private IPs and `.local` names
([Chrome Local Network Access](https://developer.chrome.com/blog/local-network-access)).

MV3 is not a permanent daemon. Chrome normally terminates an extension service
worker after 30 seconds of inactivity and instructs extensions to persist state
against unexpected termination. WebSocket traffic can reset that timer from
Chrome 116 onward, but keeping a socket alive requires traffic within the activity
window
([service-worker lifecycle](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle),
[WebSockets in extension service workers](https://developer.chrome.com/docs/extensions/how-to/web-platform/websockets)).
Therefore correctness cannot depend on a continuously resident WebSocket. Reconnect
and catch-up must be first-class.

**Pomo implication:** keep direct LAN synchronization as an opportunistic fast
path. Authenticate the peer cryptographically after discovery, encrypt/authenticate
Operations even on trusted Wi-Fi, and never treat absence from a LAN response as a
deletion. LAN alone cannot meet the accepted cross-location or offline requirement.

## 2. WebRTC is a connection, not a mailbox

An `RTCDataChannel` can be ordered and reliable, or configured with bounded packet
lifetime/retransmits
([W3C WebRTC](https://www.w3.org/TR/webrtc/#dom-rtcdatachannel)). That makes it a
possible direct path across NATs, but WebRTC does not specify application signaling
([W3C WebRTC overview](https://www.w3.org/2015/Talks/dhm-webrtc-masterclass/webrtc.html));
ICE negotiation needs some other channel, and restrictive networks can require a
TURN relay ([RFC 8656](https://www.rfc-editor.org/rfc/rfc8656)). Most importantly,
neither STUN, TURN, nor a data channel stores Pomo Operations until an offline
device returns.

**Pomo implication:** WebRTC may later optimize simultaneous peer-to-peer transfer,
but adopting it does not remove the need for an internet mailbox and adds signaling,
NAT traversal, and MV3 lifecycle work. It is not a sensible first durability layer.

## 3. Nostr as a replaceable relay fabric

Nostr's base protocol uses signed, content-addressed events sent and queried over
WebSockets. Regular event kinds are expected to be stored, but NIP-01 explicitly
notes that relay implementations may differ
([NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md)). An `OK` response
confirms that a particular relay accepted an event; it is not a replication quorum,
retention promise, or proof that a future query will return it. NIP-09 also says an
event deletion request cannot guarantee deletion from all relays and clients
([NIP-09](https://github.com/nostr-protocol/nips/blob/master/09.md)). NIP-40 is even
more explicit that relays may retain expired messages indefinitely
([NIP-40](https://github.com/nostr-protocol/nips/blob/master/40.md)). These semantics
rule out treating arbitrary public relays as the sole durable backup.

For confidentiality, NIP-44 provides authenticated encryption but documents the
lack of forward secrecy and the fact that relays/intermediaries see client IP
addresses
([NIP-44](https://github.com/nostr-protocol/nips/blob/master/44.md)). NIP-59 Gift
Wrap encrypts the real sender and content under a random outer signer, but the
outer event still carries the recipient `p` tag needed for routing. It also warns
that relays may decline to store gift-wrapped events
([NIP-59](https://github.com/nostr-protocol/nips/blob/master/59.md)). NIP-17 suggests
publishing private messages to a small recipient-selected set of inbox relays and
using NIP-42 authentication to limit queries
([NIP-17](https://github.com/nostr-protocol/nips/blob/master/17.md),
[NIP-42](https://github.com/nostr-protocol/nips/blob/master/42.md)). Authentication
reduces unauthorized reads at a cooperating relay; it does not conceal IP/timing
from that relay or make retention contractual.

Multi-relay publishing improves availability and operator replaceability. Stable
Operation IDs can deduplicate identical payloads returned by several relays. Nostr
timestamps have second precision and are supplied by clients; multi-relay arrival
order varies. Pomo must therefore carry its own causal/logical ordering inside the
encrypted Operation rather than use relay arrival or `created_at` as truth.

**Pomo implication:** Nostr is a credible optional/opportunistic internet transport
because it is already implemented on Android and Chrome in this repo and permits
several independent relays. It is not, without a relay contract and verified
retention behavior, a sufficient durable mailbox or backup. Revoking a Device means
ceasing future envelopes for it and rotating future group/content keys; it cannot
withdraw ciphertext already served to the device or copied by a relay.

## 4. WebDAV as a deliberately boring encrypted mailbox

WebDAV offers collections and HTTP methods for remotely creating and retrieving
resources. RFC 4918 recommends entity tags with `If-Match` to prevent lost updates
and supports `If-None-Match` for safe creation of a previously unmapped resource
([RFC 4918, sections 7.3 and 8.6](https://www.rfc-editor.org/rfc/rfc4918)). Pomo could
avoid shared mutable files entirely by storing every encrypted Operation and
checkpoint under a content-derived or random immutable name. Conditional creation
then detects a collision rather than overwriting another device's bytes.

WebDAV defines neither push notification nor application ordering. Clients must
poll/list after startup, alarms, foreground activity, or manual retry, and the
encrypted envelope must carry stable IDs and causal information. Retention,
quotas, backups, authentication methods, and service availability are provider
properties, not WebDAV guarantees. A provider can also observe account identity,
resource paths/counts, ciphertext sizes, IP addresses, and access times. Opaque
paths, padding/batching, and multiple providers can reduce but not eliminate those
signals.

Chrome can access a user-selected HTTPS WebDAV origin through `fetch` after a host
permission grant. Its service-worker lifecycle favors bounded upload/download
bursts rather than continuous polling. Provider credentials would be a separate
secret from the Member and Device keys.

**Pomo implication:** an encrypted immutable-object mailbox is the simplest option
with understandable durability and minimal server semantics. It preserves Pomo's
transport independence and avoids confusing a provider's last-modified timestamp
with domain order. Its costs are setup/credential UX, polling latency, quota and
provider variability, and the absence of a universal zero-configuration provider.

## 5. CouchDB as a heavier user-provided mailbox

CouchDB defines HTTP replication between source and target databases using an MVCC
revision model and changes feed
([replication protocol](https://docs.couchdb.org/en/stable/replication/protocol.html)).
Replication is incremental and one-way; full two-way synchronization requires push
and pull. CouchDB preserves concurrent leaf revisions and deterministically chooses
one winner, but the application must detect and merge the other revisions
([conflict model](https://docs.couchdb.org/en/stable/replication/conflicts.html)).
That winner must not be mistaken for Pomo's correct domain outcome.

CouchDB documentation explicitly says replication copies the latest document
revision rather than all historical revision bodies, and compaction can discard
non-leaf bodies. Its revision tree is replication metadata, not the accepted
Git-like recovery journal
([replication overview](https://docs.couchdb.org/en/stable/replication/),
[conflict model](https://docs.couchdb.org/en/stable/replication/conflicts.html#merging-and-revision-history)).
CouchDB supplies database membership/basic or cookie authentication, while Pomo
would still need application-layer end-to-end encryption
([CouchDB security](https://docs.couchdb.org/en/stable/intro/security.html)). CORS
can be enabled for browser clients
([CouchDB HTTP server](https://docs.couchdb.org/en/stable/config/http.html#config-cors)).

**Pomo implication:** CouchDB is credible for an advanced self-hosted or
user-provisioned provider and offers more efficient continuous replication than
plain WebDAV. It is substantially more operational and client complexity, leaks
document/revision structure unless Pomo makes identifiers opaque, and cannot
replace the domain journal or its conflict rules.

## 6. Why Matrix and the reference Automerge server are not default mailboxes

Matrix provides a mature long-polling `/sync` cursor, encrypted device key
distribution, cross-signing, QR verification, device management, and server-side
encrypted-key backups. Its specification also warns that event ordering in `/sync`
is based on homeserver arrival and may differ from graph order, so clients should
deduplicate by event ID
([Matrix Client-Server API](https://spec.matrix.org/latest/client-server-api/#syncing)).
This is strong prior art for device lifecycle, but using Matrix makes every Member
a Matrix homeserver account and exposes user, room, device, traffic, and size
metadata to that homeserver. It is much more protocol and identity machinery than
a personal Pomo mailbox. Federation removes a single global operator, not the
requirement for an account-bearing homeserver.

Automerge Repo demonstrates a useful adapter boundary: repositories can use several
network adapters, store locally in IndexedDB, work offline, and reconnect through a
WebSocket sync server
([Automerge network sync](https://automerge.org/docs/tutorial/network-sync/),
[Automerge networking](https://automerge.org/docs/reference/repositories/networking/)).
However, Automerge explicitly gives its public community server no reliability or
data-safety guarantee, and the upstream reference sync server calls itself an
unsecured Express app
([Automerge sync server](https://github.com/automerge/automerge-repo-sync-server)).
Running and securing it would amount to operating a Pomo/user backend. Automerge's
convergence model is also a separate decision from the domain-aware signed Operation
journal already accepted by the Wayfinder discussion.

**Pomo implication:** use Matrix as device-security and recovery prior art, and use
Automerge's pluggable adapter shape as transport-seam prior art. Neither is a
zero-configuration production mailbox that satisfies the current constraints as-is.

## Findings for later decisions

1. **LAN and mailbox should be independent adapters.** LAN HTTP/WebSocket provides
   smoothness when devices overlap; it provides no offline delivery or backup.
2. **Every adapter is at-least-once from Pomo's perspective.** Stable Operation IDs,
   authenticated envelopes, replay-safe ingestion, causal ordering, local outboxes,
   and per-adapter cursors/acknowledgements are required above transport.
3. **Nostr is replaceable but not durably specified.** Multi-relay publication is a
   useful availability layer; arbitrary public relay acceptance is not proof of
   retention and must not satisfy the final durable-copy policy by itself.
4. **Immutable WebDAV objects are the conservative durable-mailbox baseline to
   prototype.** They have the smallest standard server contract and clear
   conditional-write behavior, but provider setup, polling, quota, and retention UX
   require explicit product decisions.
5. **CouchDB is a viable advanced provider, not free conflict semantics.** Its
   replication revision tree must not become Pomo's history or determine domain
   winners.
6. **No revocation can erase an old copy.** Across all transports, revocation can
   reject future writes and rotate future decryption access. It cannot prove remote
   wipe of plaintext or ciphertext already obtained.
7. **Availability is a policy over copies, not a protocol feature.** Later design
   must define which acknowledgements count as durable, minimum independent copies,
   retention verification, checkpoint/export interaction, and how the UI reports
   degraded redundancy.

## Suggested validation before a transport decision

- Prototype one encrypted immutable Operation through Android and Chrome against at
  least two WebDAV implementations; measure initial permission/setup, conditional
  creation, listing pagination, CORS, and recovery after MV3 suspension.
- Run a controlled Nostr relay-retention experiment across multiple upstream relay
  implementations: publish, disconnect both devices, restart/compact the relay,
  query later, and record advertised policies versus observed retention.
- Verify LAN discovery and authenticated catch-up in a packaged Chrome extension on
  current Chrome with Local Network Access enabled; do not infer it from TypeScript
  or Bun tests.
- Threat-model metadata and key rotation separately from payload encryption before
  treating any provider as acceptable.
