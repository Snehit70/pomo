import "../helpers/db";

import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { bytesToHex } from "../../src/shared/hex";
import {
  DB_NAME,
  openDb,
  SCHEMA_VERSION,
  SYNC_DISPOSITION_EVENT_STORE,
  SYNC_FEED_HEAD_STORE,
  SYNC_OPERATION_STORE,
  SYNC_OUTBOX_STORE,
  SYNC_PREFERENCE_STORE,
} from "../../src/db/schema";
import {
  OperationKernel,
  type OperationSigner,
  type OperationVerifier,
} from "../../src/sync/kernel/OperationKernel";
import { SharedPreferenceProjection, encodeSharedPreferenceFact } from "../../src/sync/materialize/sharedPreferences";
import { canonicalUnsignedOperation, operationId, payloadHash } from "../../src/sync/protocol/operation";
import {
  OperationKind,
  OPERATION_DISPOSITIONS,
  POMO_SUITE_1,
  POMO_SUITE_GENERATION_1,
  type AuthenticatedOperation,
  type UnsignedOperation,
} from "../../src/sync/protocol/types";
import {
  IndexedDbOperationDao,
  type CrashPoint,
  type DurableCommit,
  type SyncFeedHeadRow,
  type SyncPreferenceRow,
} from "../../src/sync/storage/IndexedDbOperationDao";
import { IndexedDbKernelJournal } from "../../src/sync/storage/IndexedDbKernelJournal";

const MEMBER = "00".repeat(32);
const DEVICE = "11".repeat(32);
const INCARNATION = "22".repeat(16);

async function deleteDatabase(): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const request = indexedDB.deleteDatabase(DB_NAME);
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("test database deletion blocked"));
  });
}

async function operation(
  sequence: number,
  value: string,
  previousHash: string | null,
  key = "focusDurationMinutes",
  deviceId = DEVICE,
  incarnationId = INCARNATION,
): Promise<AuthenticatedOperation> {
  const payload = encodeSharedPreferenceFact(key, value);
  const unsigned = {
    suite: 1,
    suiteGeneration: 1,
    memberId: MEMBER,
    deviceId,
    incarnationId,
    sequence,
    previousHash,
    frontier: [],
    authorizationEpoch: 1,
    payloadSchema: 1,
    kind: OperationKind.SharedPreferenceSet,
    payloadHash: await payloadHash(payload),
  } as const;
  const canonicalUnsigned = canonicalUnsignedOperation(unsigned);
  const id = await operationId(canonicalUnsigned);
  return {
    unsigned,
    payload,
    canonicalUnsigned,
    operationId: id,
    signedEnvelope: new TextEncoder().encode(`authenticated:${id}`),
  };
}

function head(operation: AuthenticatedOperation, forkedAt: number | null = null): SyncFeedHeadRow {
  return {
    feedKey: `${operation.unsigned.deviceId}:${operation.unsigned.incarnationId}`,
    deviceId: operation.unsigned.deviceId,
    incarnationId: operation.unsigned.incarnationId,
    sequence: operation.unsigned.sequence,
    operationId: operation.operationId,
    forkedAt,
  };
}

function projection(operation: AuthenticatedOperation, value: string, key = "focusDurationMinutes"): SyncPreferenceRow {
  return { key, value, operationId: operation.operationId };
}

function accepted(operation: AuthenticatedOperation, value: string, localAuthor: boolean): DurableCommit {
  void value;
  return {
    operation,
    disposition: "ACCEPTED",
    localAuthor,
  };
}

class PersistenceFixtureCrypto implements OperationSigner, OperationVerifier {
  readonly operations = new Map<string, AuthenticatedOperation>();

  async sign(
    unsigned: UnsignedOperation,
    payload: Uint8Array,
    canonicalUnsigned: Uint8Array,
    id: string,
  ): Promise<Uint8Array> {
    const envelope = new TextEncoder().encode(id);
    this.operations.set(bytesToHex(envelope), {
      unsigned,
      payload,
      canonicalUnsigned,
      operationId: id,
      signedEnvelope: envelope,
    });
    return envelope;
  }

