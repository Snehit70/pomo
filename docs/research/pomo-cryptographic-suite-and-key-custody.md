# Pomo cryptographic suite, canonical encoding, and key custody

## Question

Which concrete, interoperable cryptographic suite and canonical wire encoding
should Pomo use for Member recovery, Device signing and agreement, Operation
signatures, Content-epoch wrapping, recovery artifacts, manifests, and local key
custody across Android 8+ (API 26) and Chrome Manifest V3?

This report evaluates the platform and standards state as of **2026-08-10**. It
does not design new cryptographic primitives or implement production crypto.

## Decision

Define one indivisible, downgrade-resistant suite named **`POMO-SUITE-1`**:

| Purpose | Exact choice |
| --- | --- |
| Device and Recovery signatures | ECDSA P-256 with SHA-256, COSE `ESP256` (`-9`), fixed 64-byte `r || s`, low-S only |
| Device and Recovery agreement keys | P-256 ECDH |
| Content-epoch wrapping | RFC 9180 HPKE Base mode: DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM |
| Content encryption | AES-256-GCM, 96-bit nonce, 128-bit tag |
| General KDF | HKDF-SHA256 |
| Recovery passphrase KDF | Argon2id v1.3, `m=64 MiB`, `t=3`, `p=4`, 16-byte random salt, 32-byte output |
| Hashes and content identities | SHA-256 |
| Signed container | tagged COSE_Sign1, tag 18 |
| Canonical payload encoding | strict RFC 8949 Core Deterministic CBOR application profile |
| Randomness | Android `SecureRandom` / Keystore key generation; Web Crypto `generateKey()` and `getRandomValues()` |

