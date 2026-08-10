# Direct Chrome-to-Chrome peer transport under Manifest V3

Research date: 2026-08-10

Wayfinder question: What standards-compliant direct peer transport can two authorized Chrome MV3 extension Replicas use while simultaneously reachable, including same-LAN and internet cases, without a permanent Pomo server?

## Executive answer

Chrome-to-Chrome direct synchronization is feasible in a packaged Manifest V3 extension, but the `RTCPeerConnection` must not live in the extension service worker. The standards-backed design is:

1. The ephemeral service worker creates or finds one extension **offscreen document** using the `WEB_RTC` reason.
2. The offscreen document owns `RTCPeerConnection` and a reliable, ordered `RTCDataChannel` while the browser is running.
3. End-to-end encrypted, Device-authenticated offers, answers, and trickled ICE candidates travel through Pomo's replaceable Nostr rendezvous adapters. WebRTC deliberately does not standardize the application's signaling transport.
4. ICE tries host candidates for same-LAN paths and STUN-derived server-reflexive candidates for internet paths. User-selectable TURN servers may provide a live relayed path when NAT, firewall, proxy, or Chrome privacy policy prevents direct connectivity.
5. Every received transport envelope is durably journaled in extension IndexedDB before Pomo sends its application-level durable acknowledgement. WebRTC reliability is not Pomo durability.
6. Browser closure, extension reload/update, offscreen-document loss, ICE failure, or network change destroys the live-session assumption. Pomo recreates the document, renegotiates, and resumes from durable feed frontiers.

This is an **accelerator**, not the offline synchronization substrate. A direct session has no store-and-forward capability; encrypted WebDAV Mailboxes remain the durable asynchronous path. Without TURN, Pomo must report that some simultaneously-online peers cannot establish a live direct path. With TURN, that path is live but relayed rather than direct.

## Where WebRTC can run

| MV3 context | `RTCPeerConnection` | Suitable role | Constraint |
| --- | --- | --- | --- |
| Extension service worker | **No** | Durable wakeup/orchestration, alarms, adapter scheduling | The W3C interface is exposed only to `Window`; Chrome service workers have no `window` or DOM and may terminate when idle. |
| Popup, options page, side panel, or extension tab | **Yes while that page exists** | Visible diagnostics, permission ceremony, physical prototype | Page closure/navigation tears down its in-memory peer connection; a toolbar popup is therefore not a background transport owner. |
| Offscreen document | **Yes** | Live WebRTC owner | It is a hidden `window`, requires the `offscreen` manifest permission, and only `chrome.runtime` is available from the extension API surface. Only one may be open per installed extension per profile. |

