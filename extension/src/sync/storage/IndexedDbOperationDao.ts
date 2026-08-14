import { req, tx } from "../../db/dao";
import {
  openDb,
  SYNC_DISPOSITION_EVENT_STORE,
  SYNC_FEED_HEAD_STORE,
  SYNC_OPERATION_STORE,
  SYNC_OUTBOX_STORE,
  SYNC_PREFERENCE_STORE,
} from "../../db/schema";
import { decodeSharedPreferenceFact } from "../materialize/sharedPreferences";
import { assertOperationIdentity, compareBytes } from "../protocol/operation";
import {
  POMO_SUITE_1,
  POMO_SUITE_GENERATION_1,
  type AuthenticatedOperation,
  type FeedKey,
  type OperationDisposition,
  type RejectedDisposition,
} from "../protocol/types";

const SYNC_STORES = [
  SYNC_OPERATION_STORE,
  SYNC_FEED_HEAD_STORE,
  SYNC_PREFERENCE_STORE,
  SYNC_OUTBOX_STORE,
  SYNC_DISPOSITION_EVENT_STORE,
] as const;

export type DurableDisposition = OperationDisposition;

export interface SyncOperationRow {
  readonly operationId: string;
  readonly memberId: string;
  readonly deviceId: string;
  readonly incarnationId: string;
  readonly feedKey: FeedKey;
  readonly sequence: number;
  readonly previousHash: string | null;
  readonly rawWire: Uint8Array;
  readonly preferenceKey: string;
  readonly preferenceValue: string;
  readonly disposition: DurableDisposition;
  readonly localAuthor: boolean;
}

export interface SyncFeedHeadRow {
  readonly feedKey: FeedKey;
  readonly deviceId: string;
  readonly incarnationId: string;
  readonly sequence: number;
  readonly operationId: string | null;
  readonly forkedAt: number | null;
}

export interface SyncPreferenceRow {
  readonly key: string;
  readonly value: string;
  readonly operationId: string;
}

export interface SyncOutboxRow {
  readonly operationId: string;
  readonly rawWire: Uint8Array;
  readonly state: "PENDING";
  readonly attemptCount: number;
}

export interface SyncDispositionEventRow {
  readonly id?: number;
  readonly operationId: string | null;
  readonly disposition: DurableDisposition;
  readonly rawWire: Uint8Array;
}

/** Authenticated kernel decision; every derived effect is computed inside the DAO transaction. */
export interface DurableCommit {
  readonly operation: AuthenticatedOperation;
  readonly disposition: DurableDisposition;
  readonly localAuthor: boolean;
}

export type CrashPoint =
  | "BEFORE_OPERATION"
  | "AFTER_OPERATION"
  | "AFTER_QUARANTINE"
  | "AFTER_FEED_HEAD"
  | "AFTER_PROJECTION_CLEAR"
  | "AFTER_PROJECTION"
  | "AFTER_OUTBOX"
  | "AFTER_DISPOSITION"
  | "BEFORE_COMMIT";

export type CrashInjector = (point: CrashPoint) => void;

export interface ReconstructedSyncState {
  readonly operations: readonly SyncOperationRow[];
  readonly feedHeads: readonly SyncFeedHeadRow[];
  readonly preferences: readonly SyncPreferenceRow[];
  readonly outbox: readonly SyncOutboxRow[];
  readonly dispositionCounts: Readonly<Record<DurableDisposition, number>>;
}

interface PreparedCommit {
  readonly input: DurableCommit;
  readonly row: SyncOperationRow;
}

function cloneBytes(value: Uint8Array): Uint8Array {
  return value.slice();
}

function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((byte, index) => byte === right[index]);
}

function requireHex(value: string, length: number, name: string): void {
  if (!new RegExp(`^[0-9a-f]{${length}}$`).test(value)) throw new Error(`${name} must be lowercase hex`);
}

function compareUtf8(left: string, right: string): number {
  return compareBytes(new TextEncoder().encode(left), new TextEncoder().encode(right));
}

function compareOperationIds(left: SyncOperationRow, right: SyncOperationRow): number {
  return left.operationId < right.operationId ? -1 : left.operationId > right.operationId ? 1 : 0;
}

function crash(injector: CrashInjector | undefined, point: CrashPoint): void {
  injector?.(point);
}