The suite is deliberately conventional. P-256 is not as implementation-simple
as Ed25519/X25519, but it is the only choice among the two that preserves Pomo's
non-exportable Android signing-key goal throughout its current API 26+ support
range. Android documents P-256 ECDSA generation in `AndroidKeyStore` from API 23,
while `PURPOSE_AGREE_KEY` and Keystore ECDH arrive in API 31. Android 13's
KeyMint v2 adds Curve25519 for signing and agreement, too late to be the uniform
custody primitive for an API 26 application ([Android `KeyGenParameterSpec`](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec),
[API 31 `PURPOSE_AGREE_KEY`](https://developer.android.com/sdk/api_diff/31/changes/android.security.keystore.KeyProperties),
[AOSP Keystore history](https://source.android.com/docs/security/features/keystore)).

Chrome does not change that conclusion. Current Chromium implements Web Crypto
in renderer-process software using BoringSSL and explicitly has no
hardware-token support. Choosing Ed25519/X25519 would therefore improve encoding
ergonomics, but not Chrome's custody boundary, while forcing software private
keys on older Android releases ([Chromium Web Crypto README](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/components/webcrypto/README.md),
[Chromium Web Crypto algorithm sources](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/components/webcrypto/algorithms/)).

This is a **single target suite**, not a staged V1/V2 product plan. Algorithm
agility exists so a later security migration is possible; it is not runtime
cipher negotiation and does not permit opportunistic fallback.

## Why this suite

### Signature: P-256 instead of Ed25519

Both P-256 and Ed25519 are standardized, current choices. The deciding factor is
key custody, not abstract curve preference:

- Android's official Keystore example generates a P-256 signing key and uses
  `SHA256withECDSA`; private key material remains behind the Keystore boundary.
  The application targets API 26, so this works over the entire supported range
  ([Android `KeyGenParameterSpec`](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec),
  [Android cryptography guidance](https://developer.android.com/privacy-and-security/cryptography)).
- Android's Java EdEC/XEC APIs appear only on recent releases, and AOSP records
  hardware Curve25519 support as a KeyMint v2 / Android 13 addition. Shipping a
  library can make Ed25519 compute on API 26, but cannot turn that software key
  into a Keystore-held Ed25519 key ([Android API 33 interface diff](https://developer.android.com/sdk/api_diff/33/changes/pkg_java.security.interfaces),
  [AOSP Keystore history](https://source.android.com/docs/security/features/keystore)).
- Chrome supports P-256 ECDSA and ECDH through Web Crypto, and Chromium's current
  implementation converts its internal DER ECDSA result to the raw `r || s`
  representation required by Web Crypto and COSE
  ([Web Cryptography Level 2](https://www.w3.org/TR/webcrypto-2/),
  [Chromium ECDSA source](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/components/webcrypto/algorithms/ecdsa.cc)).

Use the new fully specified COSE algorithm identifier **`ESP256` (`-9`)**, not
the older polymorphic `ES256` (`-7`). RFC 9864 defines `ESP256` as exactly P-256
plus SHA-256, marks it Recommended, and deprecates polymorphic `ES256`. This
removes curve selection from message interpretation and directly reduces
cross-curve and downgrade ambiguity ([RFC 9864 sections 2.1 and 4.2](https://www.rfc-editor.org/rfc/rfc9864.html#section-2.1)).

COSE encodes a P-256 signature as 32-byte big-endian `r` followed by 32-byte
big-endian `s`; Android's ASN.1 DER output must be converted at the adapter
boundary. Pomo should additionally normalize generated signatures to low-S and
reject high-S input. That application rule gives one wire form for either of the
two mathematically valid ECDSA `s` values. Signature randomness must not enter
any object identity: `Operation ID` is the SHA-256 digest of the canonical
unsigned Operation payload, never of a signature or signed envelope
([RFC 9053 section 2.1](https://www.rfc-editor.org/rfc/rfc9053.html#section-2.1),
[FIPS 186-5](https://csrc.nist.gov/pubs/fips/186-5/final)).

Deterministic ECDSA is desirable where a software implementation controls nonce
generation, but it cannot be required from opaque Android Keystore or Web Crypto
implementations. RFC 6979 explains why deterministic nonces avoid dependence on
fresh per-signature randomness; interoperability must nevertheless depend only
on signature verification, not byte-for-byte repeatability
([RFC 6979](https://www.rfc-editor.org/rfc/rfc6979.html)).

### Agreement and key wrapping: standard HPKE, not a custom ECDH envelope

Give every Device Identity and Recovery authority a distinct P-256 agreement
key. Never reuse its signing key for ECDH. Wrap each fresh 32-byte Content-epoch
secret independently to every Authorized Device and the current Recovery
authority using RFC 9180 HPKE **Base mode** with:

- KEM `0x0010`: DHKEM(P-256, HKDF-SHA256)
- KDF `0x0001`: HKDF-SHA256
- AEAD `0x0001`: AES-128-GCM

This exact combination has normative RFC test vectors. AES-128 matches P-256's
security class, is natively available on both platforms, and avoids inventing a
Pomo-specific ECDH/HKDF/AES envelope. HPKE requires fresh ephemeral sender key
material, labeled KDF inputs, and P-256 public-point validation; implementations
must reject out-of-range points, points off the curve, infinity, and invalid DH
outputs ([RFC 9180 sections 7.1 and Appendix A.3](https://www.rfc-editor.org/rfc/rfc9180.html#appendix-A.3)).

Base mode authenticates the recipient binding but not the sender. That is
intentional: the immutable key-envelope or epoch manifest containing the HPKE
ciphertexts is itself signed by an Authorized Device or Recovery authority.
Using HPKE Auth mode would couple wrapping to another static agreement key and
duplicate the authorization signature without improving Pomo's causal ledger.

On Android API 31+, create the P-256 agreement private key with
`PURPOSE_AGREE_KEY` in `AndroidKeyStore`; the official example requires a fresh
ephemeral peer key per message and an HKDF after ECDH. On API 26-30, Keystore
does not expose that agreement purpose. Generate the agreement key in software,
encrypt its PKCS#8 private form under a non-exportable Keystore AES-256-GCM
wrapping key, and unwrap it only in process for HPKE. Report this honestly as
**software agreement custody**, never hardware-backed custody
([Android API 31 `PURPOSE_AGREE_KEY`](https://developer.android.com/reference/android/security/keystore/KeyProperties#PURPOSE_AGREE_KEY),
[Android ECDH example](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)).

Do not use raw ECDH output as a key, custom AES-KW composition, secp256k1, or the
existing Crew identity primitive for new synchronization. The legacy Crew and
backup formats remain migration inputs only.

### AEAD, hashing, and KDF

Encrypt journal packs, blobs, Checkpoints, and Mailbox objects with AES-256-GCM,
a 96-bit nonce, a 128-bit tag, and authenticated canonical headers. Android
recommends AES-GCM with 256-bit keys; Web Crypto supports AES-GCM directly
([Android cryptography guidance](https://developer.android.com/privacy-and-security/cryptography),
[Web Cryptography Level 2 AES-GCM](https://www.w3.org/TR/webcrypto-2/#aes-gcm)).

Nonce reuse under one AES-GCM key is catastrophic. Avoid one long-lived epoch
key plus uncoordinated random nonces. For every immutable encrypted object:

1. generate a 32-byte random object salt;
2. derive a unique AES-256 key from the Content epoch with HKDF-SHA256;
3. generate a 12-byte random nonce;
4. authenticate the exact canonical object header as AAD; and
5. store salt, nonce, ciphertext, and tag in the immutable envelope.

The HKDF `info` value is deterministic CBOR containing the suite, purpose,
Member ID, Content-epoch ID, object kind, and plaintext object identity. Separate
fixed purposes are used for object encryption, mailbox manifest authentication,
Recovery-file encryption, Recovery-archive encryption, and any future exporter.
RFC 5869 makes `info` the application/context binding input and warns that DH
output must go through Extract; it also states that HKDF is not a password KDF
([RFC 5869 sections 2 and 3](https://www.rfc-editor.org/rfc/rfc5869.html)).

Use SHA-256 for Operation IDs, feed hashes, pack hashes, blob hashes, state
roots, and complete-envelope hashes. Hash input is always a typed canonical
structure with a purpose label; never hash an ambiguous string concatenation.
AES-256 does not raise the whole suite above P-256's security strength, but it
is already native on both targets and keeps randomly generated Content epochs
at 32 bytes.

All random keys, salts, nonces, and identifiers come from platform CSPRNGs.
Web Crypto specifies `getRandomValues()` as cryptographically strong and tells
applications to use `generateKey()` for keys. Android recommends `SecureRandom`
and Keystore generation ([Web Cryptography Level 2 randomness](https://www.w3.org/TR/webcrypto-2/#Crypto-method-getRandomValues),
[Android security checklist](https://developer.android.com/privacy-and-security/security-tips)).

## Canonical wire encoding

### Strict deterministic CBOR profile

Use RFC 8949 Core Deterministic Encoding, not JSON stringification, JVM object
serialization, or a library's undocumented "canonical" option. RFC 8949 requires
shortest encodings and deterministic map ordering, and warns that duplicate map
keys and invalid UTF-8 can otherwise be interpreted differently by decoders
([RFC 8949 sections 4.2 and 5.3](https://www.rfc-editor.org/rfc/rfc8949.html#section-4.2)).

The Pomo profile is narrower than generic CBOR:

- definite-length arrays, maps, byte strings, and text strings only;
- unsigned integer field labels and fixed array positions defined by CDDL;
- integers only, bounded per field; no floats, NaN, bignums, decimals, or
  implicit JavaScript `number` coercion above `2^53-1`;
- valid UTF-8; domain text normalized and validated before encoding;
- no duplicate map keys, no unknown duplicate semantic fields, no indefinite
  lengths, and no alternate encodings of the same value;
- byte strings for IDs, hashes, public coordinates, and ciphertext, never hex or
  Base64 inside CBOR; and
- one tag policy per structure. Signed Operations use mandatory tag 18; payload
  data uses only explicitly registered Pomo tags, otherwise no tags.

Receivers first validate the encoded bytes against this profile, then decode.
For understood objects they re-encode and require byte equality before hashing
or signature verification. For unsupported future Operation payloads they
verify the containing signed bytes and retain/forward those exact bytes; they do
not decode and re-encode away unknown information.

### COSE_Sign1 profile

Use tagged COSE_Sign1 for one Device-authored Operation. RFC 9052 defines its
signature input as the deterministic CBOR `Sig_structure` containing the fixed
`"Signature1"` context, protected headers, external AAD, and payload. This gives
a standardized signing structure and prevents conversion to another COSE
signature type from verifying ([RFC 9052 sections 4.2-4.4](https://www.rfc-editor.org/rfc/rfc9052.html#section-4.2)).

Every Pomo COSE_Sign1 has:

- tag 18;
- an empty unprotected header map;
- protected `alg = -9` (`ESP256`);
- protected Pomo private-use labels for `suite = 1`, signed-object kind,
  schema/version, and the complete Device key ID;
- a non-detached canonical CBOR payload byte string; and
- fixed external AAD `Pomo/<object-kind>/1`, supplied by the object parser, not
  selected from attacker-controlled input.

`kid` is never treated as authority: the complete content-derived Device ID and
authorization lineage are checked from the signed payload and ledger. COSE
requires algorithm identifiers to be authenticated and duplicate protected
labels to be rejected; Pomo goes further and protects every interpretation-
critical field ([RFC 9052 section 3](https://www.rfc-editor.org/rfc/rfc9052.html#section-3)).

Public descriptors use deterministic COSE_Key-style EC2 P-256 fields with
exactly 32-byte `x` and `y` coordinates and explicit signing/agreement purpose.
Wire adapters convert this representation to the 65-byte uncompressed SEC1
point required by HPKE/Web Crypto or to platform SPKI objects. DER, JWK, provider
class names, aliases, and X.509 certificates are local adapter formats and never
signed protocol meaning.

## Domain separation and object identities

Every cryptographic input begins with structure, not prose concatenation:

- COSE signature domain: `Signature1`, protected suite/kind/version, and fixed
  Pomo external AAD;
- Operation ID: `SHA-256(deterministic-CBOR(["Pomo Operation ID", 1,
  unsigned-operation]))`;
- Device ID: hash of the canonical Device Descriptor containing both public
  keys, purposes, and suite;
- Member ID: hash of the canonical Genesis record, not a current key;
- Feed link: hash of a canonical tuple including Member, Device, incarnation,
  sequence, previous hash, and Operation ID;
- HKDF: structured `info` with a distinct purpose and every relevant identity;
  and
- AES-GCM: deterministic AAD containing suite, Member, epoch, object kind,
  plaintext identity, and encoding version.

Length-delimited deterministic CBOR avoids collisions such as `ab || c` versus
`a || bc`. No signature, ciphertext, provider encoding, wall-clock timestamp,
or transport envelope contributes to a domain object's stable identity.

## Recovery artifacts

### Recovery file

The new Recovery file is a small, self-describing encrypted envelope. It does
not reuse the current `pomo-recovery.v1` JSON/PBKDF2 format except as a bounded
legacy import format.

1. Process the passphrase using the RFC 8265 OpaqueString profile and encode it
   as UTF-8. OpaqueString preserves case and requires NFC normalization
   ([RFC 8265 section 4](https://www.rfc-editor.org/rfc/rfc8265.html#section-4)).
2. Derive 32 bytes using Argon2id v1.3 with `m=65536 KiB`, `t=3`, `p=4`, a
   random 16-byte salt, and a 32-byte output. This is RFC 9106's second
   recommended, memory-constrained profile
   ([RFC 9106 section 7.4](https://www.rfc-editor.org/rfc/rfc9106.html#section-7.4)).
3. HKDF-expand that master with a fixed Recovery-file purpose into an AES-256
   key. Encrypt the deterministic-CBOR secret payload using a random 12-byte
   nonce and a 128-bit GCM tag.
4. Authenticate the complete outer header as AAD: magic, file format, suite,
   Argon2 version and parameters, salt, nonce, Recovery generation, and a
   non-correlating file identifier.
5. Inside the ciphertext, bind Member ID, current Recovery public descriptor,
   private signing/agreement keys, latest known authorization frontier,
   Content-epoch recovery envelopes, Crew capabilities, and a checksum/root of
   the canonical secret manifest.

Parsers enforce exact minimum and maximum KDF parameters before allocating
memory. They never accept PBKDF2 because Argon2 is unavailable, silently reduce
memory after failure, or select parameters from local defaults. Wrong
passphrase, corrupted ciphertext, and wrong AAD yield the same failure class.
Recovery files carry no ordinary Device private key.

Argon2id is not native to Web Crypto. A Chrome implementation must bundle a
reviewed WASM build with the extension and perform recovery work in an
extension/offscreen context, never download code. Manifest V3 permits packaged
WASM with its minimum `wasm-unsafe-eval` CSP. The Argon2 reference implementation
exposes the time, memory, parallelism, version, and output parameters needed for
the exact profile and is the appropriate source for a pinned WASM build
([Chrome MV3 CSP](https://developer.chrome.com/docs/extensions/reference/manifest/content-security-policy),
[Argon2 reference implementation](https://github.com/P-H-C/phc-winner-argon2)). Android can use a
maintained Argon2id implementation such as Bouncy Castle's lightweight
`Argon2BytesGenerator`; the library publishes its maintained source and Java 8+
artifact ([Bouncy Castle source and artifacts](https://github.com/bcgit/bc-java),
[Argon2BytesGenerator API](https://downloads.bouncycastle.org/java/docs/bcjce-jdk13-javadoc/org/bouncycastle/crypto/generators/Argon2BytesGenerator.html)).

Both candidates remain implementation dependencies, not protocol authorities.
The prototype must prove exact RFC vector parity, parameter handling, memory
zeroing where available, bundle integrity, and acceptable 64 MiB behavior on
the oldest supported Android and Chrome targets before production use.

### Recovery archive

A portable Recovery archive uses a distinct file kind, HKDF purpose, salt,
nonce space, and format version. It may contain verified journal packs,
Checkpoints, blobs, and manifests, but it never reuses a Recovery-file AEAD key
or nonce. Large archives require a specified chunked AEAD framing with monotonic
chunk indexes, total-length/final-chunk authentication, and a manifest root;
they must not be passed as one unbounded in-memory AES-GCM operation.

### Checkpoints, epoch manifests, and Mailbox manifests

These artifacts are signed canonical objects, not trusted database exports:

- Checkpoint: suite, Member, authorization and Recovery generations, Content
  epochs, causal frontier, each Device feed head/hash, Materializer/schema
  versions, state roots, pack/blob references, and creator Device ID.
- Content-epoch manifest: prior epoch, authorization frontier that created it,
  random epoch ID, and one HPKE envelope per Authorized Device plus Recovery.
- Mailbox manifest: suite, Member mailbox pseudonym, manifest generation,
  protected frontier, immutable object hashes/sizes/kinds, and predecessor hash.

Sign the manifest payload with COSE_Sign1 and then encrypt private manifests as
ordinary Content-epoch objects. A signature does not make an incomplete
Checkpoint complete: receivers still verify feed continuity, referenced
objects, roots, authorization, and causal compatibility.

## Platform key custody

### Android API 26+

Use separate aliases and purposes:

| Secret | Custody |
| --- | --- |
| Device signing key | P-256 generated non-exportable in Android Keystore, sign-only, SHA-256 only |
| Device agreement key, API 31+ | P-256 generated non-exportable in Android Keystore, agree-only |
| Device agreement key, API 26-30 | software P-256 private key encrypted by a non-exportable Keystore AES-256-GCM key |
| Content epochs and capabilities | ciphertext under a separate Keystore AES-256-GCM local wrapping key; plaintext only while needed |
| Recovery private keys | only in a passphrase-opened Recovery file; never routine app storage |

Android says Keystore keys remain non-exportable and may be hardware-bound, but
also notes that a compromised app process can still ask the system to use them.
`KeyInfo.getSecurityLevel()` distinguishes software, TEE, and StrongBox
protection; record this only as local diagnostics, not as extra authorization
weight ([Android Keystore](https://developer.android.com/privacy-and-security/keystore),
[Android `KeyInfo`](https://developer.android.com/reference/android/security/keystore/KeyInfo)).

Request StrongBox opportunistically when supported, fall back cleanly when
unavailable, and do not require attestation. StrongBox is optional and supports
P-256 ECDSA/ECDH, but requiring it would exclude valid devices
([Android Keystore StrongBox support](https://developer.android.com/privacy-and-security/keystore#HardwareSecurityModule),
[StrongBoxUnavailableException](https://developer.android.com/reference/android/security/keystore/StrongBoxUnavailableException)).

Do not require biometric or device-credential authentication for ordinary
Device keys: background sync and timer finalization must proceed unattended.
This is a deliberate availability/security boundary. Recovery export and
destructive recovery actions may require an application-level confirmation,
but that does not change the Device-key authorization.

Place Device-private ciphertext and alias metadata in `noBackupFilesDir` and
explicitly exclude it from Android Auto Backup/device transfer. Auto Backup
otherwise includes shared preferences, internal files, and databases, while
`noBackupFilesDir` is excluded. Restoring ciphertext without its Keystore key
would produce a misleading half-restored identity
([Android Auto Backup](https://developer.android.com/identity/data/autobackup)).
Missing aliases, invalidated keys, or unwrap failure end that Device Identity;
they never trigger silent key regeneration under the same Device ID.

### Chrome Manifest V3

Generate P-256 ECDSA and ECDH keys using `crypto.subtle.generateKey(...,
extractable=false, ...)`. Store the serializable `CryptoKey` handles via
structured clone in the extension origin's IndexedDB; never export a raw local
wrapping key into `chrome.storage.local`. Web Crypto defines `CryptoKey` as
serializable, explains that `extractable=false` only blocks key export, and
warns that origin storage may be cleared and keys destroyed
([Web Cryptography Level 2 `CryptoKey`](https://www.w3.org/TR/webcrypto-2/#cryptokey-interface)).

IndexedDB is available to extension service workers and shared by trusted
extension-origin contexts. Request `unlimitedStorage` and persistent storage to
avoid quota eviction, but still treat deletion, uninstall, profile loss, and
extension-ID change as Device loss. Chrome documents that extension storage can
otherwise be evicted under pressure and that IndexedDB is available in service
workers ([Chrome extension storage](https://developer.chrome.com/docs/extensions/develop/concepts/storage-and-cookies)).

This is **software non-extractability**, not hardware custody. Chromium states
that Web Crypto runs in renderer-process software and intentionally lacks
hardware-backed-token support. A compromised or malicious extension update can
invoke the key even when it cannot export its bytes
([Chromium Web Crypto README](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/components/webcrypto/README.md),
[Web Crypto security considerations](https://www.w3.org/TR/webcrypto-2/#security-developers)).

Keep all private-key operations behind one extension-internal broker. Do not
expose private CryptoKeys or signing/decryption methods to content scripts,
externally connectable pages, or generic message forwarding. Treat MV3 service
worker suspension as normal: re-open IndexedDB and reconstruct pending work from
the durable journal on every event. A missing CryptoKey seals the old Replica
incarnation and starts authorization of a new Device Identity after recovery.

## Algorithm agility without downgrade

Pomo does **not negotiate algorithms per connection**. Transport peers exchange
opaque supported-suite metadata only to explain whether they can process an
object; the signed object's suite determines verification.

Rules:

1. `suite=1` names the entire vector in this report. Implementations may not
   substitute another curve, hash, KDF, AEAD, tag length, signature encoding,
   CBOR profile, or password parameters while retaining that number.
2. Genesis commits the initial suite. Every Operation, Device Descriptor,
   Recovery descriptor/file, Content-epoch manifest, Checkpoint, pack, and
   Mailbox manifest carries the suite inside authenticated meaning.
3. A future `AdoptCryptoSuite` authorization Operation is signed and accepted
   under the currently active suite. It carries a monotonically increasing
   **suite generation**, exact new suite ID, activation causal frontier, new
   Device/Recovery public descriptors, and proof of possession under the new
   signing keys.
4. After activation, current devices author only the new suite. Old-suite
   historical objects remain verifiable; old-suite Operations at or beyond the
   activation boundary are Pending or Quarantined according to causal context,
   never silently accepted through fallback.
5. Concurrent suite-adoption Operations quarantine. Numeric suite order,
   timestamp, arrival order, and “strongest mutually supported” do not select a
   winner.
6. Unsupported suites are retained byte-for-byte as Pending when their outer
   structure and size are safe to retain. They are never reinterpreted under a
   known suite.
7. Recovery files and archives carry suite generation and authorization
   frontier. A Replica that has observed a later generation rejects a stale
   file for authority; it may use it only as a clearly labeled source of
   historical ciphertext that is independently verified.
8. No PBKDF2, secp256k1, Ed25519, X25519, AES-CBC, shortened GCM tag, or
   non-deterministic encoding fallback is accepted for `POMO-SUITE-1`.

RFC 9864 motivates fully specified identifiers precisely because polymorphic
algorithms and à-la-carte negotiation leave mandatory choices ambiguous. Pomo's
suite generation and causal activation add the stateful anti-downgrade rule the
COSE identifier alone cannot provide
([RFC 9864 sections 1 and 3](https://www.rfc-editor.org/rfc/rfc9864.html)).

## Required cross-runtime evidence before implementation approval

The Operation kernel prototype is not complete until one checked-in golden
corpus is consumed by both Android and Chrome and produces byte-identical
results. It must contain:

1. RFC 5869 HKDF-SHA256 vectors.
2. RFC 9180 Appendix A.3 P-256 HPKE vectors, including intermediate labeled
   extract/expand values.
3. RFC 9106 Argon2id vectors plus the exact Pomo 64 MiB profile.
4. NIST AES-GCM and ECDSA verification vectors, including invalid cases.
5. deterministic-CBOR vectors for every Pomo type and COSE `Sig_structure`.
6. fixed test P-256 private keys producing Device descriptors, Operation
   payload bytes, Operation IDs, feed links, low-S signatures, HPKE envelopes,
   Content-epoch objects, Recovery files, Checkpoints, and manifests.
7. Android-DER to COSE-raw signature conversion in both directions for test
   fixtures only.
8. a negative corpus: high-S and wrong-length signatures, DER-on-wire
   signatures, wrong curve, invalid points/infinity, mismatched `alg`/suite,
   duplicate CBOR keys, non-minimal integers, indefinite items, floats, invalid
   UTF-8, unknown critical headers, wrong external AAD, modified ciphertext/tag,
   excessive Argon2 parameters, stale suite generation, and truncated objects.

Required execution matrix:

- Android API 26 and 30: Keystore signing plus software/wrapped agreement key;
- Android API 31: Keystore ECDH path;
- current Android with TEE, with StrongBox, and without StrongBox;
- current stable Chrome MV3: service-worker suspension/restart, browser restart,
  extension update with stable ID, storage-pressure simulation, explicit data
  clearing, uninstall/reinstall, and corrupted IndexedDB record;
- Android-to-Chrome and Chrome-to-Android creation/verification for every
  artifact; and
- independent parser fuzzing plus differential canonical-decode/re-encode
  tests, with every accepted understood object reproducing its original bytes.

The suite must also pass fault injection at every durability boundary: key
generation before descriptor commit, signature before Operation commit, epoch
generation before every recipient envelope exists, Recovery-file write before
rename, and key loss with journal/outbox intact. No failure may create the same
Device ID with new private keys or claim `Protected` without a verified recovery
path.

Do not make deterministic ECDSA signature bytes a golden assertion for
platform-generated signatures. Golden tests use fixed software test keys and
known signatures; platform-key tests assert verification, low-S normalization,
key non-exportability where promised, and stable public descriptors.

## Repository implications

The current repository is migration evidence, not the target cryptosystem:

- Android already wraps Crew identity and Crew secrets with a non-exportable
  Keystore AES key (`CrewSecretCipher`), which is a useful local-custody seam.
- Chrome's current keyring generates an **extractable** AES-GCM wrapping key,
  exports it, and stores it alongside wrapped identity material. That prevents
  corruption and casual inspection but is not a non-exportable Device-key
  boundary; the new kernel must use IndexedDB `CryptoKey` handles instead.
- Current recovery uses JSON, PBKDF2-HMAC-SHA256 at 600,000 iterations, and
  AES-GCM. Keep its bounded decoder only for explicit legacy import. New
  Recovery files use deterministic CBOR and Argon2id and must have a different
  magic/version.
- Current Crew identity is secp256k1 and Crew snapshots use AES-GCM. Neither
  algorithm identity nor current key material is silently promoted into the
  new Member/Device hierarchy. Migration creates explicit imported facts and
  fresh P-256 Device keys.

NodeMCU firmware, physical-device behavior, and the existing phone protocol are
outside this decision and remain untouched.

## Caveats and unresolved implementation proof

- P-256 adds DER/raw conversion and low-S normalization seams. Centralize them
  in one small cross-runtime crypto adapter and test them exhaustively.
- Android API 26-30 cannot keep the agreement private key inside Keystore during
  ECDH. The AES-wrapped software fallback is weaker and must be visible in local
  security diagnostics. Raising the minimum to API 31 would remove this
  exception, but the current application minimum is API 26.
- Chrome `extractable=false` is not hardware-backed and cannot defend against
  malicious extension code executing in the trusted extension origin. Mailbox
  redundancy and Recovery are therefore essential, not optional polish.
- Argon2id adds maintained code outside platform crypto. Bundle and pin it,
  verify its source/artifact integrity, benchmark real low-end targets, and do
  not silently lower parameters. If 64 MiB cannot run, Recovery creation/import
  must fail honestly until a separately versioned suite/profile is approved.
- The standards define primitives and containers, not Pomo's authorization
  semantics. Signature success never bypasses causal authorization, revocation,
  quarantine, expected-head, or incomplete-Replica rules.
- A later post-quantum migration will require a new suite generation and likely
  larger descriptors/envelopes. The current suite mechanism permits that; it
  must not speculate a hybrid algorithm into `POMO-SUITE-1` before both runtimes
  and custody paths are proven.

## Primary sources

- [RFC 8949: CBOR](https://www.rfc-editor.org/rfc/rfc8949.html)
- [RFC 9052: COSE structures](https://www.rfc-editor.org/rfc/rfc9052.html)
- [RFC 9053: COSE algorithms](https://www.rfc-editor.org/rfc/rfc9053.html)
- [RFC 9180: HPKE](https://www.rfc-editor.org/rfc/rfc9180.html)
- [RFC 9864: fully specified JOSE/COSE algorithms](https://www.rfc-editor.org/rfc/rfc9864.html)
- [RFC 5869: HKDF](https://www.rfc-editor.org/rfc/rfc5869.html)
- [RFC 6979: deterministic ECDSA](https://www.rfc-editor.org/rfc/rfc6979.html)
- [RFC 8265: PRECIS OpaqueString](https://www.rfc-editor.org/rfc/rfc8265.html)
- [RFC 9106: Argon2](https://www.rfc-editor.org/rfc/rfc9106.html)
- [FIPS 186-5: Digital Signature Standard](https://csrc.nist.gov/pubs/fips/186-5/final)
- [NIST SP 800-38D: AES-GCM](https://csrc.nist.gov/pubs/sp/800/38/d/final)
- [NIST SP 800-186: elliptic curves](https://csrc.nist.gov/pubs/sp/800/186/final)
- [Web Cryptography Level 2](https://www.w3.org/TR/webcrypto-2/)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android `KeyGenParameterSpec`](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)
- [AOSP hardware-backed Keystore history](https://source.android.com/docs/security/features/keystore)
- [Chrome extension storage](https://developer.chrome.com/docs/extensions/develop/concepts/storage-and-cookies)
- [Chrome MV3 content security policy](https://developer.chrome.com/docs/extensions/reference/manifest/content-security-policy)
- [Chromium Web Crypto implementation](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/components/webcrypto/)
- [Bouncy Castle Java source](https://github.com/bcgit/bc-java)
- [Argon2 reference implementation](https://github.com/P-H-C/phc-winner-argon2)
