import { canonicalUnsignedOperation, assertOperationIdentity, compareBytes, operationId, payloadHash } from "../protocol/operation";
import { hexToBytes } from "../../shared/hex";
import {
  OperationKind,
  POMO_SUITE_1,
  POMO_SUITE_GENERATION_1,
  type AuthenticatedOperation,
  type FeedKey,
  type FrontierEntry,
  type KernelSummary,
  type OperationDisposition,
  type UnsignedOperation,
  type VerifiedCheckpoint,
} from "../protocol/types";

export interface OperationVerifier {
  verify(signedEnvelope: Uint8Array): Promise<AuthenticatedOperation>;
}

export interface OperationSigner {
  sign(operation: UnsignedOperation, payload: Uint8Array, canonicalUnsigned: Uint8Array, operationId: string): Promise<Uint8Array>;
}

export interface OperationJournal {
  record(operation: AuthenticatedOperation, disposition: OperationDisposition): Promise<void>;
}

export interface OperationMaterializer {
  validate(operation: AuthenticatedOperation): void;
  /** Implementations must validate the complete replacement before changing visible state. */
  replace(
    checkpointPreferences: readonly { readonly key: string; readonly value: string }[],
    operations: readonly AuthenticatedOperation[],
  ): void;
}

export interface AuthorRequest {
  readonly memberId: string;
  readonly deviceId: string;
  readonly incarnationId: string;
  readonly authorizationEpoch: number;
  readonly frontier: readonly FrontierEntry[];
  readonly payload: Uint8Array;
  readonly completePrerequisites: ReadonlySet<string>;
  readonly authorized: boolean;
  readonly deviceReady: boolean;
}

export type AuthorResult =
  | { readonly status: "BLOCKED_PREREQUISITE"; readonly missing: ReadonlySet<string> }
  | { readonly status: "AUTHORED"; readonly operation: AuthenticatedOperation; readonly disposition: OperationDisposition };

interface FeedState {
  head: number;
  headHash: string | null;
  forkedAt: number | null;
  accepted: Map<number, AuthenticatedOperation>;
  candidates: Map<number, AuthenticatedOperation>;
  pending: Map<number, AuthenticatedOperation>;
  checkpointIds: Map<number, string>;
}

export class OperationKernel {
  readonly #feeds = new Map<FeedKey, FeedState>();
  readonly #knownIds = new Set<string>();
  readonly #quarantined = new Set<string>();
  readonly #checkpointPreferences = new Map<string, string>();

  constructor(
    private readonly verifier: OperationVerifier,
    private readonly signer: OperationSigner,
    private readonly journal: OperationJournal,
    private readonly materializer: OperationMaterializer,
  ) {}

  async author(request: AuthorRequest): Promise<AuthorResult> {
    const missing = new Set<string>();
    if (!request.authorized) missing.add("AUTHORIZATION");
    if (!request.deviceReady) missing.add("DEVICE_READY");
    if (!request.completePrerequisites.has("PROFILE_FRONTIER")) missing.add("PROFILE_FRONTIER");
    if (missing.size > 0) return { status: "BLOCKED_PREREQUISITE", missing };
    const feed = this.#feeds.get(this.#feedKey(request));
    if (feed?.forkedAt !== null && feed?.forkedAt !== undefined) return { status: "BLOCKED_PREREQUISITE", missing: new Set(["UNFORKED_FEED"]) };
    if (feed !== undefined && feed.pending.size > 0) return { status: "BLOCKED_PREREQUISITE", missing: new Set(["CONTIGUOUS_FEED"]) };
    const operation: UnsignedOperation = {
      suite: POMO_SUITE_1,
      suiteGeneration: POMO_SUITE_GENERATION_1,
      memberId: request.memberId,
      deviceId: request.deviceId,
      incarnationId: request.incarnationId,
      sequence: (feed?.head ?? 0) + 1,
      previousHash: feed?.headHash ?? null,
      frontier: [...request.frontier].sort((left, right) =>
        compareBytes(hexToBytes(left.deviceId), hexToBytes(right.deviceId)) ||
        compareBytes(hexToBytes(left.incarnationId), hexToBytes(right.incarnationId))),
      authorizationEpoch: request.authorizationEpoch,
      payloadSchema: 1,
      kind: OperationKind.SharedPreferenceSet,
      payloadHash: await payloadHash(request.payload),
    };
    const canonical = canonicalUnsignedOperation(operation);
    const id = await operationId(canonical);
    const envelope = await this.signer.sign(operation, request.payload, canonical, id);
    const disposition = await this.ingest(envelope);
    if (disposition === "REJECTED_INVALID") throw new Error("locally authored Operation failed verification");
    return {
      status: "AUTHORED",
      operation: { operationId: id, unsigned: operation, payload: request.payload, canonicalUnsigned: canonical, signedEnvelope: envelope },
      disposition,
    };
  }