The normative WebRTC IDL exposes `RTCPeerConnection` to `Window`, not `ServiceWorker` ([W3C WebRTC, `RTCPeerConnection` interface](https://www.w3.org/TR/webrtc/#dom-rtcpeerconnection)). Chrome likewise documents that extension service workers lack the DOM and `window` interface and directs window-dependent work into an offscreen document ([Chrome: migrate to a service worker](https://developer.chrome.com/docs/extensions/develop/migrate/to-service-workers)).

Chrome's Offscreen API makes the intended route explicit: an offscreen document is an instance of `window`, `WEB_RTC` is a supported creation reason, non-audio reasons impose no documented lifetime limit, and `runtime.getContexts()` can be used to ensure the document exists ([Chrome: `chrome.offscreen`](https://developer.chrome.com/docs/extensions/reference/api/offscreen)). Pomo already declares Chrome 120 as its minimum, which is later than both Offscreen API availability in Chrome 109 and `runtime.getContexts()` in Chrome 116.

“No documented lifetime limit” is not a persistence guarantee. The peer connection and its queues are memory state. Pomo must assume they disappear on browser exit, profile suspension, extension update/reload, renderer failure, or explicit document closure. Chrome's service-worker guidance independently requires important state to survive unexpected worker termination in storage rather than globals ([Chrome: extension service-worker lifecycle](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle)).

## Recommended extension boundary

### Service worker: coordinator, not socket owner

The service worker should:

- register all wakeup and message listeners synchronously;
- inspect durable peer/outbox state in IndexedDB;
- ensure the offscreen document exists using a single-flight creation guard;
- send it commands such as `offer`, `accept-offer`, `add-candidate`, `drain-peer`, and `close-peer` through `chrome.runtime` messaging;
- schedule bounded retries and Mailbox catch-up independently of the live connection; and
- reconstruct orchestration state on every wake.

### Offscreen document: bounded live-session adapter

The offscreen document should:

- own one or more `RTCPeerConnection` objects and their DataChannels;
- perform perfect-negotiation collision handling, candidate gathering, ICE restart, flow control, and connection diagnostics;
- durably enqueue incoming bytes in a same-origin IndexedDB transport inbox before depending on worker delivery;
- read only already-durable outbound work, tolerate duplicate sends, and never treat `RTCDataChannel.send()` as delivery;
- notify the worker of durable ingress, connection changes, and retry needs through `chrome.runtime`; and
- close idle sessions/documents when no live acceleration is useful, while correctness continues through journal and Mailbox state.

Chrome allows only `chrome.runtime` from an offscreen document, but describes the document as a full extension web page for web-platform work ([Chrome: `chrome.offscreen`](https://developer.chrome.com/docs/extensions/reference/api/offscreen), [Chrome: service-worker migration](https://developer.chrome.com/docs/extensions/develop/migrate/to-service-workers)). IndexedDB is a web-platform API rather than a `chrome.*` API. The common ingestion kernel—not the WebRTC adapter—then authenticates and authorizes staged envelopes, commits their journal disposition and frontier atomically, and creates a durable acknowledgement intent. The offscreen adapter may send that acknowledgement only after observing the committed intent. The implementation prototype must prove these IndexedDB transactions and message orderings in the packaged extension.

## Connection establishment

### Signaling is still required

WebRTC supplies the media/data plane, not discovery or application signaling. JSEP deliberately leaves signaling-plane behavior to the application; peers must exchange session descriptions and ICE candidates through some other route ([RFC 9429, section 1.1](https://www.rfc-editor.org/rfc/rfc9429.html#section-1.1)).

Pomo should use its already-selected multi-relay Nostr rendezvous route for short-lived signaling:

- publish encrypted offer, answer, and trickled-candidate envelopes under epoch-rotating opaque routing identifiers;
- bind every signal to `Member ID`, sender and recipient `Device ID`, an unpredictable connection-attempt ID, expiry, and transcript sequence;
- sign the envelope with the sender Device signing key and encrypt it to the intended Device agreement key;
- reject replayed, expired, wrongly addressed, or superseded attempts; and
- use the W3C “perfect negotiation” pattern to resolve simultaneous offers rather than inventing ad hoc glare rules ([W3C WebRTC: perfect negotiation example](https://www.w3.org/TR/webrtc/#perfect-negotiation-example)).

The signaling relay learns timing and traffic volume even when content and stable identifiers are concealed. It is replaceable and is never authoritative. If signaling relays are unavailable, already-connected peers may continue, but new WebRTC sessions cannot be established automatically unless another transport exchanges the offer/answer/candidates.

### ICE, LAN, STUN, and TURN

WebRTC DataChannels run SCTP over DTLS over ICE. This provides reliable or partially reliable streams, encryption/integrity, endpoint certificate authentication at the transport layer, congestion control, and NAT traversal machinery ([RFC 8831](https://www.rfc-editor.org/rfc/rfc8831.html#section-5), [RFC 8835](https://www.rfc-editor.org/rfc/rfc8835.html#section-3.5)).

ICE gathers and tests candidate pairs ([RFC 8445](https://www.rfc-editor.org/rfc/rfc8445.html)):

- **Host candidates** permit a direct same-LAN path when Chrome exposes a usable interface and the peers can route to one another.
- **Server-reflexive candidates** from STUN make many internet/NAT paths direct without carrying Pomo payloads through the STUN service.
- **Relay candidates** from TURN carry the live DataChannel through a server when direct candidate pairs cannot connect.

Chrome may conceal host IP addresses in ICE signaling with resolvable mDNS hostnames; Chromium's source history records its responder implementation for generating and resolving concealed host candidates ([Chromium implementation change](https://chromium.googlesource.com/chromium/src.git/+/ec2dbf9484712fed760ea13e1bb532f1a1dd7db5%5E%21/), [WebRTC mDNS responder interface](https://chromium.googlesource.com/external/webrtc/+/refs/heads/main/rtc_base/mdns_responder_interface.h)). Pomo should forward candidate strings opaquely and must not parse, replace, or persist private IP addresses as peer identity. Because the Chromium source location and browser policy have evolved, the physical prototype—not this historical implementation record—must confirm current packaged-extension behavior.

A direct result is conditional, not guaranteed. RFC 8828 documents browser IP-handling modes that can omit host candidates and recommends TURN over UDP and TCP for robust connectivity; applications should diagnose the absence of host candidates ([RFC 8828, sections 5 and 7](https://www.rfc-editor.org/rfc/rfc8828.html#section-7)). Chrome exposes a user/enterprise `webRTCIPHandlingPolicy` that changes routing and local-address exposure ([Chrome: `chrome.privacy.network.webRTCIPHandlingPolicy`](https://developer.chrome.com/docs/extensions/reference/api/privacy#property-network-webRTCIPHandlingPolicy)). Pomo must respect that policy rather than request the powerful `privacy` permission to rewrite it.

The complete Pomo transport should therefore support:

- more than one configurable STUN URI;
- replaceable TURN endpoints with UDP and TCP/TLS reachability, preferably using short-lived credentials; a provider that only offers static credentials is stored as a separately rotatable Transport capability;
- a visible distinction between `direct LAN`, `direct internet`, `TURN relayed`, and `Mailbox` delivery; and
- seamless fallback to Mailbox catch-up when direct ICE fails and no TURN route is available.

TURN is not a durable Mailbox and must never count as protection. Requiring a Pomo-operated TURN service would also create a permanent central availability dependency, so TURN configuration must be replaceable and may be self-hosted or supplied by the user.

## Permissions and Local Network Access

The packaged implementation requires the `offscreen` manifest permission. Data-only WebRTC does not require microphone or camera permission. Extension `fetch()` requests to signaling, TURN-credential, or Mailbox HTTPS endpoints require appropriate declared or optional host permissions ([Chrome: declare permissions](https://developer.chrome.com/docs/extensions/develop/concepts/declare-permissions)). STUN/TURN packet transport is initiated internally from `RTCPeerConnection` configuration rather than by extension `fetch()`; Chrome's extension documentation does not establish a host-permission requirement for it, so the packaged prototype must verify the exact permission surface. MV3 also requires executable code to be bundled with the extension rather than downloaded at runtime ([Chrome: Manifest V3 overview](https://developer.chrome.com/docs/extensions/develop/migrate/what-is-mv3)).

Chrome's published Local Network Access guidance currently says WebRTC is **not yet gated** by the LNA prompt, while also saying Chrome plans to cover it. The same guidance notes that worker-originated local requests cannot trigger a prompt themselves ([Chrome: Local Network Access](https://developer.chrome.com/blog/local-network-access)). Therefore:

- do not assume WebRTC has a permanent LNA exemption;
- feature-test host-candidate connectivity on every supported stable Chrome;
- if Chrome begins gating WebRTC, request permission from a visible extension page as part of pairing/settings, never from the worker or hidden document;
- treat denial or revocation as route unavailability, not authorization failure; and
- keep Mailbox synchronization fully functional without LAN permission.

This point cannot be settled forever by today's documentation because Chrome explicitly marks the WebRTC integration as future work. It requires a physical browser regression gate.

## Reliability, backpressure, and reconnection

A DataChannel can be configured reliable and ordered, which is the simplest live carrier for Pomo's already-idempotent transport envelopes. That reliability covers one SCTP association; it does not survive losing the document, browser, or association. RFC 8831 states that excessive retransmissions terminate the association and both graceful and non-graceful teardown close its channels ([RFC 8831, section 6.2](https://www.rfc-editor.org/rfc/rfc8831.html#section-6.2)).

Pomo must retain its transport-independent semantics:

- stable Operation IDs and feed positions make a resent envelope harmless;
- an application acknowledgement is sent only after authentication, authorization checks needed for admission to the journal, and a durable IndexedDB commit;
- outbound route work is not complete merely because `send()` returned;
- `bufferedAmount`, `bufferedAmountLowThreshold`, and `bufferedamountlow` bound memory and provide backpressure ([W3C WebRTC: `RTCDataChannel`](https://www.w3.org/TR/webrtc/#dom-rtcdatachannel));
- reconnect first exchanges durable feed frontiers, then transfers missing immutable Operations in bounded batches;
- live timer frames remain transient and are trusted only after durable catch-up; and
- the Mailbox drain remains independent, so a WebRTC bug cannot strand the only durable copy.

When ICE changes to `failed`, the W3C specification recommends an ICE restart. A `disconnected` state may be observed briefly and assessed through traffic statistics before restarting ([W3C WebRTC: ICE restart guidance](https://www.w3.org/TR/webrtc/#dom-rtcpeerconnection-restartice)). An ICE restart still needs fresh signaling. If the offscreen document or peer connection no longer exists, Pomo creates a new attempt and resumes by frontier rather than trying to reconstruct opaque WebRTC state.

## Authentication boundary

DTLS protects the DataChannel in transit and authenticates the certificate used by the peer connection, but that certificate is not automatically Pomo's Authorized Device Identity. WebRTC's security architecture warns that a signaling service can substitute DTLS fingerprints unless peers independently authenticate their binding ([RFC 8827, section 9.1](https://www.rfc-editor.org/rfc/rfc8827.html#section-9.1)).

Pomo should therefore authenticate twice:

1. Signed/encrypted signaling binds the SDP fingerprint and attempt transcript to the expected Authorized Device certificate.
2. The opened DataChannel performs a fresh challenge-response with the Device signing key over the connection-attempt ID and both signed signaling hashes before any sync envelope is accepted.

Discovery names, Nostr routing identifiers, SDP, ICE usernames, DTLS certificates, IP addresses, STUN results, and TURN credentials are transport material—not Member or Device identity. All Operations still pass the common feed, authorization, deduplication, quarantine, and materialization pipeline.

## Alternatives considered

| Alternative | Why it does not replace offscreen WebRTC |
| --- | --- |
| Direct HTTP or WebSocket | Ordinary Chrome extensions can connect outward but expose no stable Extensions API to listen on raw TCP/UDP. Chrome-to-Chrome therefore still needs a server. It remains appropriate for Chrome-to-Android when Android hosts the LAN endpoint. |
| WebTransport | It is a client-to-server transport over HTTP/3, not browser-to-browser establishment or offline storage. It would require a reachable service. |
| Nostr WebSocket bundles | Useful encrypted rendezvous and best-effort delivery, but relay acceptance is neither peer-to-peer nor durable receipt. |
| WebDAV | Correct durable encrypted Mailbox, but not a simultaneous low-latency peer path. It complements rather than competes with WebRTC. |
| Native Messaging host | Could own raw sockets and discovery, but requires a separately installed OS-specific executable and lifecycle/security/update surface. It is a disproportionate prerequisite for the normal packaged extension. |
| Visible extension tab/side panel | Standards-capable and valuable as a prototype/diagnostic fallback, but direct sync would stop when the user closes it. |
| Former Chrome Apps socket APIs | They belong to the discontinued Chrome Apps platform and are not ordinary MV3 Extensions APIs ([Chrome Apps API reference](https://developer.chrome.com/docs/apps/reference)). |

## Decision for Pomo

Adopt **WebRTC DataChannel in a `WEB_RTC` offscreen document** as the complete Chrome-to-Chrome live peer adapter.

The adapter contract is:

1. Service worker and visible UI own orchestration, consent, diagnostics, and durable scheduling.
2. Offscreen document owns reconstructible WebRTC session state and durably stages ingress; only the common ingestion kernel may create a durable acknowledgement intent.
3. Encrypted, Device-signed Nostr messages perform replaceable rendezvous/signaling.
4. ICE tries direct host/STUN routes first; optional configurable TURN supplies live reachability where direct paths fail.
5. Pomo reports the selected route honestly and never calls TURN or Nostr delivery “direct.”
6. All routes enter the same authenticated ingestion pipeline and resume from durable causal frontiers.
7. WebDAV Mailboxes remain mandatory for the product's Protected-sync guarantee and all offline delivery.
8. No Pomo-operated signaling or TURN server is required by the protocol.

This satisfies the single “perfect version” target without pretending that every network permits direct browser-to-browser packets. The perfection criterion is seamless, observable convergence and no data loss—not claiming a physical direct path where NAT/firewall/privacy policy makes one impossible.

## Physical prototype gate

Before implementation is considered ready, a packaged Chrome extension at Pomo's minimum and current stable Chrome must prove:

1. `RTCPeerConnection` is absent from the service worker and works in a `WEB_RTC` offscreen document.
2. Two clean Chrome profiles establish an authenticated reliable DataChannel on the same LAN using mDNS-obfuscated host candidates.
3. Two distinct internet networks establish a server-reflexive direct path where possible and a configured TURN path where direct ICE is deliberately blocked.
4. The selected candidate-pair type is surfaced accurately as LAN, internet-direct, or relay.
5. Simultaneous offers converge using perfect negotiation; replayed/expired/wrong-recipient signals fail closed.
6. Worker termination during signaling or transfer does not lose an Operation or produce a false acknowledgement.
7. Closing/recreating the offscreen document, browser restart, extension reload/update, network handoff, ICE failure/restart, and relay reconnect resume from durable frontiers.
8. Large backlogs honor DataChannel backpressure and bounded batches rather than growing memory without limit.
9. Current LNA behavior—and allowed, denied, and revoked states once Chrome gates WebRTC—degrade only the direct route.
10. Mailbox synchronization continues when Nostr, STUN, TURN, or WebRTC is unavailable.

Unit and CI evidence must be reported separately from these real-browser, multi-profile, and multi-network observations.

## Sources

- [Chrome Offscreen API](https://developer.chrome.com/docs/extensions/reference/api/offscreen)
- [Chrome extension service-worker lifecycle](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle)
- [Chrome migration from background pages to service workers](https://developer.chrome.com/docs/extensions/develop/migrate/to-service-workers)
- [Chrome extension permissions](https://developer.chrome.com/docs/extensions/develop/concepts/declare-permissions)
- [Chrome Local Network Access](https://developer.chrome.com/blog/local-network-access)
- [Chrome privacy API: WebRTC IP handling policy](https://developer.chrome.com/docs/extensions/reference/api/privacy#property-network-webRTCIPHandlingPolicy)
- [W3C WebRTC Recommendation](https://www.w3.org/TR/webrtc/)
- [RFC 9429: JavaScript Session Establishment Protocol](https://www.rfc-editor.org/rfc/rfc9429.html)
- [RFC 8445: Interactive Connectivity Establishment](https://www.rfc-editor.org/rfc/rfc8445.html)
- [RFC 8827: WebRTC Security Architecture](https://www.rfc-editor.org/rfc/rfc8827.html)
- [RFC 8828: WebRTC IP Address Handling Requirements](https://www.rfc-editor.org/rfc/rfc8828.html)
- [RFC 8831: WebRTC Data Channels](https://www.rfc-editor.org/rfc/rfc8831.html)
- [RFC 8835: Transports for WebRTC](https://www.rfc-editor.org/rfc/rfc8835.html)
- [Chromium WebRTC mDNS responder implementation change](https://chromium.googlesource.com/chromium/src.git/+/ec2dbf9484712fed760ea13e1bb532f1a1dd7db5%5E%21/)
- [WebRTC mDNS responder interface](https://chromium.googlesource.com/external/webrtc/+/refs/heads/main/rtc_base/mdns_responder_interface.h)