  async verify(envelope: Uint8Array): Promise<AuthenticatedOperation> {
    const authenticated = this.operations.get(bytesToHex(envelope));
    if (authenticated === undefined) throw new Error("invalid fixture signature");
    return authenticated;
  }
}

async function fixtureOperation(
  crypto: PersistenceFixtureCrypto,
  sequence: number,
  previousHash: string | null,
  value: string,
): Promise<AuthenticatedOperation> {
  const payload = encodeSharedPreferenceFact("focusDurationMinutes", value);
  const unsigned: UnsignedOperation = {
    suite: POMO_SUITE_1,
    suiteGeneration: POMO_SUITE_GENERATION_1,
    memberId: MEMBER,
    deviceId: DEVICE,
    incarnationId: INCARNATION,
    sequence,
    previousHash,
    frontier: [],
    authorizationEpoch: 1,
    payloadSchema: 1,
    kind: OperationKind.SharedPreferenceSet,
    payloadHash: await payloadHash(payload),
  };
  const canonicalUnsigned = canonicalUnsignedOperation(unsigned);
  const id = await operationId(canonicalUnsigned);
  const signedEnvelope = await crypto.sign(unsigned, payload, canonicalUnsigned, id);
  return { unsigned, payload, canonicalUnsigned, operationId: id, signedEnvelope };
}

beforeEach(deleteDatabase);
afterEach(deleteDatabase);