  async ingest(signedEnvelope: Uint8Array): Promise<OperationDisposition> {
    let operation: AuthenticatedOperation;
    try {
      operation = await this.verifier.verify(signedEnvelope);
      await assertOperationIdentity(operation.unsigned, operation.payload, operation.canonicalUnsigned, operation.operationId);
      this.materializer.validate(operation);
    } catch {
      return "REJECTED_INVALID";
    }
    if (this.#knownIds.has(operation.operationId)) {
      await this.journal.record(operation, "DUPLICATE");
      return "DUPLICATE";
    }
    const key = this.#feedKey(operation.unsigned);
    const existingFeed = this.#feeds.get(key);
    const feed = existingFeed ?? this.#newFeed();

    const checkpointId = feed.checkpointIds.get(operation.unsigned.sequence);
    if (checkpointId !== undefined && checkpointId !== operation.operationId) {
      await this.journal.record(operation, "QUARANTINED_FORK");
      if (existingFeed === undefined) this.#feeds.set(key, feed);
      this.#knownIds.add(operation.operationId);
      this.#quarantineFork(feed, operation.unsigned.sequence, checkpointId, operation);
      this.#rematerialize();
      return "QUARANTINED_FORK";
    }
    const existing = feed.candidates.get(operation.unsigned.sequence);
    if (existing !== undefined && existing.operationId !== operation.operationId) {
      await this.journal.record(operation, "QUARANTINED_FORK");
      if (existingFeed === undefined) this.#feeds.set(key, feed);
      this.#knownIds.add(operation.operationId);
      this.#quarantineFork(feed, operation.unsigned.sequence, existing.operationId, operation);
      this.#rematerialize();
      return "QUARANTINED_FORK";
    }
    let disposition: OperationDisposition;
    if (feed.forkedAt !== null && operation.unsigned.sequence >= feed.forkedAt) {
      disposition = "QUARANTINED_FORK";
      await this.journal.record(operation, disposition);
      if (existingFeed === undefined) this.#feeds.set(key, feed);
      this.#knownIds.add(operation.operationId);
      feed.candidates.set(operation.unsigned.sequence, operation);
      this.#quarantined.add(operation.operationId);
    } else if (operation.unsigned.sequence !== feed.head + 1) {
      disposition = "PENDING_GAP";
      await this.journal.record(operation, disposition);
      if (existingFeed === undefined) this.#feeds.set(key, feed);
      this.#knownIds.add(operation.operationId);
      feed.candidates.set(operation.unsigned.sequence, operation);
      feed.pending.set(operation.unsigned.sequence, operation);
    } else if (operation.unsigned.previousHash !== feed.headHash) {
      disposition = "REJECTED_INVALID";
      await this.journal.record(operation, disposition);
    } else if (!this.#causalReady(operation)) {
      disposition = "PENDING_CAUSAL";
      await this.journal.record(operation, disposition);
      if (existingFeed === undefined) this.#feeds.set(key, feed);
      this.#knownIds.add(operation.operationId);
      feed.candidates.set(operation.unsigned.sequence, operation);
      feed.pending.set(operation.unsigned.sequence, operation);
    } else {
      disposition = "ACCEPTED";
      await this.journal.record(operation, disposition);
      if (existingFeed === undefined) this.#feeds.set(key, feed);
      this.#knownIds.add(operation.operationId);
      feed.candidates.set(operation.unsigned.sequence, operation);
      this.#accept(feed, operation);
      this.#drainAll();
      this.#rematerialize();
    }
    return disposition;
  }

