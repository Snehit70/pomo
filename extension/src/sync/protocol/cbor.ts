import { bytesToUtf8, utf8ToBytes } from "../../shared/bytes";

export class CborTag {
  constructor(
    readonly tag: number,
    readonly value: CborValue,
  ) {
    if (tag !== 18) throw new Error("unsupported CBOR tag");
  }
}

export type CborKey = number | string | Uint8Array;
export type CborValue = number | string | Uint8Array | boolean | null | readonly CborValue[] | ReadonlyMap<CborKey, CborValue> | CborTag;

function concat(parts: readonly Uint8Array[]): Uint8Array {
  const output = new Uint8Array(parts.reduce((total, part) => total + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    output.set(part, offset);
    offset += part.length;
  }
  return output;
}

function head(major: number, value: number): Uint8Array {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error("CBOR integer is outside the safe unsigned range");
  if (value < 24) return Uint8Array.of((major << 5) | value);
  if (value <= 0xff) return Uint8Array.of((major << 5) | 24, value);
  if (value <= 0xffff) return Uint8Array.of((major << 5) | 25, value >>> 8, value & 0xff);
  if (value <= 0xffff_ffff) {
    return Uint8Array.of((major << 5) | 26, (value / 0x1000000) & 0xff, (value >>> 16) & 0xff, (value >>> 8) & 0xff, value & 0xff);
  }
  const high = Math.floor(value / 0x1_0000_0000);
  const low = value >>> 0;
  return Uint8Array.of((major << 5) | 27, high >>> 24, high >>> 16, high >>> 8, high, low >>> 24, low >>> 16, low >>> 8, low).map((byte) => byte & 0xff);
}

function compareBytes(left: Uint8Array, right: Uint8Array): number {
  const length = Math.min(left.length, right.length);
  for (let index = 0; index < length; index++) {
    const difference = left[index]! - right[index]!;
    if (difference !== 0) return difference;
  }
  return left.length - right.length;
}

export function encodeCanonicalCbor(value: CborValue): Uint8Array {
  if (value === null) return Uint8Array.of(0xf6);
  if (value === false) return Uint8Array.of(0xf4);
  if (value === true) return Uint8Array.of(0xf5);
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) throw new Error("CBOR profile permits safe integers only");
    return value >= 0 ? head(0, value) : head(1, -1 - value);
  }
  if (typeof value === "string") {
    if (value.normalize("NFC") !== value) throw new Error("CBOR text must be NFC-normalized");
    const encoded = utf8ToBytes(value);
    return concat([head(3, encoded.length), encoded]);
  }
  if (value instanceof Uint8Array) return concat([head(2, value.length), value]);
  if (value instanceof CborTag) return concat([head(6, value.tag), encodeCanonicalCbor(value.value)]);
  if (Array.isArray(value)) return concat([head(4, value.length), ...value.map(encodeCanonicalCbor)]);
  if (value instanceof Map) {
    const entries = [...value].map(([key, item]) => ({ key: encodeCanonicalCbor(key), item: encodeCanonicalCbor(item) }));
    entries.sort((left, right) => compareBytes(left.key, right.key));
    for (let index = 1; index < entries.length; index++) {
      if (compareBytes(entries[index - 1]!.key, entries[index]!.key) === 0) throw new Error("duplicate CBOR map key");
    }
    return concat([head(5, entries.length), ...entries.flatMap(({ key, item }) => [key, item])]);
  }
  throw new Error("unsupported CBOR value");
}

class Decoder {
  static readonly MAX_INPUT_BYTES = 1024 * 1024;
  static readonly MAX_DEPTH = 32;
  static readonly MAX_COLLECTION_ITEMS = 4096;
  #offset = 0;
  constructor(private readonly bytes: Uint8Array) {
    if (bytes.length > Decoder.MAX_INPUT_BYTES) throw new Error("CBOR input exceeds limit");
  }

  decode(): CborValue {
    const value = this.#item(0);
    if (this.#offset !== this.bytes.length) throw new Error("trailing CBOR bytes");
    return value;
  }

  #readByte(): number {
    const byte = this.bytes[this.#offset];
    if (byte === undefined) throw new Error("truncated CBOR");
    this.#offset++;
    return byte;
  }

  #uint(additional: number): number {
    if (additional < 24) return additional;
    const widths: Readonly<Record<number, number>> = { 24: 1, 25: 2, 26: 4, 27: 8 };
    const width = widths[additional];
    if (width === undefined) throw new Error(additional === 31 ? "indefinite CBOR is forbidden" : "reserved CBOR additional information");
    let value = 0;
    for (let index = 0; index < width; index++) value = value * 256 + this.#readByte();
    if (!Number.isSafeInteger(value)) throw new Error("CBOR integer exceeds safe range");
    const minimum = width === 1 ? 24 : width === 2 ? 0x100 : width === 4 ? 0x1_0000 : 0x1_0000_0000;
    if (value < minimum) throw new Error("non-minimal CBOR integer");
    return value;
  }

  #slice(length: number): Uint8Array {
    const end = this.#offset + length;
    if (!Number.isSafeInteger(length) || end > this.bytes.length) throw new Error("truncated CBOR");
    const value = this.bytes.slice(this.#offset, end);
    this.#offset = end;
    return value;
  }

  #item(depth: number): CborValue {
    if (depth > Decoder.MAX_DEPTH) throw new Error("CBOR nesting exceeds limit");
    const initial = this.#readByte();
    const major = initial >>> 5;
    const additional = initial & 31;
    if (major === 7) {
      if (additional === 20) return false;
      if (additional === 21) return true;
      if (additional === 22) return null;
      throw new Error("CBOR simple values and floats are forbidden");
    }
    const argument = this.#uint(additional);
    if (major === 0) return argument;
    if (major === 1) return -1 - argument;
    if (major === 2) return this.#slice(argument);
    if (major === 3) {
      const text = bytesToUtf8(this.#slice(argument));
      if (text.normalize("NFC") !== text) throw new Error("CBOR text must be NFC-normalized");
      return text;
    }
    if ((major === 4 || major === 5) && argument > Decoder.MAX_COLLECTION_ITEMS) throw new Error("CBOR collection exceeds limit");
    if (major === 4) return Array.from({ length: argument }, () => this.#item(depth + 1));
    if (major === 5) {
      const map = new Map<CborKey, CborValue>();
      let previousKeyBytes: Uint8Array | null = null;
      for (let index = 0; index < argument; index++) {
        const keyStart = this.#offset;
        const key = this.#item(depth + 1);
        if (!(typeof key === "number" || typeof key === "string" || key instanceof Uint8Array)) throw new Error("unsupported CBOR map key");
        const keyBytes = this.bytes.slice(keyStart, this.#offset);
        if (previousKeyBytes !== null && compareBytes(previousKeyBytes, keyBytes) >= 0) throw new Error("duplicate or non-canonical CBOR map key order");
        previousKeyBytes = keyBytes;
        map.set(key, this.#item(depth + 1));
      }
      return map;
    }
    if (major === 6) {
      if (argument !== 18) throw new Error("unregistered CBOR tag");
      return new CborTag(argument, this.#item(depth + 1));
    }
    throw new Error("unsupported CBOR major type");
  }
}

export function decodeCanonicalCbor(bytes: Uint8Array): CborValue {
  const value = new Decoder(bytes).decode();
  const canonical = encodeCanonicalCbor(value);
  if (canonical.length !== bytes.length || canonical.some((byte, index) => byte !== bytes[index])) throw new Error("non-canonical CBOR");
  return value;
}
