/*
 * THROWAWAY PROTOTYPE — not production synchronization code.
 *
 * This executable sketch makes the proposed OperationKernel interface concrete.
 * Cryptographic signature verification is represented by fixture booleans; the
 * production adapter must implement POMO-SUITE-1 and its full negative corpus.
 */

type Hex = string;
type FeedKey = `${Hex}:${Hex}`;
type Disposition =
  | "ACCEPTED"
  | "DUPLICATE"
  | "PENDING_GAP"
  | "PENDING_CAUSAL"
  | "QUARANTINED_FORK"
  | "REJECTED_INVALID";

type OperationKind = "HISTORY_APPEND" | "HISTORY_DELETE" | "PROFILE_SET";

interface FrontierEntry {
  readonly deviceId: Hex;
  readonly incarnationId: Hex;
  readonly sequence: number;
  readonly headHash: Hex;
}

interface UnsignedOperation {
  readonly suite: 1;
  readonly memberId: Hex;
  readonly deviceId: Hex;
  readonly incarnationId: Hex;
  readonly sequence: number;
  readonly previousHash: Hex | null;
  readonly frontier: readonly FrontierEntry[];
  readonly authorizationEpoch: number;
  readonly payloadSchema: number;
  readonly kind: OperationKind;
  readonly payloadHash: Hex;
}

interface SignedOperation {
  readonly unsigned: UnsignedOperation;
  readonly canonicalHex: Hex;
  readonly operationId: Hex;
  readonly contentIdentityValid: boolean;
  readonly signatureValid: boolean;
  readonly authorized: boolean;
}

interface AuthoringContext {
  readonly authorized: boolean;
  readonly deviceReady: boolean;
  readonly completePrerequisites: ReadonlySet<string>;
}

interface CheckpointFeed {
  readonly feed: FeedKey;
  readonly headHash: Hex;
  readonly coveredOperationIds: readonly (readonly [number, Hex])[];
}

interface Checkpoint {
  readonly valid: boolean;
  readonly feeds: readonly CheckpointFeed[];
}

interface FeedState {
  head: number;
  headHash: Hex | null;
  forkedAt: number | null;
  accepted: Map<number, SignedOperation>;
  candidates: Map<number, SignedOperation>;
  pending: Map<number, SignedOperation>;
  checkpointIds: Map<number, Hex>;
}

interface CausalSummary {
  readonly heads: readonly string[];
  readonly gaps: readonly string[];
  readonly waiting: readonly string[];
  readonly forks: readonly string[];
  readonly accepted: number;
  readonly pending: number;
  readonly quarantined: number;
}

const textEncoder = new TextEncoder();
const KIND_CODE: Readonly<Record<OperationKind, number>> = {
  HISTORY_APPEND: 1,
  HISTORY_DELETE: 2,
  PROFILE_SET: 3,
};

function bytes(hex: Hex): Uint8Array {
  if (hex.length % 2 !== 0 || !/^[0-9a-f]*$/.test(hex)) throw new Error("invalid lowercase hex");
  return Uint8Array.from(hex.match(/../g)?.map((value) => Number.parseInt(value, 16)) ?? []);
}