  summarize(): KernelSummary {
    const heads = new Map<FeedKey, { sequence: number; headHash: string | null }>();
    const gaps = new Set<string>();
    const causalWaits = new Set<string>();
    const forks = new Set<string>();
    let accepted = 0;
    let pending = 0;
    for (const [key, feed] of [...this.#feeds].sort(([left], [right]) => this.#compareFeedKeys(left, right))) {
      heads.set(key, { sequence: feed.head, headHash: feed.headHash });
      accepted += feed.checkpointIds.size + feed.accepted.size;
      pending += feed.pending.size;
      if (feed.pending.size > 0) {
        if (feed.pending.has(feed.head + 1)) causalWaits.add(`${key}@${feed.head + 1}`);
        else gaps.add(`${key}@${feed.head + 1}`);
      }
      if (feed.forkedAt !== null) forks.add(`${key}@${feed.forkedAt}`);
    }
    return { heads, gaps, causalWaits, forks, accepted, pending, quarantined: this.#quarantined.size };
  }

  async restore(checkpoint: VerifiedCheckpoint, trailing: readonly Uint8Array[]): Promise<"RESTORED" | "REJECTED_CHECKPOINT"> {
    if (checkpoint.suite !== POMO_SUITE_1 || checkpoint.suiteGeneration !== POMO_SUITE_GENERATION_1) return "REJECTED_CHECKPOINT";
    let checkpointPreferences: Map<string, string>;
    try {
      checkpointPreferences = this.#validateCheckpointPreferences(checkpoint.materializedPreferences);
    } catch {
      return "REJECTED_CHECKPOINT";
    }
    const restored = new Map<FeedKey, FeedState>();
    for (const checkpointFeed of checkpoint.feeds) {
      try {
        this.#requireCheckpointFeed(checkpointFeed.deviceId, checkpointFeed.incarnationId, checkpointFeed.coveredOperationIds);
      } catch {
        return "REJECTED_CHECKPOINT";
      }
      const feedKey = this.#feedKey(checkpointFeed);
      if (restored.has(feedKey)) return "REJECTED_CHECKPOINT";
      const covered = new Map(checkpointFeed.coveredOperationIds.map((id, index) => [index + 1, id]));
      const count = checkpointFeed.coveredOperationIds.length;
      restored.set(feedKey, { ...this.#newFeed(), head: count, headHash: covered.get(count) ?? null, checkpointIds: covered });
    }
    const staged = new OperationKernel(this.verifier, this.signer, { record: async () => {} }, {
      validate: (operation) => this.materializer.validate(operation),
      replace: () => {},
    });
    for (const [key, feed] of restored) {
      staged.#feeds.set(key, feed);
      for (const id of feed.checkpointIds.values()) staged.#knownIds.add(id);
    }
    for (const [key, value] of checkpointPreferences) staged.#checkpointPreferences.set(key, value);
    for (const envelope of trailing) {
      if (await staged.ingest(envelope) !== "ACCEPTED") return "REJECTED_CHECKPOINT";
    }
    const materialized = staged.#acceptedInMaterializationOrder();
    try { this.materializer.replace(staged.#checkpointPreferenceEntries(), materialized); } catch { return "REJECTED_CHECKPOINT"; }
    this.#feeds.clear();
    for (const [key, feed] of staged.#feeds) this.#feeds.set(key, this.#cloneFeed(feed));
    this.#knownIds.clear();
    for (const id of staged.#knownIds) this.#knownIds.add(id);
    this.#quarantined.clear();
    for (const id of staged.#quarantined) this.#quarantined.add(id);
    this.#checkpointPreferences.clear();
    for (const [key, value] of staged.#checkpointPreferences) this.#checkpointPreferences.set(key, value);
    return "RESTORED";
  }

  #cloneFeed(feed: FeedState): FeedState {
    return {
      head: feed.head,
      headHash: feed.headHash,
      forkedAt: feed.forkedAt,
      accepted: new Map(feed.accepted),
      candidates: new Map(feed.candidates),
      pending: new Map(feed.pending),
      checkpointIds: new Map(feed.checkpointIds),
    };
  }

  #newFeed(): FeedState {
    return { head: 0, headHash: null, forkedAt: null, accepted: new Map(), candidates: new Map(), pending: new Map(), checkpointIds: new Map() };
  }

  #feedKey(value: { readonly deviceId: string; readonly incarnationId: string }): FeedKey {
    return `${value.deviceId}:${value.incarnationId}`;
  }

  #compareFeedKeys(left: FeedKey, right: FeedKey): number {
    const [leftDevice, leftIncarnation] = left.split(":") as [string, string];
    const [rightDevice, rightIncarnation] = right.split(":") as [string, string];
    return compareBytes(hexToBytes(leftDevice), hexToBytes(rightDevice)) || compareBytes(hexToBytes(leftIncarnation), hexToBytes(rightIncarnation));
  }

  #requireCheckpointFeed(deviceId: string, incarnationId: string, ids: readonly string[]): void {
    if (!/^[0-9a-f]{64}$/.test(deviceId) || !/^[0-9a-f]{32}$/.test(incarnationId)) throw new Error("invalid checkpoint feed");
    const unique = new Set(ids);
    if (unique.size !== ids.length || ids.some((id) => !/^[0-9a-f]{64}$/.test(id))) throw new Error("invalid checkpoint coverage");
  }

  #validateCheckpointPreferences(
    preferences: readonly { readonly key: string; readonly value: string }[],
  ): Map<string, string> {
    const validated = new Map<string, string>();
    let previousKey: string | undefined;
    for (const preference of preferences) {
      this.#requireCanonicalText(preference.key, 1, 128, "checkpoint preference key");
      this.#requireCanonicalText(preference.value, 0, 4096, "checkpoint preference value");
      if (previousKey !== undefined && this.#compareUtf8(previousKey, preference.key) >= 0) {
        throw new Error("checkpoint preferences must have unique canonical key order");
      }
      validated.set(preference.key, preference.value);
      previousKey = preference.key;
    }
    return validated;
  }

  #requireCanonicalText(value: string, minimumBytes: number, maximumBytes: number, name: string): void {
    const byteLength = new TextEncoder().encode(value).length;
    if (value.normalize("NFC") !== value || byteLength < minimumBytes || byteLength > maximumBytes) {
      throw new Error(`${name} is outside the canonical text profile`);
    }
  }

  #compareUtf8(left: string, right: string): number {
    return compareBytes(new TextEncoder().encode(left), new TextEncoder().encode(right));
  }

  #accept(feed: FeedState, operation: AuthenticatedOperation): void {
    feed.pending.delete(operation.unsigned.sequence);
    feed.accepted.set(operation.unsigned.sequence, operation);
    feed.head = operation.unsigned.sequence;
    feed.headHash = operation.operationId;
  }

  #causalReady(operation: AuthenticatedOperation): boolean {
    return operation.unsigned.frontier.every((entry) => {
      const dependency = this.#feeds.get(this.#feedKey(entry));
      if (dependency === undefined || dependency.head < entry.sequence) return false;
      return (dependency.accepted.get(entry.sequence)?.operationId ?? dependency.checkpointIds.get(entry.sequence)) === entry.headHash;
    });
  }