describe("dormant IndexedDB Device-feed persistence", () => {
  test("migrates v3 to additive schema v4 without removing existing stores", async () => {
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, 3);
      request.onupgradeneeded = () => request.result.createObjectStore("legacySentinel");
      request.onsuccess = () => {
        request.result.close();
        resolve();
      };
      request.onerror = () => reject(request.error);
    });
    const db = await openDb();
    try {
      expect(db.version).toBe(SCHEMA_VERSION);
      for (const store of [
        "legacySentinel",
        SYNC_OPERATION_STORE,
        SYNC_FEED_HEAD_STORE,
        SYNC_PREFERENCE_STORE,
        SYNC_OUTBOX_STORE,
        SYNC_DISPOSITION_EVENT_STORE,
      ]) expect(db.objectStoreNames.contains(store)).toBe(true);
    } finally {
      db.close();
    }
  });

  test("atomically commits authenticated wire, head, projection, outbox, and disposition", async () => {
    const authored = await operation(1, "25", null);
    expect(await new IndexedDbOperationDao().commit(accepted(authored, "25", true))).toBe("COMMITTED");

    const restarted = await new IndexedDbOperationDao().reconstruct();
    expect(restarted.operations).toHaveLength(1);
    expect(restarted.operations[0]!.rawWire).toEqual(authored.signedEnvelope);
    expect(restarted.feedHeads).toEqual([head(authored)]);
    expect(restarted.preferences).toEqual([projection(authored, "25")]);
    expect(restarted.outbox).toEqual([{
      operationId: authored.operationId,
      rawWire: authored.signedEnvelope,
      state: "PENDING",
      attemptCount: 0,
    }]);
    expect(restarted.dispositionCounts.ACCEPTED).toBe(1);
  });

  test("treats an identical replay as one effect while auditing DUPLICATE", async () => {
    const authored = await operation(1, "25", null);
    const commit = accepted(authored, "25", true);
    const dao = new IndexedDbOperationDao();
    expect(await dao.commit(commit)).toBe("COMMITTED");
    expect(await dao.commit(commit)).toBe("DUPLICATE");
    const state = await dao.reconstruct();
    expect(state.operations).toHaveLength(1);
    expect(state.feedHeads).toHaveLength(1);
    expect(state.preferences).toHaveLength(1);
    expect(state.outbox).toHaveLength(1);
    expect(state.dispositionCounts).toMatchObject({ ACCEPTED: 1, DUPLICATE: 1 });
  });

  test("rebuilds projection by Operation ID so a lower-ID later arrival cannot replace a winner", async () => {
    const dao = new IndexedDbOperationDao();
    const firstFeed = await operation(1, "25", null, "focusDurationMinutes", "11".repeat(32));
    const secondFeed = await operation(1, "30", null, "focusDurationMinutes", "33".repeat(32));
    const ordered = [firstFeed, secondFeed].sort((left, right) => left.operationId < right.operationId ? -1 : 1);
    const lower = ordered[0]!;
    const higher = ordered[1]!;
    await dao.commit(accepted(higher, "30", false));
    await dao.commit(accepted(lower, "25", false));
    const expectedValue = higher === firstFeed ? "25" : "30";
    expect((await dao.reconstruct()).preferences).toEqual([projection(higher, expectedValue)]);
  });

  test("preserves an unrelated preference while rebuilding after ordinary acceptance", async () => {
    const dao = new IndexedDbOperationDao();
    const unrelated = await operation(1, "5", null, "breakDurationMinutes");
    const focus = await operation(2, "25", unrelated.operationId);
    await dao.commit(accepted(unrelated, "5", false));
    await dao.commit(accepted(focus, "25", false));
    expect((await dao.reconstruct()).preferences).toEqual([
      projection(unrelated, "5", "breakDurationMinutes"),
      projection(focus, "25"),
    ]);
  });

  test("persists Pending, Quarantined, and both authenticated and unauthenticated Rejected audit events", async () => {
    const dao = new IndexedDbOperationDao();
    const gap = await operation(2, "30", "aa".repeat(32));
    const causal = await operation(3, "35", gap.operationId);
    const quarantined = await operation(4, "40", causal.operationId);
    const rejected = await operation(5, "45", quarantined.operationId);
    await dao.commit({ operation: gap, disposition: "PENDING_GAP", localAuthor: false });
    await dao.commit({ operation: causal, disposition: "PENDING_CAUSAL", localAuthor: false });
    await dao.commit({
      operation: quarantined,
      disposition: "QUARANTINED_FORK",
      localAuthor: false,
    });
    await dao.commit({ operation: rejected, disposition: "REJECTED_INVALID", localAuthor: false });
    await dao.recordRejected(Uint8Array.of(0x80), "REJECTED_INVALID");
    await dao.recordRejected(new Uint8Array(), "REJECTED_UNSUPPORTED_SUITE");
    const state = await new IndexedDbOperationDao().reconstruct();
    expect(state.operations.map((row) => row.disposition)).toEqual(["PENDING_GAP", "PENDING_CAUSAL", "QUARANTINED_FORK"]);
    expect(state.dispositionCounts).toMatchObject({
      PENDING_GAP: 1,
      PENDING_CAUSAL: 1,
      QUARANTINED_FORK: 1,
      REJECTED_INVALID: 2,
      REJECTED_UNSUPPORTED_SUITE: 1,
    });
  });

  test("rolls back an empty raw-wire rejection audit when its transaction aborts", async () => {
    const dao = new IndexedDbOperationDao();
    await expect(dao.recordRejected(new Uint8Array(), "REJECTED_INVALID", (point) => {
      if (point === "AFTER_DISPOSITION") throw new Error("crash:raw-rejection");
    })).rejects.toThrow("crash:raw-rejection");
    const counts = (await new IndexedDbOperationDao().reconstruct()).dispositionCounts;
    expect(counts.REJECTED_INVALID).toBe(0);
    expect(counts.REJECTED_UNSUPPORTED_SUITE).toBe(0);
  });

  test("reconstructs and atomically promotes an explicit Pending candidate without treating redelivery as promotion", async () => {
    const pending = await operation(1, "25", null);
    const dao = new IndexedDbOperationDao();
    await dao.commit({ operation: pending, disposition: "PENDING_CAUSAL", localAuthor: false });
    expect(await new IndexedDbOperationDao().commit({ operation: pending, disposition: "PENDING_CAUSAL", localAuthor: false })).toBe("DUPLICATE");
    expect(await new IndexedDbOperationDao().commit({
      ...accepted(pending, "25", false),
    })).toBe("COMMITTED");
    const state = await new IndexedDbOperationDao().reconstruct();
    expect(state.operations).toHaveLength(1);
    expect(state.operations[0]!.disposition).toBe("ACCEPTED");
    expect(state.feedHeads).toEqual([head(pending)]);
    expect(state.preferences).toEqual([projection(pending, "25")]);
    expect(state.dispositionCounts).toMatchObject({ PENDING_CAUSAL: 1, DUPLICATE: 1, ACCEPTED: 1 });
  });

  test("preserves a local Pending origin and creates its outbox obligation when drained", async () => {
    const pending = await operation(1, "25", null);
    const dao = new IndexedDbOperationDao();
    await dao.commit({ operation: pending, disposition: "PENDING_CAUSAL", localAuthor: true });
    await dao.commit(accepted(pending, "25", false));
    expect((await dao.reconstruct()).outbox.map((row) => row.operationId)).toEqual([pending.operationId]);
  });

  test("commits an accepted ingress and every drained Pending transition in one durable batch", async () => {
    const dao = new IndexedDbOperationDao();
    const first = await operation(1, "25", null);
    const second = await operation(2, "30", first.operationId);
    const third = await operation(3, "35", second.operationId);
    const fourth = await operation(4, "40", third.operationId);
    await dao.commit(accepted(first, "25", false));
    await dao.commit({ operation: fourth, disposition: "PENDING_GAP", localAuthor: false });
    await dao.commit({ operation: third, disposition: "PENDING_GAP", localAuthor: false });
    expect(await dao.commitBatch([
      accepted(second, "30", false),
      accepted(third, "35", false),
      accepted(fourth, "40", false),
    ])).toEqual(["COMMITTED", "COMMITTED", "COMMITTED"]);
    const state = await dao.reconstruct();
    expect(state.operations.map((row) => row.disposition)).toEqual(["ACCEPTED", "ACCEPTED", "ACCEPTED", "ACCEPTED"]);
    expect(state.feedHeads).toEqual([head(fourth)]);
    expect(state.dispositionCounts).toMatchObject({ ACCEPTED: 4, PENDING_GAP: 2 });
  });

  test("aborts the whole drained transition batch if any derived boundary fails", async () => {
    const dao = new IndexedDbOperationDao();
    const first = await operation(1, "25", null);
    const second = await operation(2, "30", first.operationId);
    const third = await operation(3, "35", second.operationId);
    const fourth = await operation(4, "40", third.operationId);
    await dao.commit(accepted(first, "25", false));
    await dao.commit({ operation: fourth, disposition: "PENDING_GAP", localAuthor: false });
    await dao.commit({ operation: third, disposition: "PENDING_GAP", localAuthor: false });
    const before = await dao.reconstruct();
    await expect(dao.commitBatch([
      accepted(second, "30", false),
      accepted(third, "35", false),
      accepted(fourth, "40", false),
    ], (point) => {
      if (point === "AFTER_FEED_HEAD") throw new Error("crash:batch-head");
    })).rejects.toThrow("crash:batch-head");
    expect(await new IndexedDbOperationDao().reconstruct()).toEqual(before);
  });

  test("connects the dormant kernel to IndexedDB and reconstructs a multi-Pending drain after reopen", async () => {
    const crypto = new PersistenceFixtureCrypto();
    const dao = new IndexedDbOperationDao();
    const visible = new SharedPreferenceProjection();
    const kernel = new OperationKernel(crypto, crypto, new IndexedDbKernelJournal(dao), visible);
    const authored = await kernel.author({
      memberId: MEMBER,
      deviceId: DEVICE,
      incarnationId: INCARNATION,
      authorizationEpoch: 1,
      frontier: [],
      payload: encodeSharedPreferenceFact("focusDurationMinutes", "25"),
      completePrerequisites: new Set(["PROFILE_FRONTIER"]),
      authorized: true,
      deviceReady: true,
    });
    if (authored.status !== "AUTHORED") throw new Error("fixture authoring was blocked");
    const second = await fixtureOperation(crypto, 2, authored.operation.operationId, "30");
    const third = await fixtureOperation(crypto, 3, second.operationId, "35");
    const fourth = await fixtureOperation(crypto, 4, third.operationId, "40");

    expect(await kernel.ingest(fourth.signedEnvelope)).toBe("PENDING_GAP");
    expect(await kernel.ingest(third.signedEnvelope)).toBe("PENDING_GAP");
    expect(await kernel.ingest(second.signedEnvelope)).toBe("ACCEPTED");

    const restarted = await new IndexedDbOperationDao().reconstruct();
    expect(restarted.operations.map((row) => row.disposition)).toEqual(["ACCEPTED", "ACCEPTED", "ACCEPTED", "ACCEPTED"]);
    expect(restarted.feedHeads).toEqual([head(fourth)]);
    expect(restarted.outbox.map((row) => row.operationId)).toEqual([authored.operation.operationId]);
    expect(restarted.dispositionCounts).toMatchObject({ ACCEPTED: 4, PENDING_GAP: 2 });
    for (const disposition of OPERATION_DISPOSITIONS) {
      expect(restarted.dispositionCounts[disposition]).toBe(kernel.summarize().dispositionCounts.get(disposition) ?? 0);
    }
    const values = new Map([
      [authored.operation.operationId, "25"],
      [second.operationId, "30"],
      [third.operationId, "35"],
      [fourth.operationId, "40"],
    ]);
    const winner = [authored.operation, second, third, fourth]
      .sort((left, right) => left.operationId < right.operationId ? -1 : left.operationId > right.operationId ? 1 : 0)
      .at(-1)!;
    expect(restarted.preferences).toEqual([projection(winner, values.get(winner.operationId)!)]);
    expect(visible.value("focusDurationMinutes")).toBe(values.get(winner.operationId));

    expect(await kernel.ingest(new Uint8Array())).toBe("REJECTED_INVALID");
    const unsupportedEnvelope = Uint8Array.of(0x81);
    crypto.operations.set(bytesToHex(unsupportedEnvelope), {
      ...fourth,
      unsigned: { ...fourth.unsigned, suiteGeneration: 2 },
      signedEnvelope: unsupportedEnvelope,
    });
    expect(await kernel.ingest(unsupportedEnvelope)).toBe("REJECTED_UNSUPPORTED_SUITE");
    const rejectionCounts = (await new IndexedDbOperationDao().reconstruct()).dispositionCounts;
    expect(rejectionCounts.REJECTED_INVALID).toBe(1);
    expect(rejectionCounts.REJECTED_UNSUPPORTED_SUITE).toBe(1);
    for (const disposition of OPERATION_DISPOSITIONS) {
      expect(rejectionCounts[disposition]).toBe(kernel.summarize().dispositionCounts.get(disposition) ?? 0);
    }
  });

  test("quarantines a divergent tail while retaining its uncontested prefix and safe projection", async () => {
    const dao = new IndexedDbOperationDao();
    const first = await operation(1, "5", null, "breakDurationMinutes");
    const second = await operation(2, "30", first.operationId);
    const fork = await operation(2, "35", first.operationId);
    await dao.commit(accepted(first, "5", false));
    await dao.commit(accepted(second, "30", false));
    await dao.commit({
      operation: fork,
      disposition: "QUARANTINED_FORK",
      localAuthor: false,
    });
    const state = await dao.reconstruct();
    expect(state.feedHeads).toEqual([{ ...head(first), forkedAt: 2 }]);
    expect(state.preferences).toEqual([projection(first, "5", "breakDurationMinutes")]);
    expect(state.operations.find((row) => row.operationId === first.operationId)!.disposition).toBe("ACCEPTED");
    expect(state.operations.filter((row) => row.sequence === 2).map((row) => row.disposition)).toEqual([
      "QUARANTINED_FORK",
      "QUARANTINED_FORK",
    ]);
  });

  test("never loosens an existing durable fork when a later quarantined candidate arrives", async () => {
    const dao = new IndexedDbOperationDao();
    const first = await operation(1, "25", null);
    const acceptedSecond = await operation(2, "30", first.operationId);
    const forkSecond = await operation(2, "35", first.operationId);
    const later = await operation(4, "40", forkSecond.operationId);
    await dao.commit(accepted(first, "25", false));
    await dao.commit(accepted(acceptedSecond, "30", false));
    await dao.commit({ operation: forkSecond, disposition: "QUARANTINED_FORK", localAuthor: false });
    await dao.commit({ operation: later, disposition: "QUARANTINED_FORK", localAuthor: false });
    expect((await dao.reconstruct()).feedHeads).toEqual([{ ...head(first), forkedAt: 2 }]);
  });

  test("reopens to reconstruct pending outbox obligations and removes only an explicit delivery acknowledgement", async () => {
    const authored = await operation(1, "25", null);
    await new IndexedDbOperationDao().commit(accepted(authored, "25", true));
    expect((await new IndexedDbOperationDao().reconstruct()).outbox.map((row) => row.operationId)).toEqual([authored.operationId]);
    await new IndexedDbOperationDao().markDelivered(authored.operationId);
    const state = await new IndexedDbOperationDao().reconstruct();
    expect(state.outbox).toEqual([]);
    expect(state.operations).toHaveLength(1);
    expect(state.preferences).toEqual([projection(authored, "25")]);
  });
});

