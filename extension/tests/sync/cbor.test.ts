import { describe, expect, test } from "bun:test";
import { CborTag, decodeCanonicalCbor, encodeCanonicalCbor, type CborKey, type CborValue } from "../../src/sync/protocol/cbor";
import { hexToBytes } from "../../src/shared/hex";

interface NegativeCorpus {
  readonly cases: readonly { readonly id: string; readonly inputHex?: string; readonly expected: string }[];
}

const fixtureRoot = new URL("../../../sync-protocol/", import.meta.url);
const negatives = (await Bun.file(new URL("fixtures/negative.json", fixtureRoot)).json()) as NegativeCorpus;

describe("strict deterministic CBOR", () => {
  test("round trips the permitted application profile", () => {
    const encoded = encodeCanonicalCbor([1, "focusDurationMinutes", "25", new Uint8Array([0, 1, 2]), null]);
    expect(encodeCanonicalCbor(decodeCanonicalCbor(encoded))).toEqual(encoded);
  });

  for (const fixture of negatives.cases.filter((item) => item.expected === "REJECT_NON_CANONICAL" && item.inputHex !== undefined)) {
    test(`rejects shared negative fixture: ${fixture.id}`, () => {
      expect(() => decodeCanonicalCbor(hexToBytes(fixture.inputHex!))).toThrow();
    });
  }

  test("rejects floats, invalid UTF-8, trailing bytes, duplicate keys, and non-canonical key order", () => {
    for (const input of ["f90000", "61ff", "0101", "a201000101", "a202000100", "c101"] as const) {
      expect(() => decodeCanonicalCbor(hexToBytes(input))).toThrow();
    }
  });

  test("orders map keys by unsigned lexical bytes of their encodings", () => {
    expect(() => decodeCanonicalCbor(hexToBytes("a26000181800"))).toThrow(/order|canonical/);
    expect(encodeCanonicalCbor(decodeCanonicalCbor(hexToBytes("a21818006000")))).toEqual(hexToBytes("a21818006000"));
  });

  test("rejects duplicate byte-string keys and unregistered tags while encoding", () => {
    expect(() => encodeCanonicalCbor(new Map<CborKey, CborValue>([[Uint8Array.of(1), 1], [Uint8Array.of(1), 2]]))).toThrow(/duplicate/);
    expect(() => new CborTag(1, null)).toThrow(/tag/);
  });

  test("rejects text that is not NFC-normalized", () => {
    expect(() => encodeCanonicalCbor("e\u0301")).toThrow(/NFC/);
    expect(() => decodeCanonicalCbor(hexToBytes("6365cc81"))).toThrow(/NFC/);
  });
});