  #drainAll(): void {
    let advanced = true;
    while (advanced) {
      advanced = false;
      for (const feed of this.#feeds.values()) {
        const next = feed.pending.get(feed.head + 1);
        if (next !== undefined && next.unsigned.previousHash !== feed.headHash) {
          feed.pending.delete(next.unsigned.sequence);
          feed.candidates.delete(next.unsigned.sequence);
          this.#knownIds.delete(next.operationId);
          advanced = true;
        } else if (next !== undefined && this.#causalReady(next)) {
          this.#accept(feed, next);
          advanced = true;
        }
      }
    }
  }

  #quarantineFork(feed: FeedState, sequence: number, existingId: string, incoming: AuthenticatedOperation): void {
    feed.forkedAt = feed.forkedAt === null ? sequence : Math.min(feed.forkedAt, sequence);
    this.#quarantined.add(existingId);
    this.#quarantined.add(incoming.operationId);
    let invalidatedCheckpoint = false;
    for (const [position, id] of feed.checkpointIds) if (position >= feed.forkedAt) {
      feed.checkpointIds.delete(position);
      this.#quarantined.add(id);
      invalidatedCheckpoint = true;
    }
    if (invalidatedCheckpoint) this.#checkpointPreferences.clear();
    for (const [position, operation] of feed.accepted) if (position >= feed.forkedAt) { feed.accepted.delete(position); this.#quarantined.add(operation.operationId); }
    for (const [position, operation] of feed.pending) if (position >= feed.forkedAt) { feed.pending.delete(position); this.#quarantined.add(operation.operationId); }
    feed.head = Math.min(feed.head, feed.forkedAt - 1);
    feed.headHash = feed.accepted.get(feed.head)?.operationId ?? feed.checkpointIds.get(feed.head) ?? null;
  }

  #rematerialize(): void {
    this.materializer.replace(this.#checkpointPreferenceEntries(), this.#acceptedInMaterializationOrder());
  }

  #checkpointPreferenceEntries(): readonly { readonly key: string; readonly value: string }[] {
    return [...this.#checkpointPreferences].map(([key, value]) => ({ key, value }));
  }

  #acceptedInMaterializationOrder(): AuthenticatedOperation[] {
    const accepted = [...this.#feeds.values()].flatMap((feed) => [...feed.accepted.values()]);
    accepted.sort((left, right) => compareBytes(hexToBytes(left.operationId), hexToBytes(right.operationId)));
    return accepted;
  }
}
