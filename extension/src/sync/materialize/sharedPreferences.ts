import { decodeCanonicalCbor, encodeCanonicalCbor } from "../protocol/cbor";
import { OperationKind, type AuthenticatedOperation } from "../protocol/types";

export interface SharedPreferenceFact {
  readonly schema: 1;
  readonly key: string;
  readonly value: string;
}

export function encodeSharedPreferenceFact(key: string, value: string): Uint8Array {
  validateText(key, "preference key", 1, 128);
  validateText(value, "preference value", 0, 4096);
  return encodeCanonicalCbor([1, key, value]);
}

export function decodeSharedPreferenceFact(bytes: Uint8Array): SharedPreferenceFact {
  const decoded = decodeCanonicalCbor(bytes);
  if (!Array.isArray(decoded) || decoded.length !== 3 || decoded[0] !== 1 || typeof decoded[1] !== "string" || typeof decoded[2] !== "string") {
    throw new Error("invalid shared-preference payload");
  }
  validateText(decoded[1], "preference key", 1, 128);
  validateText(decoded[2], "preference value", 0, 4096);
  return { schema: 1, key: decoded[1], value: decoded[2] };
}

function validateText(value: string, name: string, minimum: number, maximum: number): void {
  const byteLength = new TextEncoder().encode(value).length;
  if (byteLength < minimum || byteLength > maximum || value.normalize("NFC") !== value) {
    throw new Error(`${name} is outside the canonical text profile`);
  }
}

export class SharedPreferenceProjection {
  readonly #values = new Map<string, string>();

  apply(operation: AuthenticatedOperation): void {
    if (operation.unsigned.kind !== OperationKind.SharedPreferenceSet || operation.unsigned.payloadSchema !== 1) {
      throw new Error("unsupported shared-preference Operation");
    }
    const fact = decodeSharedPreferenceFact(operation.payload);
    this.#values.set(fact.key, fact.value);
  }

  validate(operation: AuthenticatedOperation): void {
    if (operation.unsigned.kind !== OperationKind.SharedPreferenceSet || operation.unsigned.payloadSchema !== 1) {
      throw new Error("unsupported shared-preference Operation");
    }
    decodeSharedPreferenceFact(operation.payload);
  }

  value(key: string): string | undefined {
    return this.#values.get(key);
  }

  snapshot(): Readonly<Record<string, string>> {
    return Object.fromEntries([...this.#values].sort(([left], [right]) => compareUtf8(left, right)));
  }

  clear(): void {
    this.#values.clear();
  }

  replace(
    checkpointPreferences: readonly { readonly key: string; readonly value: string }[],
    operations: readonly AuthenticatedOperation[],
  ): void {
    const staged = new Map(checkpointPreferences.map(({ key, value }) => {
      validateText(key, "checkpoint preference key", 1, 128);
      validateText(value, "checkpoint preference value", 0, 4096);
      return [key, value] as const;
    }));
    for (const operation of operations) {
      if (operation.unsigned.kind !== OperationKind.SharedPreferenceSet || operation.unsigned.payloadSchema !== 1) {
        throw new Error("unsupported shared-preference Operation");
      }
      const fact = decodeSharedPreferenceFact(operation.payload);
      staged.set(fact.key, fact.value);
    }
    this.#values.clear();
    for (const [key, value] of staged) this.#values.set(key, value);
  }
}

function compareUtf8(left: string, right: string): number {
  const leftBytes = new TextEncoder().encode(left);
  const rightBytes = new TextEncoder().encode(right);
  const common = Math.min(leftBytes.length, rightBytes.length);
  for (let index = 0; index < common; index++) {
    const difference = leftBytes[index]! - rightBytes[index]!;
    if (difference !== 0) return difference;
  }
  return leftBytes.length - rightBytes.length;
}