function hex(value: Uint8Array): Hex {
  return Array.from(value, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function concat(parts: readonly Uint8Array[]): Uint8Array {
  const output = new Uint8Array(parts.reduce((size, part) => size + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    output.set(part, offset);
    offset += part.length;
  }
  return output;
}

function cborHead(major: number, value: number): Uint8Array {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error("CBOR integer outside prototype profile");
  if (value < 24) return Uint8Array.of((major << 5) | value);
  if (value <= 0xff) return Uint8Array.of((major << 5) | 24, value);
  if (value <= 0xffff) return Uint8Array.of((major << 5) | 25, value >>> 8, value & 0xff);
  if (value <= 0xffffffff) {
    return Uint8Array.of(
      (major << 5) | 26,
      (value >>> 24) & 0xff,
      (value >>> 16) & 0xff,
      (value >>> 8) & 0xff,
      value & 0xff,
    );
  }
  const high = Math.floor(value / 0x1_0000_0000);
  const low = value >>> 0;
  return Uint8Array.of(
    (major << 5) | 27,
    (high >>> 24) & 0xff,
    (high >>> 16) & 0xff,
    (high >>> 8) & 0xff,
    high & 0xff,
    (low >>> 24) & 0xff,
    (low >>> 16) & 0xff,
    (low >>> 8) & 0xff,
    low & 0xff,
  );
}

type CborValue = number | string | Uint8Array | null | readonly CborValue[];

function encode(value: CborValue): Uint8Array {
  if (value === null) return Uint8Array.of(0xf6);
  if (typeof value === "number") return cborHead(0, value);
  if (typeof value === "string") {
    const encoded = textEncoder.encode(value);
    return concat([cborHead(3, encoded.length), encoded]);
  }
  if (value instanceof Uint8Array) return concat([cborHead(2, value.length), value]);
  return concat([cborHead(4, value.length), ...value.map(encode)]);
}

function canonicalUnsigned(operation: UnsignedOperation): Uint8Array {
  const frontier: readonly CborValue[] = [...operation.frontier]
    .sort((left, right) => {
      const a = `${left.deviceId}:${left.incarnationId}`;
      const b = `${right.deviceId}:${right.incarnationId}`;
      return a.localeCompare(b);
    })
    .map((entry) => [
      bytes(entry.deviceId),
      bytes(entry.incarnationId),
      entry.sequence,
      bytes(entry.headHash),
    ]);
  return encode([
    operation.suite,
    bytes(operation.memberId),
    bytes(operation.deviceId),
    bytes(operation.incarnationId),
    operation.sequence,
    operation.previousHash === null ? null : bytes(operation.previousHash),
    frontier,
    operation.authorizationEpoch,
    operation.payloadSchema,
    KIND_CODE[operation.kind],
    bytes(operation.payloadHash),
  ]);
}

async function sha256(value: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", value));
}

async function operationId(canonical: Uint8Array): Promise<Hex> {
  return hex(await sha256(encode(["Pomo Operation ID", 1, canonical])));
}

async function makeOperation(
  input: Omit<UnsignedOperation, "suite" | "payloadHash"> & { readonly payload: string },
  overrides: Partial<Pick<SignedOperation, "contentIdentityValid" | "signatureValid" | "authorized">> = {},
): Promise<SignedOperation> {
  const payloadHash = hex(await sha256(textEncoder.encode(input.payload)));
  const unsigned: UnsignedOperation = { ...input, suite: 1, payloadHash };
  const canonical = canonicalUnsigned(unsigned);
  return {
    unsigned,
    canonicalHex: hex(canonical),
    operationId: await operationId(canonical),
    contentIdentityValid: overrides.contentIdentityValid ?? true,
    signatureValid: overrides.signatureValid ?? true,
    authorized: overrides.authorized ?? true,
  };
}

class OperationKernel {
  readonly #feeds = new Map<FeedKey, FeedState>();
  readonly #knownIds = new Set<Hex>();
  readonly #quarantined = new Set<Hex>();

  author(kind: OperationKind, context: AuthoringContext): "AUTHORIZED" | "BLOCKED_PREREQUISITE" {
    if (!context.authorized) return "BLOCKED_PREREQUISITE";
    const prerequisites: Readonly<Record<OperationKind, readonly string[]>> = {
      HISTORY_APPEND: ["AUTHORIZATION", "ACTIVE_PHASE_CHAIN"],
      HISTORY_DELETE: ["AUTHORIZATION", "FULL_HISTORY", "INDEPENDENT_CONFIRMATION"],
      PROFILE_SET: ["AUTHORIZATION", "PROFILE_FRONTIER"],
    };
    if (kind === "HISTORY_DELETE" && !context.deviceReady) return "BLOCKED_PREREQUISITE";
    return prerequisites[kind].every((item) => context.completePrerequisites.has(item))
      ? "AUTHORIZED"
      : "BLOCKED_PREREQUISITE";
  }

  ingest(operation: SignedOperation): Disposition {
    if (!operation.contentIdentityValid || !operation.signatureValid || !operation.authorized) return "REJECTED_INVALID";
    if (this.#knownIds.has(operation.operationId)) return "DUPLICATE";
    this.#knownIds.add(operation.operationId);

    const key = this.#feedKey(operation.unsigned);
    const feed = this.#feeds.get(key) ?? {
      head: 0,
      headHash: null,
      forkedAt: null,
      accepted: new Map(),
      candidates: new Map(),
      pending: new Map(),
      checkpointIds: new Map(),
    };
    this.#feeds.set(key, feed);

    const checkpointId = feed.checkpointIds.get(operation.unsigned.sequence);
    if (checkpointId && checkpointId !== operation.operationId) {
      this.#quarantineFork(feed, operation.unsigned.sequence, checkpointId, operation);
      return "QUARANTINED_FORK";
    }

    const existing = feed.candidates.get(operation.unsigned.sequence);
    if (existing && existing.operationId !== operation.operationId) {
      this.#quarantineFork(feed, operation.unsigned.sequence, existing.operationId, operation);
      return "QUARANTINED_FORK";
    }
    feed.candidates.set(operation.unsigned.sequence, operation);

    if (feed.forkedAt !== null && operation.unsigned.sequence >= feed.forkedAt) {
      this.#quarantined.add(operation.operationId);
      return "QUARANTINED_FORK";
    }
    if (operation.unsigned.sequence !== feed.head + 1) {
      feed.pending.set(operation.unsigned.sequence, operation);
      return "PENDING_GAP";
    }
    if (operation.unsigned.previousHash !== feed.headHash) return "REJECTED_INVALID";
    if (!this.#causalReady(operation)) {
      feed.pending.set(operation.unsigned.sequence, operation);
      return "PENDING_CAUSAL";
    }

    this.#accept(feed, operation);
    this.#drainAll();
    return "ACCEPTED";
  }

  summarize(): CausalSummary {
    const heads: string[] = [];
    const gaps: string[] = [];
    const waiting: string[] = [];
    const forks: string[] = [];
    let accepted = 0;
    let pending = 0;
    for (const [key, feed] of [...this.#feeds].sort(([a], [b]) => a.localeCompare(b))) {
      heads.push(`${key}@${feed.head}:${feed.headHash ?? "genesis"}`);
      accepted += feed.checkpointIds.size + feed.accepted.size;
      pending += feed.pending.size;
      if (feed.pending.size > 0) {
        if (feed.pending.has(feed.head + 1)) waiting.push(`${key}@${feed.head + 1}`);
        else gaps.push(`${key}@${feed.head + 1}`);
      }
      if (feed.forkedAt !== null) forks.push(`${key}@${feed.forkedAt}`);
    }
    return { heads, gaps, waiting, forks, accepted, pending, quarantined: this.#quarantined.size };
  }

  restore(checkpoint: Checkpoint, trailing: readonly SignedOperation[]): "RESTORED" | "REJECTED_CHECKPOINT" {
    if (!checkpoint.valid) return "REJECTED_CHECKPOINT";
    const restoredFeeds = new Map<FeedKey, FeedState>();
    for (const checkpointFeed of checkpoint.feeds) {
      const covered = new Map(checkpointFeed.coveredOperationIds);
      const sequence = checkpointFeed.coveredOperationIds.length;
      const contiguous = checkpointFeed.coveredOperationIds.every(([position], index) => position === index + 1);
      if (!contiguous || covered.size !== sequence || (sequence === 0) !== (checkpointFeed.headHash === "") || (sequence > 0 && covered.get(sequence) !== checkpointFeed.headHash)) {
        return "REJECTED_CHECKPOINT";
      }
      restoredFeeds.set(checkpointFeed.feed, {
        head: sequence,
        headHash: sequence === 0 ? null : checkpointFeed.headHash,
        forkedAt: null,
        accepted: new Map(),
        candidates: new Map(),
        pending: new Map(),
        checkpointIds: covered,
      });
    }
    this.#feeds.clear();
    this.#knownIds.clear();
    this.#quarantined.clear();
    for (const [key, feed] of restoredFeeds) {
      this.#feeds.set(key, feed);
      for (const operationId of feed.checkpointIds.values()) this.#knownIds.add(operationId);
    }
    for (const operation of trailing) this.ingest(operation);
    return "RESTORED";
  }

  #feedKey(operation: UnsignedOperation): FeedKey {
    return `${operation.deviceId}:${operation.incarnationId}`;
  }

  #accept(feed: FeedState, operation: SignedOperation): void {
    feed.pending.delete(operation.unsigned.sequence);
    feed.accepted.set(operation.unsigned.sequence, operation);
    feed.head = operation.unsigned.sequence;
    feed.headHash = operation.operationId;
  }

  #causalReady(operation: SignedOperation): boolean {
    return operation.unsigned.frontier.every((entry) => {
      const dependency = this.#feeds.get(`${entry.deviceId}:${entry.incarnationId}`);
      if (!dependency || dependency.head < entry.sequence) return false;
      const observed = dependency.accepted.get(entry.sequence)?.operationId ?? dependency.checkpointIds.get(entry.sequence);
      return observed === entry.headHash;
    });
  }

  #drainAll(): void {
    let advanced = true;
    while (advanced) {
      advanced = false;
      for (const feed of this.#feeds.values()) {
        const next = feed.pending.get(feed.head + 1);
        if (next && next.unsigned.previousHash === feed.headHash && this.#causalReady(next)) {
          this.#accept(feed, next);
          advanced = true;
        }
      }
    }
  }

  #quarantineFork(
    feed: FeedState,
    sequence: number,
    existingId: Hex,
    incoming: SignedOperation,
  ): void {
    feed.forkedAt = feed.forkedAt === null ? sequence : Math.min(feed.forkedAt, sequence);
    this.#quarantined.add(existingId);
    this.#quarantined.add(incoming.operationId);
    for (const [position, operationId] of feed.checkpointIds) {
      if (position >= feed.forkedAt) {
        feed.checkpointIds.delete(position);
        this.#quarantined.add(operationId);
      }
    }
    for (const [position, operation] of feed.accepted) {
      if (position >= feed.forkedAt) {
        feed.accepted.delete(position);
        this.#quarantined.add(operation.operationId);
      }
    }
    for (const [position, operation] of feed.pending) {
      if (position >= feed.forkedAt) {
        feed.pending.delete(position);
        this.#quarantined.add(operation.operationId);
      }
    }
    const prefix = feed.accepted.get(feed.forkedAt - 1);
    feed.head = feed.forkedAt - 1;
    feed.headHash = prefix?.operationId ?? feed.checkpointIds.get(feed.head) ?? null;
  }
}

