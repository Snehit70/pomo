import { bufferOf } from "../../shared/bytes";
import { bytesToHex, hexToBytes, isLowerHex } from "../../shared/hex";
import { decodeCanonicalCbor, encodeCanonicalCbor, type CborValue } from "./cbor";
import {
  OperationKind,
  POMO_SUITE_1,
  POMO_SUITE_GENERATION_1,
  type FrontierEntry,
  type UnsignedOperation,
} from "./types";

const HASH_BYTES = 32;
const INCARNATION_BYTES = 16;

function requireHex(value: string, bytes: number, name: string): void {
  if (!isLowerHex(value, bytes * 2)) throw new Error(`${name} must be ${bytes}-byte lowercase hex`);
}

function requireUint(value: number, name: string): void {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${name} must be a safe unsigned integer`);
}

function compareFrontier(left: FrontierEntry, right: FrontierEntry): number {
  return compareBytes(hexToBytes(left.deviceId), hexToBytes(right.deviceId)) ||
    compareBytes(hexToBytes(left.incarnationId), hexToBytes(right.incarnationId));
}

export function compareBytes(left: Uint8Array, right: Uint8Array): number {
  const common = Math.min(left.length, right.length);
  for (let index = 0; index < common; index++) {
    const difference = left[index]! - right[index]!;
    if (difference !== 0) return difference;
  }
  return left.length - right.length;
}

function validateUnsignedOperationFields(operation: UnsignedOperation, allowUnsupportedSuite: boolean): void {
  if (!allowUnsupportedSuite && (operation.suite !== POMO_SUITE_1 || operation.suiteGeneration !== POMO_SUITE_GENERATION_1)) {
    throw new Error("unsupported Pomo suite or generation");
  }
  requireUint(operation.suite, "suite");
  requireUint(operation.suiteGeneration, "suiteGeneration");
  requireHex(operation.memberId, HASH_BYTES, "memberId");
  requireHex(operation.deviceId, HASH_BYTES, "deviceId");
  requireHex(operation.incarnationId, INCARNATION_BYTES, "incarnationId");
  requireUint(operation.sequence, "sequence");
  if (operation.sequence < 1) throw new Error("sequence must start at one");
  if (operation.previousHash !== null) requireHex(operation.previousHash, HASH_BYTES, "previousHash");
  requireUint(operation.authorizationEpoch, "authorizationEpoch");
  requireUint(operation.payloadSchema, "payloadSchema");
  requireHex(operation.payloadHash, HASH_BYTES, "payloadHash");
  if (operation.kind !== OperationKind.SharedPreferenceSet) throw new Error("unsupported Operation kind");
  let previous: FrontierEntry | undefined;
  for (const entry of operation.frontier) {
    requireHex(entry.deviceId, HASH_BYTES, "frontier.deviceId");
    requireHex(entry.incarnationId, INCARNATION_BYTES, "frontier.incarnationId");
    requireUint(entry.sequence, "frontier.sequence");
    if (entry.sequence < 1) throw new Error("frontier sequence must start at one");
    requireHex(entry.headHash, HASH_BYTES, "frontier.headHash");
    if (previous !== undefined && compareFrontier(previous, entry) >= 0) throw new Error("frontier must be unique and canonically ordered");
    previous = entry;
  }
}

export function validateUnsignedOperation(operation: UnsignedOperation): void {
  validateUnsignedOperationFields(operation, false);
}

export function canonicalUnsignedOperation(operation: UnsignedOperation): Uint8Array {
  const normalized: UnsignedOperation = { ...operation, frontier: [...operation.frontier].sort(compareFrontier) };
  validateUnsignedOperation(normalized);
  return encodeCanonicalCbor([
    normalized.suite,
    normalized.suiteGeneration,
    hexToBytes(normalized.memberId),
    hexToBytes(normalized.deviceId),
    hexToBytes(normalized.incarnationId),
    normalized.sequence,
    normalized.previousHash === null ? null : hexToBytes(normalized.previousHash),
    normalized.frontier.map((entry) => [
      hexToBytes(entry.deviceId),
      hexToBytes(entry.incarnationId),
      entry.sequence,
      hexToBytes(entry.headHash),
    ]),
    normalized.authorizationEpoch,
    normalized.payloadSchema,
    normalized.kind,
    hexToBytes(normalized.payloadHash),
  ]);
}

function asArray(value: CborValue, name: string, length: number): readonly CborValue[] {
  if (!Array.isArray(value) || value.length !== length) throw new Error(`${name} must be a ${length}-item array`);
  return value;
}

function asUint(value: CborValue, name: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) throw new Error(`${name} must be an unsigned integer`);
  return value;
}

function asBytes(value: CborValue, name: string, length: number): Uint8Array {
  if (!(value instanceof Uint8Array) || value.length !== length) throw new Error(`${name} must be a ${length}-byte string`);
  return value;
}

function decodeUnsignedOperationFields(bytes: Uint8Array, allowUnsupportedSuite: boolean): UnsignedOperation {
  const fields = asArray(decodeCanonicalCbor(bytes), "Operation", 12);
  if (!Array.isArray(fields[7])) throw new Error("frontier must be an array");
  const frontier = fields[7].map((raw, index) => {
    const entry = asArray(raw, `frontier[${index}]`, 4);
    return {
      deviceId: bytesToHex(asBytes(entry[0]!, "frontier.deviceId", HASH_BYTES)),
      incarnationId: bytesToHex(asBytes(entry[1]!, "frontier.incarnationId", INCARNATION_BYTES)),
      sequence: asUint(entry[2]!, "frontier.sequence"),
      headHash: bytesToHex(asBytes(entry[3]!, "frontier.headHash", HASH_BYTES)),
    };
  });
  const previous = fields[6];
  const operation: UnsignedOperation = {
    suite: asUint(fields[0]!, "suite"),
    suiteGeneration: asUint(fields[1]!, "suiteGeneration"),
    memberId: bytesToHex(asBytes(fields[2]!, "memberId", HASH_BYTES)),
    deviceId: bytesToHex(asBytes(fields[3]!, "deviceId", HASH_BYTES)),
    incarnationId: bytesToHex(asBytes(fields[4]!, "incarnationId", INCARNATION_BYTES)),
    sequence: asUint(fields[5]!, "sequence"),
    previousHash: previous === null ? null : bytesToHex(asBytes(previous!, "previousHash", HASH_BYTES)),
    frontier,
    authorizationEpoch: asUint(fields[8]!, "authorizationEpoch"),
    payloadSchema: asUint(fields[9]!, "payloadSchema"),
    kind: asUint(fields[10]!, "kind") as OperationKind,
    payloadHash: bytesToHex(asBytes(fields[11]!, "payloadHash", HASH_BYTES)),
  };
  validateUnsignedOperationFields(operation, allowUnsupportedSuite);
  return operation;
}

export function decodeUnsignedOperation(bytes: Uint8Array): UnsignedOperation {
  return decodeUnsignedOperationFields(bytes, false);
}

/** Decode enough authenticated structure to let the kernel classify an unsupported suite distinctly. */
export function decodeUnsignedOperationForVerification(bytes: Uint8Array): UnsignedOperation {
  return decodeUnsignedOperationFields(bytes, true);
}

export async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bufferOf(bytes)));
}

export async function operationId(canonicalUnsigned: Uint8Array): Promise<string> {
  return bytesToHex(await sha256(encodeCanonicalCbor(["Pomo Operation ID", POMO_SUITE_1, canonicalUnsigned])));
}

export async function payloadHash(payload: Uint8Array): Promise<string> {
  return bytesToHex(await sha256(payload));
}

export async function assertOperationIdentity(
  operation: UnsignedOperation,
  payload: Uint8Array,
  claimedCanonical: Uint8Array,
  claimedId: string,
): Promise<void> {
  const canonical = canonicalUnsignedOperation(operation);
  if (!equalBytes(canonical, claimedCanonical)) throw new Error("canonical Operation bytes do not match content");
  if ((await payloadHash(payload)) !== operation.payloadHash) throw new Error("Operation payload hash mismatch");
  requireHex(claimedId, HASH_BYTES, "operationId");
  if ((await operationId(canonical)) !== claimedId) throw new Error("Operation ID mismatch");
}

function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((byte, index) => byte === right[index]);
}
