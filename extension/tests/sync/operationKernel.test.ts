import { describe, expect, test } from "bun:test";
import { bytesToHex } from "../../src/shared/hex";
import { OperationKernel, type OperationJournal, type OperationJournalEntry, type OperationSigner, type OperationVerifier } from "../../src/sync/kernel/OperationKernel";
import { SharedPreferenceProjection, encodeSharedPreferenceFact } from "../../src/sync/materialize/sharedPreferences";
import { canonicalUnsignedOperation, operationId, payloadHash } from "../../src/sync/protocol/operation";
import { OperationKind, type AuthenticatedOperation, type OperationDisposition, POMO_SUITE_1, POMO_SUITE_GENERATION_1, type UnsignedOperation } from "../../src/sync/protocol/types";

const MEMBER = "00".repeat(32);
const DEVICE = "11".repeat(32);
const INCARNATION = "22".repeat(16);

class FixtureCrypto implements OperationSigner, OperationVerifier {
  readonly operations = new Map<string, AuthenticatedOperation>();

  async sign(operation: AuthenticatedOperation["unsigned"], payload: Uint8Array, canonicalUnsigned: Uint8Array, operationId: string): Promise<Uint8Array> {
    const envelope = new TextEncoder().encode(operationId);
    this.operations.set(bytesToHex(envelope), { operationId, unsigned: operation, payload, canonicalUnsigned, signedEnvelope: envelope });
    return envelope;
  }

  async verify(envelope: Uint8Array): Promise<AuthenticatedOperation> {
    const operation = this.operations.get(bytesToHex(envelope));
    if (operation === undefined) throw new Error("invalid fixture signature");
    return operation;
  }
}

class MemoryJournal implements OperationJournal {
  readonly records: Array<{ readonly id: string; readonly disposition: OperationDisposition }> = [];
  readonly rejected: Array<{ readonly rawWire: Uint8Array; readonly disposition: OperationDisposition }> = [];
  async recordBatch(entries: readonly OperationJournalEntry[]): Promise<void> {
    this.records.push(...entries.map(({ operation, disposition }) => ({ id: operation.operationId, disposition })));
  }
  async recordRejected(rawWire: Uint8Array, disposition: OperationDisposition): Promise<void> {
    this.rejected.push({ rawWire: rawWire.slice(), disposition });
  }
}

class FailingJournal implements OperationJournal {
  async recordBatch(): Promise<void> {
    throw new Error("durable journal unavailable");
  }
  async recordRejected(): Promise<void> {
    throw new Error("durable journal unavailable");
  }
}

function harness(): { kernel: OperationKernel; crypto: FixtureCrypto; journal: MemoryJournal; projection: SharedPreferenceProjection } {
  const crypto = new FixtureCrypto();
  const journal = new MemoryJournal();
  const projection = new SharedPreferenceProjection();
  return { kernel: new OperationKernel(crypto, crypto, journal, projection), crypto, journal, projection };
}

function request(value = "25") {
  return {
    memberId: MEMBER,
    deviceId: DEVICE,
    incarnationId: INCARNATION,
    authorizationEpoch: 1,
    frontier: [],
    payload: encodeSharedPreferenceFact("focusDurationMinutes", value),
    completePrerequisites: new Set(["AUTHORIZATION", "PROFILE_FRONTIER"]),
    authorized: true,
    deviceReady: true,
  } as const;
}

async function signedOperation(
  crypto: FixtureCrypto,
  sequence: number,
  previousHash: string | null,
  value: string,
): Promise<Uint8Array> {
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
  const canonical = canonicalUnsignedOperation(unsigned);
  return crypto.sign(unsigned, payload, canonical, await operationId(canonical));
}