const MEMBER = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
const DEVICE = "1000000000000000000000000000000000000000000000000000000000000001";
const INCARNATION = "20000000000000000000000000000001";
const DEVICE_B = "1000000000000000000000000000000000000000000000000000000000000002";
const INCARNATION_B = "20000000000000000000000000000002";
const FEED: FeedKey = `${DEVICE}:${INCARNATION}`;
const FEED_B: FeedKey = `${DEVICE_B}:${INCARNATION_B}`;

async function fixtures(): Promise<{
  readonly a1: SignedOperation;
  readonly b1: SignedOperation;
  readonly a2: SignedOperation;
  readonly a3: SignedOperation;
  readonly a2Fork: SignedOperation;
}> {
  const base = {
    memberId: MEMBER,
    deviceId: DEVICE,
    incarnationId: INCARNATION,
    frontier: [] as const,
    authorizationEpoch: 3,
    payloadSchema: 1,
    kind: "HISTORY_APPEND" as const,
  };
  const a1 = await makeOperation({ ...base, sequence: 1, previousHash: null, payload: "work:alpha" });
  const b1 = await makeOperation({ ...base, deviceId: DEVICE_B, incarnationId: INCARNATION_B, sequence: 1, previousHash: null, payload: "tag:deep-work" });
  const observesB1 = [{ deviceId: DEVICE_B, incarnationId: INCARNATION_B, sequence: 1, headHash: b1.operationId }] as const;
  const a2 = await makeOperation({ ...base, frontier: observesB1, sequence: 2, previousHash: a1.operationId, payload: "work:beta" });
  const a3 = await makeOperation({ ...base, frontier: observesB1, sequence: 3, previousHash: a2.operationId, payload: "work:gamma" });
  const a2Fork = await makeOperation({ ...base, frontier: observesB1, sequence: 2, previousHash: a1.operationId, payload: "work:fork" });
  return { a1, b1, a2, a3, a2Fork };
}

