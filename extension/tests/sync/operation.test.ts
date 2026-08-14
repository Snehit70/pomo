import { describe, expect, test } from "bun:test";
import { bytesToHex, hexToBytes } from "../../src/shared/hex";
import { decodeSharedPreferenceFact, encodeSharedPreferenceFact } from "../../src/sync/materialize/sharedPreferences";
import { canonicalUnsignedOperation, decodeUnsignedOperation, operationId, payloadHash } from "../../src/sync/protocol/operation";
import { OperationKind, type UnsignedOperation } from "../../src/sync/protocol/types";

interface OperationFixture {
  readonly payload: { readonly key: string; readonly value: string };
  readonly payloadCborHex: string;
  readonly payloadSha256Hex: string;
  readonly unsigned: {
    readonly suite: number;
    readonly suiteGeneration: number;
    readonly memberIdHex: string;
    readonly deviceIdHex: string;
    readonly incarnationIdHex: string;
    readonly sequence: number;
    readonly previousOperationIdHex: string | null;
    readonly authorizationEpoch: number;
    readonly payloadSchema: number;
    readonly operationKind: number;
  };
  readonly unsignedCborHex: string;
  readonly operationIdHex: string;
}

const fixtureRoot = new URL("../../../sync-protocol/", import.meta.url);
const corpus = (await Bun.file(new URL("fixtures/operation.json", fixtureRoot)).json()) as { readonly cases: readonly OperationFixture[] };
const fixture = corpus.cases[0]!;

function operationFromFixture(): UnsignedOperation {
  return {
    suite: fixture.unsigned.suite,
    suiteGeneration: fixture.unsigned.suiteGeneration,
    memberId: fixture.unsigned.memberIdHex,
    deviceId: fixture.unsigned.deviceIdHex,
    incarnationId: fixture.unsigned.incarnationIdHex,
    sequence: fixture.unsigned.sequence,
    previousHash: fixture.unsigned.previousOperationIdHex,
    frontier: [],
    authorizationEpoch: fixture.unsigned.authorizationEpoch,
    payloadSchema: fixture.unsigned.payloadSchema,
    kind: fixture.unsigned.operationKind as OperationKind,
    payloadHash: fixture.payloadSha256Hex,
  };
}

describe("shared Operation corpus", () => {
  test("produces byte-identical preference payload, unsigned Operation, and identity", async () => {
    const payload = encodeSharedPreferenceFact(fixture.payload.key, fixture.payload.value);
    expect(bytesToHex(payload)).toBe(fixture.payloadCborHex);
    expect(await payloadHash(payload)).toBe(fixture.payloadSha256Hex);
    const canonical = canonicalUnsignedOperation(operationFromFixture());
    expect(bytesToHex(canonical)).toBe(fixture.unsignedCborHex);
    expect(await operationId(canonical)).toBe(fixture.operationIdHex);
    expect(decodeUnsignedOperation(hexToBytes(fixture.unsignedCborHex))).toEqual(operationFromFixture());
    expect(decodeSharedPreferenceFact(payload)).toEqual({ schema: 1, key: fixture.payload.key, value: fixture.payload.value });
  });

  test("fails closed on an unsupported suite or generation", () => {
    expect(() => canonicalUnsignedOperation({ ...operationFromFixture(), suite: 2 })).toThrow(/unsupported/);
    expect(() => canonicalUnsignedOperation({ ...operationFromFixture(), suiteGeneration: 2 })).toThrow(/unsupported/);
  });

  test("enforces preference text bounds in UTF-8 bytes", () => {
    expect(() => encodeSharedPreferenceFact("é".repeat(65), "value")).toThrow(/profile/);
  });
});