describe("OperationKernel four-call seam", () => {
  test("author signs, journals, ingests, summarizes, and materializes one dormant fact", async () => {
    const { kernel, journal, projection } = harness();
    const result = await kernel.author(request());
    expect(result.status).toBe("AUTHORED");
    expect(result.status === "AUTHORED" && result.disposition).toBe("ACCEPTED");
    expect(journal.records).toHaveLength(1);
    expect(kernel.summarize()).toMatchObject({ accepted: 1, pending: 0, quarantined: 0 });
    expect(kernel.summarize().dispositionCounts.get("ACCEPTED")).toBe(1);
    expect(projection.value("focusDurationMinutes")).toBe("25");
  });

  test("blocks incomplete authoring and rejects an unauthenticated envelope", async () => {
    const { kernel, journal, crypto } = harness();
    expect(await kernel.author({ ...request(), completePrerequisites: new Set(["AUTHORIZATION"]) })).toEqual({
      status: "BLOCKED_PREREQUISITE",
      missing: new Set(["PROFILE_FRONTIER"]),
    });
    expect([...kernel.summarize().dispositionCounts.values()].every((count) => count === 0)).toBe(true);
    expect(await kernel.ingest(new Uint8Array([1, 2, 3]))).toBe("REJECTED_INVALID");
    expect(journal.rejected).toEqual([{
      rawWire: new Uint8Array([1, 2, 3]),
      disposition: "REJECTED_INVALID",
    }]);

    const payload = encodeSharedPreferenceFact("focusDurationMinutes", "25");
    const unsupported: UnsignedOperation = {
      suite: 2,
      suiteGeneration: POMO_SUITE_GENERATION_1,
      memberId: MEMBER,
      deviceId: DEVICE,
      incarnationId: INCARNATION,
      sequence: 1,
      previousHash: null,
      frontier: [],
      authorizationEpoch: 1,
      payloadHash: await payloadHash(payload),
      payloadSchema: 1,
      kind: OperationKind.SharedPreferenceSet,
    };
    const unsupportedEnvelope = Uint8Array.of(0x82);
    crypto.operations.set(bytesToHex(unsupportedEnvelope), {
      unsigned: unsupported,
      payload,
      canonicalUnsigned: new Uint8Array(),
      operationId: "",
      signedEnvelope: unsupportedEnvelope,
    });
    expect(await kernel.ingest(unsupportedEnvelope)).toBe("REJECTED_UNSUPPORTED_SUITE");
    expect(journal.rejected.at(-1)).toEqual({
      rawWire: unsupportedEnvelope,
      disposition: "REJECTED_UNSUPPORTED_SUITE",
    });
    expect(kernel.summarize().dispositionCounts.get("REJECTED_INVALID")).toBe(1);
    expect(kernel.summarize().dispositionCounts.get("REJECTED_UNSUPPORTED_SUITE")).toBe(1);
  });

  test("deduplicates replay and restores a verified covered feed", async () => {
    const { kernel, crypto, projection } = harness();
    const authored = await kernel.author(request());
    if (authored.status !== "AUTHORED") throw new Error("fixture authoring was blocked");
    expect(await kernel.ingest(authored.operation.signedEnvelope)).toBe("DUPLICATE");
    expect(kernel.summarize().dispositionCounts.get("DUPLICATE")).toBe(1);
    expect(await kernel.restore({
      suite: POMO_SUITE_1,
      suiteGeneration: POMO_SUITE_GENERATION_1,
      feeds: [{ deviceId: DEVICE, incarnationId: INCARNATION, coveredOperationIds: [authored.operation.operationId] }],
      materializedPreferences: [{ key: "focusDurationMinutes", value: "25" }],
    }, [])).toBe("RESTORED");
    expect(kernel.summarize()).toMatchObject({ accepted: 1, pending: 0, quarantined: 0 });
    expect(projection.value("focusDurationMinutes")).toBe("25");

    expect(await kernel.ingest(await signedOperation(crypto, 3, authored.operation.operationId, "30"))).toBe("PENDING_GAP");
    expect(await kernel.ingest(await signedOperation(crypto, 3, authored.operation.operationId, "35"))).toBe("QUARANTINED_FORK");
    expect(projection.value("focusDurationMinutes")).toBe("25");

    const restoredAgain = await kernel.restore({
      suite: POMO_SUITE_1,
      suiteGeneration: POMO_SUITE_GENERATION_1,
      feeds: [{ deviceId: DEVICE, incarnationId: INCARNATION, coveredOperationIds: [authored.operation.operationId] }],
      materializedPreferences: [{ key: "focusDurationMinutes", value: "25" }],
    }, []);
    expect(restoredAgain).toBe("RESTORED");
    expect(await kernel.ingest(await signedOperation(crypto, 1, null, "30"))).toBe("QUARANTINED_FORK");
    expect(projection.value("focusDurationMinutes")).toBeUndefined();
  });

  test("rejects unsupported checkpoint generation without changing state", async () => {
    const { kernel } = harness();
    await kernel.author(request());
    expect(await kernel.restore({ suite: 1, suiteGeneration: 2, feeds: [] } as never, [])).toBe("REJECTED_CHECKPOINT");
    expect(kernel.summarize().accepted).toBe(1);
  });

  test("does not expose accepted state when durable journal recording fails", async () => {
    const crypto = new FixtureCrypto();
    const projection = new SharedPreferenceProjection();
    const kernel = new OperationKernel(crypto, crypto, new FailingJournal(), projection);
    await expect(kernel.author(request())).rejects.toThrow(/journal/);
    expect(kernel.summarize().accepted).toBe(0);
    expect([...kernel.summarize().dispositionCounts.values()].every((count) => count === 0)).toBe(true);
    expect(projection.value("focusDurationMinutes")).toBeUndefined();
  });

  test("does not count a rejected wire when its audit persistence fails", async () => {
    const crypto = new FixtureCrypto();
    const kernel = new OperationKernel(crypto, crypto, new FailingJournal(), new SharedPreferenceProjection());
    await expect(kernel.ingest(new Uint8Array())).rejects.toThrow(/journal/);
    expect([...kernel.summarize().dispositionCounts.values()].every((count) => count === 0)).toBe(true);
  });

  test("does not fabricate missing feed positions when future pending candidates fork", async () => {
    const { kernel, crypto } = harness();
    const first = await kernel.author(request());
    if (first.status !== "AUTHORED") throw new Error("fixture authoring was blocked");
    const firstOperation = first.operation;

    async function signedFuture(value: string): Promise<Uint8Array> {
      const payload = encodeSharedPreferenceFact("focusDurationMinutes", value);
      const unsigned: UnsignedOperation = {
        suite: POMO_SUITE_1,
        suiteGeneration: POMO_SUITE_GENERATION_1,
        memberId: MEMBER,
        deviceId: DEVICE,
        incarnationId: INCARNATION,
        sequence: 5,
        previousHash: firstOperation.operationId,
        frontier: [],
        authorizationEpoch: 1,
        payloadSchema: 1,
        kind: OperationKind.SharedPreferenceSet,
        payloadHash: await payloadHash(payload),
      };
      const canonical = canonicalUnsignedOperation(unsigned);
      const id = await operationId(canonical);
      return crypto.sign(unsigned, payload, canonical, id);
    }

    expect(await kernel.ingest(await signedFuture("30"))).toBe("PENDING_GAP");
    expect(await kernel.ingest(await signedFuture("35"))).toBe("QUARANTINED_FORK");
    expect([...kernel.summarize().heads.values()]).toEqual([{ sequence: 1, headHash: firstOperation.operationId }]);
  });

  test("stages the complete restore and leaves active state unchanged on a trailing duplicate", async () => {
    const { kernel, projection } = harness();
    const authored = await kernel.author(request());
    if (authored.status !== "AUTHORED") throw new Error("fixture authoring was blocked");
    expect(await kernel.restore({ suite: 1, suiteGeneration: 1, feeds: [], materializedPreferences: [] }, [
      authored.operation.signedEnvelope,
      authored.operation.signedEnvelope,
    ])).toBe("REJECTED_CHECKPOINT");
    expect(kernel.summarize().accepted).toBe(1);
    expect(projection.value("focusDurationMinutes")).toBe("25");
  });

  test("drops a now-immediate pending candidate with the wrong predecessor and allows identical replay", async () => {
    const { kernel, crypto } = harness();
    const wrongPrevious = "aa".repeat(32);
    const invalidSecond = await signedOperation(crypto, 2, wrongPrevious, "30");
    const first = await signedOperation(crypto, 1, null, "25");

    expect(await kernel.ingest(invalidSecond)).toBe("PENDING_GAP");
    expect(await kernel.ingest(first)).toBe("ACCEPTED");
    const afterDrain = kernel.summarize();
    expect(afterDrain).toMatchObject({ accepted: 1, pending: 0, quarantined: 0 });
    expect(afterDrain.dispositionCounts.get("PENDING_GAP")).toBe(1);
    expect(afterDrain.dispositionCounts.get("ACCEPTED")).toBe(1);
    expect(afterDrain.dispositionCounts.get("REJECTED_INVALID")).toBe(1);

    expect(await kernel.ingest(invalidSecond)).toBe("REJECTED_INVALID");
    const afterReplay = kernel.summarize();
    expect({ ...afterReplay, dispositionCounts: undefined }).toEqual({ ...afterDrain, dispositionCounts: undefined });
    expect(afterReplay.dispositionCounts.get("REJECTED_INVALID")).toBe(2);
  });

  test("rejects non-canonical checkpoint preference projections without changing active state", async () => {
    const { kernel, projection } = harness();
    await kernel.author(request());
    const invalidPreferences = [
      [{ key: "z", value: "1" }, { key: "a", value: "2" }],
      [{ key: "a", value: "1" }, { key: "a", value: "2" }],
      [{ key: "é".repeat(65), value: "1" }],
    ] as const;
    for (const materializedPreferences of invalidPreferences) {
      expect(await kernel.restore({ suite: 1, suiteGeneration: 1, feeds: [], materializedPreferences }, [])).toBe("REJECTED_CHECKPOINT");
      expect(projection.value("focusDurationMinutes")).toBe("25");
    }
  });
});