function compact(summary: CausalSummary): string {
  return [
    `heads=${summary.heads.join(",")}`,
    `gaps=${summary.gaps.join(",") || "none"}`,
    `waiting=${summary.waiting.join(",") || "none"}`,
    `forks=${summary.forks.join(",") || "none"}`,
    `accepted=${summary.accepted}`,
    `pending=${summary.pending}`,
    `quarantined=${summary.quarantined}`,
  ].join(";");
}

async function main(): Promise<void> {
  const { a1, b1, a2, a3, a2Fork } = await fixtures();
  console.log("suite=1");
  console.log(`a1.cbor=${a1.canonicalHex}`);
  console.log(`a1.id=${a1.operationId}`);
  console.log(`invalid.contentIdentity=${new OperationKernel().ingest({ ...a1, contentIdentityValid: false })}`);

  const reorder = new OperationKernel();
  console.log(`reorder.dispositions=${[reorder.ingest(a3), reorder.ingest(a1), reorder.ingest(a1), reorder.ingest(a2), reorder.ingest(b1)].join(",")}`);
  console.log(`reorder.summary=${compact(reorder.summarize())}`);

  const fork = new OperationKernel();
  console.log(`fork.dispositions=${[fork.ingest(a1), fork.ingest(b1), fork.ingest(a2), fork.ingest(a2Fork)].join(",")}`);
  console.log(`fork.summary=${compact(fork.summarize())}`);

  const restored = new OperationKernel();
  const checkpoint: Checkpoint = { valid: true, feeds: [
    { feed: FEED, headHash: a2.operationId, coveredOperationIds: [[1, a1.operationId], [2, a2.operationId]] },
    { feed: FEED_B, headHash: b1.operationId, coveredOperationIds: [[1, b1.operationId]] },
  ] };
  console.log(`checkpoint.restore=${restored.restore(checkpoint, [a3])}`);
  console.log(`checkpoint.summary=${compact(restored.summarize())}`);
  console.log(`checkpoint.tampered=${new OperationKernel().restore({ ...checkpoint, valid: false }, [a3])}`);

  const incomplete: AuthoringContext = {
    authorized: true,
    deviceReady: false,
    completePrerequisites: new Set(["AUTHORIZATION", "ACTIVE_PHASE_CHAIN"]),
  };
  const full: AuthoringContext = {
    authorized: true,
    deviceReady: true,
    completePrerequisites: new Set(["AUTHORIZATION", "ACTIVE_PHASE_CHAIN", "FULL_HISTORY", "INDEPENDENT_CONFIRMATION", "PROFILE_FRONTIER"]),
  };
  const authoring = new OperationKernel();
  console.log(`author.incomplete.append=${authoring.author("HISTORY_APPEND", incomplete)}`);
  console.log(`author.incomplete.delete=${authoring.author("HISTORY_DELETE", incomplete)}`);
  console.log(`author.incomplete.profile=${authoring.author("PROFILE_SET", incomplete)}`);
  console.log(`author.full.delete=${authoring.author("HISTORY_DELETE", full)}`);
}

await main();