const LOCAL_COMMIT_CRASH_POINTS: readonly CrashPoint[] = [
  "BEFORE_OPERATION",
  "AFTER_OPERATION",
  "AFTER_FEED_HEAD",
  "AFTER_PROJECTION_CLEAR",
  "AFTER_PROJECTION",
  "AFTER_OUTBOX",
  "AFTER_DISPOSITION",
  "BEFORE_COMMIT",
];

for (const crashPoint of LOCAL_COMMIT_CRASH_POINTS) {
  test(`aborts every local-authoring effect at ${crashPoint}`, async () => {
    const authored = await operation(1, "25", null);
    const dao = new IndexedDbOperationDao();
    await expect(dao.commit(accepted(authored, "25", true), (point) => {
      if (point === crashPoint) throw new Error(`crash:${point}`);
    })).rejects.toThrow(`crash:${crashPoint}`);
    const state = await new IndexedDbOperationDao().reconstruct();
    expect(state.operations).toEqual([]);
    expect(state.feedHeads).toEqual([]);
    expect(state.preferences).toEqual([]);
    expect(state.outbox).toEqual([]);
    expect(Object.values(state.dispositionCounts).reduce((sum, count) => sum + count, 0)).toBe(0);
  });
}

const FORK_CRASH_POINTS: readonly CrashPoint[] = [
  "BEFORE_OPERATION",
  "AFTER_OPERATION",
  "AFTER_QUARANTINE",
  "AFTER_FEED_HEAD",
  "AFTER_PROJECTION_CLEAR",
  "AFTER_PROJECTION",
  "AFTER_DISPOSITION",
  "BEFORE_COMMIT",
];

for (const crashPoint of FORK_CRASH_POINTS) {
  test(`aborts every fork effect at ${crashPoint}`, async () => {
    const dao = new IndexedDbOperationDao();
    const first = await operation(1, "25", null);
    const second = await operation(2, "30", first.operationId);
    const fork = await operation(2, "35", first.operationId);
    await dao.commit(accepted(first, "25", false));
    await dao.commit(accepted(second, "30", false));
    const before = await dao.reconstruct();
    await expect(dao.commit({
      operation: fork,
      disposition: "QUARANTINED_FORK",
      localAuthor: false,
    }, (point) => {
      if (point === crashPoint) throw new Error(`crash:${point}`);
    })).rejects.toThrow(`crash:${crashPoint}`);
    expect(await new IndexedDbOperationDao().reconstruct()).toEqual(before);
  });
}