export class IndexedDbOperationDao {
  constructor(private readonly opener: () => Promise<IDBDatabase> = openDb) {}

  async commit(input: DurableCommit, injectCrash?: CrashInjector): Promise<"COMMITTED" | "DUPLICATE"> {
    return (await this.commitBatch([input], injectCrash))[0]!;
  }

  async commitBatch(
    inputs: readonly DurableCommit[],
    injectCrash?: CrashInjector,
  ): Promise<readonly ("COMMITTED" | "DUPLICATE")[]> {
    if (inputs.length === 0) return [];
    const prepared = await Promise.all(inputs.map((input) => this.#prepare(input)));
    return this.#withDb((db) => tx(db, [...SYNC_STORES], "readwrite", async (transaction) => {
      const operations = transaction.objectStore(SYNC_OPERATION_STORE);
      const heads = transaction.objectStore(SYNC_FEED_HEAD_STORE);
      const outbox = transaction.objectStore(SYNC_OUTBOX_STORE);
      const dispositions = transaction.objectStore(SYNC_DISPOSITION_EVENT_STORE);
      const results: Array<"COMMITTED" | "DUPLICATE"> = [];
      let projectionDirty = false;

      for (const { input, row: preparedRow } of prepared) {
        let row = preparedRow;
        const existing = await req<SyncOperationRow | undefined>(operations.get(row.operationId));
        crash(injectCrash, "BEFORE_OPERATION");
        if (existing !== undefined && (!equalBytes(existing.rawWire, row.rawWire) || existing.feedKey !== row.feedKey || existing.sequence !== row.sequence)) {
          throw new Error("Operation ID collision has different authenticated bytes");
        }

        if (input.disposition === "DUPLICATE") {
          if (existing === undefined) throw new Error("cannot record a duplicate for an unknown Operation");
          await this.#recordDisposition(dispositions, row, "DUPLICATE");
          crash(injectCrash, "AFTER_DISPOSITION");
          results.push("DUPLICATE");
          continue;
        }

        const existingPending = existing?.disposition === "PENDING_GAP" || existing?.disposition === "PENDING_CAUSAL";
        if (existing !== undefined && !existingPending) {
          await this.#recordDisposition(dispositions, row, "DUPLICATE");
          crash(injectCrash, "AFTER_DISPOSITION");
          results.push("DUPLICATE");
          continue;
        }
        if (existingPending && (input.disposition === "PENDING_GAP" || input.disposition === "PENDING_CAUSAL")) {
          await this.#recordDisposition(dispositions, row, "DUPLICATE");
          crash(injectCrash, "AFTER_DISPOSITION");
          results.push("DUPLICATE");
          continue;
        }
        if (existingPending && existing!.localAuthor && !row.localAuthor) row = { ...row, localAuthor: true };

        if (input.disposition === "REJECTED_INVALID" || input.disposition === "REJECTED_UNSUPPORTED_SUITE") {
          if (existingPending) {
            await req(operations.delete(row.operationId));
            crash(injectCrash, "AFTER_OPERATION");
          }
        } else {
          await req(existing === undefined ? operations.add(row) : operations.put(row));
          crash(injectCrash, "AFTER_OPERATION");
        }

        if (input.disposition === "ACCEPTED") {
          await this.#advanceHead(heads, row);
          crash(injectCrash, "AFTER_FEED_HEAD");
          projectionDirty = true;
          if (row.localAuthor) {
            await req(outbox.put({
              operationId: row.operationId,
              rawWire: cloneBytes(row.rawWire),
              state: "PENDING",
              attemptCount: 0,
            } satisfies SyncOutboxRow));
            crash(injectCrash, "AFTER_OUTBOX");
          }
        } else if (input.disposition === "QUARANTINED_FORK") {
          await this.#quarantineFork(operations, heads, row);
          crash(injectCrash, "AFTER_QUARANTINE");
          crash(injectCrash, "AFTER_FEED_HEAD");
          projectionDirty = true;
        }

        await this.#recordDisposition(dispositions, row, input.disposition);
        crash(injectCrash, "AFTER_DISPOSITION");
        results.push("COMMITTED");
      }

      if (projectionDirty) await this.#rebuildProjection(transaction, injectCrash);
      crash(injectCrash, "BEFORE_COMMIT");
      return results;
    }));
  }

  async recordRejected(
    rawWire: Uint8Array,
    disposition: RejectedDisposition,
    injectCrash?: CrashInjector,
  ): Promise<void> {
    await this.#withDb((db) => tx(db, [SYNC_DISPOSITION_EVENT_STORE], "readwrite", async (transaction) => {
      await req(transaction.objectStore(SYNC_DISPOSITION_EVENT_STORE).add({
        operationId: null,
        disposition,
        rawWire: cloneBytes(rawWire),
      } satisfies SyncDispositionEventRow));
      crash(injectCrash, "AFTER_DISPOSITION");
      crash(injectCrash, "BEFORE_COMMIT");
    }));
  }

  async markDelivered(operationId: string): Promise<void> {
    requireHex(operationId, 64, "operationId");
    await this.#withDb((db) => tx(db, [SYNC_OUTBOX_STORE], "readwrite", async (transaction) => {
      await req(transaction.objectStore(SYNC_OUTBOX_STORE).delete(operationId));
    }));
  }

  async reconstruct(): Promise<ReconstructedSyncState> {
    return this.#withDb((db) => tx(db, [...SYNC_STORES], "readonly", async (transaction) => {
      const [operations, feedHeads, preferences, outbox, dispositionEvents] = await Promise.all([
        req<SyncOperationRow[]>(transaction.objectStore(SYNC_OPERATION_STORE).getAll()),
        req<SyncFeedHeadRow[]>(transaction.objectStore(SYNC_FEED_HEAD_STORE).getAll()),
        req<SyncPreferenceRow[]>(transaction.objectStore(SYNC_PREFERENCE_STORE).getAll()),
        req<SyncOutboxRow[]>(transaction.objectStore(SYNC_OUTBOX_STORE).index("state").getAll("PENDING")),
        req<SyncDispositionEventRow[]>(transaction.objectStore(SYNC_DISPOSITION_EVENT_STORE).getAll()),
      ]);
      operations.sort((left, right) => left.feedKey < right.feedKey ? -1 : left.feedKey > right.feedKey ? 1 : left.sequence - right.sequence || compareOperationIds(left, right));
      feedHeads.sort((left, right) => left.feedKey < right.feedKey ? -1 : left.feedKey > right.feedKey ? 1 : 0);
      preferences.sort((left, right) => compareUtf8(left.key, right.key));
      outbox.sort((left, right) => left.operationId < right.operationId ? -1 : left.operationId > right.operationId ? 1 : 0);
      const dispositionCounts = {
        ACCEPTED: 0,
        DUPLICATE: 0,
        PENDING_GAP: 0,
        PENDING_CAUSAL: 0,
        QUARANTINED_FORK: 0,
        REJECTED_INVALID: 0,
        REJECTED_UNSUPPORTED_SUITE: 0,
      } satisfies Record<DurableDisposition, number>;
      for (const event of dispositionEvents) dispositionCounts[event.disposition]++;
      return { operations, feedHeads, preferences, outbox, dispositionCounts };
    }));
  }

  async #prepare(input: DurableCommit): Promise<PreparedCommit> {
    const operation = input.operation;
    if (input.disposition === "REJECTED_UNSUPPORTED_SUITE") {
      throw new Error("unsupported-suite rejection must use the raw rejection audit");
    }
    if (operation.unsigned.suite !== POMO_SUITE_1 || operation.unsigned.suiteGeneration !== POMO_SUITE_GENERATION_1) {
      throw new Error("unsupported Operation suite");
    }
    await assertOperationIdentity(operation.unsigned, operation.payload, operation.canonicalUnsigned, operation.operationId);
    requireHex(operation.operationId, 64, "operationId");
    if (operation.signedEnvelope.length === 0) throw new Error("authenticated raw wire must not be empty");
    const fact = decodeSharedPreferenceFact(operation.payload);
    const feedKey = `${operation.unsigned.deviceId}:${operation.unsigned.incarnationId}` as FeedKey;
    return {
      input,
      row: {
        operationId: operation.operationId,
        memberId: operation.unsigned.memberId,
        deviceId: operation.unsigned.deviceId,
        incarnationId: operation.unsigned.incarnationId,
        feedKey,
        sequence: operation.unsigned.sequence,
        previousHash: operation.unsigned.previousHash,
        rawWire: cloneBytes(operation.signedEnvelope),
        preferenceKey: fact.key,
        preferenceValue: fact.value,
        disposition: input.disposition,
        localAuthor: input.localAuthor,
      },
    };
  }

  async #advanceHead(heads: IDBObjectStore, row: SyncOperationRow): Promise<void> {
    const current = await req<SyncFeedHeadRow | undefined>(heads.get(row.feedKey));
    if (current?.forkedAt !== null && current?.forkedAt !== undefined && row.sequence >= current.forkedAt) {
      throw new Error("cannot advance a forked feed");
    }
    const expectedSequence = (current?.sequence ?? 0) + 1;
    const expectedPreviousHash = current?.operationId ?? null;
    if (row.sequence !== expectedSequence || row.previousHash !== expectedPreviousHash) {
      throw new Error("accepted Operation does not extend the durable feed head");
    }
    await req(heads.put({
      feedKey: row.feedKey,
      deviceId: row.deviceId,
      incarnationId: row.incarnationId,
      sequence: row.sequence,
      operationId: row.operationId,
      forkedAt: null,
    } satisfies SyncFeedHeadRow));
  }

  async #quarantineFork(operations: IDBObjectStore, heads: IDBObjectStore, row: SyncOperationRow): Promise<void> {
    const current = await req<SyncFeedHeadRow | undefined>(heads.get(row.feedKey));
    const forkAt = Math.min(current?.forkedAt ?? row.sequence, row.sequence);
    const retainedSequence = Math.min(current?.sequence ?? 0, forkAt - 1);
    let retainedOperationId: string | null = null;
    if (retainedSequence > 0) {
      const retained = (await req<SyncOperationRow[]>(operations.index("feedPosition").getAll([row.feedKey, retainedSequence])))
        .find((candidate) => candidate.disposition === "ACCEPTED");
      if (retained === undefined) throw new Error("fork retained prefix is not durably accepted");
      retainedOperationId = retained.operationId;
    }
    const tail = await req<SyncOperationRow[]>(operations.index("feedPosition").getAll(
      IDBKeyRange.bound([row.feedKey, forkAt], [row.feedKey, Number.MAX_SAFE_INTEGER]),
    ));
    if (tail.length === 0) throw new Error("fork quarantine found no divergent tail");
    for (const candidate of tail) {
      await req(operations.put({ ...candidate, disposition: "QUARANTINED_FORK" } satisfies SyncOperationRow));
    }
    await req(heads.put({
      feedKey: row.feedKey,
      deviceId: row.deviceId,
      incarnationId: row.incarnationId,
      sequence: retainedSequence,
      operationId: retainedOperationId,
      forkedAt: forkAt,
    } satisfies SyncFeedHeadRow));
  }

  async #rebuildProjection(transaction: IDBTransaction, injectCrash?: CrashInjector): Promise<void> {
    const accepted = await req<SyncOperationRow[]>(transaction.objectStore(SYNC_OPERATION_STORE).index("disposition").getAll("ACCEPTED"));
    accepted.sort(compareOperationIds);
    const winners = new Map<string, SyncPreferenceRow>();
    for (const operation of accepted) {
      winners.set(operation.preferenceKey, {
        key: operation.preferenceKey,
        value: operation.preferenceValue,
        operationId: operation.operationId,
      });
    }
    const preferences = transaction.objectStore(SYNC_PREFERENCE_STORE);
    await req(preferences.clear());
    crash(injectCrash, "AFTER_PROJECTION_CLEAR");
    for (const winner of [...winners.values()].sort((left, right) => compareUtf8(left.key, right.key))) {
      await req(preferences.put(winner));
    }
    crash(injectCrash, "AFTER_PROJECTION");
  }

  async #recordDisposition(
    store: IDBObjectStore,
    row: SyncOperationRow,
    disposition: DurableDisposition,
  ): Promise<void> {
    await req(store.add({
      operationId: row.operationId,
      disposition,
      rawWire: cloneBytes(row.rawWire),
    } satisfies SyncDispositionEventRow));
  }

  async #withDb<T>(run: (db: IDBDatabase) => Promise<T>): Promise<T> {
    const db = await this.opener();
    try {
      return await run(db);
    } finally {
      db.close();
    }
  }
}
