import { utf8ToBytes } from "../../shared/bytes";
import { hexToBytes } from "../../shared/hex";
import { assertOperationIdentity, decodeUnsignedOperation, operationId } from "../protocol/operation";
import { CborTag, decodeCanonicalCbor, encodeCanonicalCbor, type CborKey, type CborValue } from "../protocol/cbor";
import { POMO_SUITE_1, POMO_SUITE_GENERATION_1, type AuthenticatedOperation, type UnsignedOperation } from "../protocol/types";
import { signP256LowS, verifyP256LowS } from "./PomoCrypto";
import type { OperationSigner, OperationVerifier } from "../kernel/OperationKernel";

const COSE_SIGN1_TAG = 18;
const COSE_ALGORITHM = -9;
const HEADER_ALGORITHM = 1;
const HEADER_CRITICAL = 2;
const HEADER_SUITE = -65_537;
const HEADER_SUITE_GENERATION = -65_538;
const HEADER_OBJECT_KIND = -65_539;
const HEADER_OBJECT_SCHEMA = -65_540;
const HEADER_DEVICE_ID = -65_541;
const OPERATION_OBJECT_KIND = 1;
const OPERATION_OBJECT_SCHEMA = 1;
const CRITICAL_HEADERS = [HEADER_SUITE, HEADER_SUITE_GENERATION, HEADER_OBJECT_KIND, HEADER_OBJECT_SCHEMA, HEADER_DEVICE_ID] as const;
const EXTERNAL_AAD = utf8ToBytes("Pomo/Operation/1");

function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.length === right.length && left.every((byte, index) => byte === right[index]);
}

function asArray(value: CborValue, length: number, name: string): readonly CborValue[] {
  if (!Array.isArray(value) || value.length !== length) throw new Error(`${name} must be a ${length}-item array`);
  return value;
}

function asBytes(value: CborValue, name: string): Uint8Array {
  if (!(value instanceof Uint8Array)) throw new Error(`${name} must be a byte string`);
  return value;
}

function protectedHeaders(deviceId: Uint8Array): ReadonlyMap<CborKey, CborValue> {
  if (deviceId.length !== 32) throw new Error("COSE Device ID must be 32 bytes");
  return new Map<CborKey, CborValue>([
    [HEADER_ALGORITHM, COSE_ALGORITHM],
    [HEADER_CRITICAL, [...CRITICAL_HEADERS]],
    [HEADER_SUITE, POMO_SUITE_1],
    [HEADER_SUITE_GENERATION, POMO_SUITE_GENERATION_1],
    [HEADER_OBJECT_KIND, OPERATION_OBJECT_KIND],
    [HEADER_OBJECT_SCHEMA, OPERATION_OBJECT_SCHEMA],
    [HEADER_DEVICE_ID, deviceId],
  ]);
}

function signatureStructure(protectedBytes: Uint8Array, payload: Uint8Array): Uint8Array {
  return encodeCanonicalCbor(["Signature1", protectedBytes, EXTERNAL_AAD, payload]);
}

export function coseProtectedHeaders(deviceId: Uint8Array): Uint8Array {
  return encodeCanonicalCbor(protectedHeaders(deviceId));
}

export function coseSignatureStructure(protectedBytes: Uint8Array, payload: Uint8Array): Uint8Array {
  return signatureStructure(protectedBytes, payload);
}

export interface DecodedCoseOperation {
  readonly canonicalUnsigned: Uint8Array;
  readonly deviceId: Uint8Array;
  readonly signature: Uint8Array;
  readonly signatureInput: Uint8Array;
}

export async function signCoseOperation(
  privateKey: CryptoKey,
  deviceId: Uint8Array,
  canonicalUnsigned: Uint8Array,
): Promise<Uint8Array> {
  const operation = decodeUnsignedOperation(canonicalUnsigned);
  if (!equalBytes(deviceId, hexToBytes(operation.deviceId))) throw new Error("COSE Device ID does not match Operation author");
  const protectedBytes = encodeCanonicalCbor(protectedHeaders(deviceId));
  const signature = await signP256LowS(privateKey, signatureStructure(protectedBytes, canonicalUnsigned));
  return encodeCanonicalCbor(new CborTag(COSE_SIGN1_TAG, [protectedBytes, new Map(), canonicalUnsigned, signature]));
}

export function decodeCoseOperation(envelope: Uint8Array): DecodedCoseOperation {
  const tagged = decodeCanonicalCbor(envelope);
  if (!(tagged instanceof CborTag) || tagged.tag !== COSE_SIGN1_TAG) throw new Error("Operation envelope must be tagged COSE_Sign1");
  const fields = asArray(tagged.value, 4, "COSE_Sign1");
  const protectedBytes = asBytes(fields[0]!, "COSE protected headers");
  if (!(fields[1] instanceof Map) || fields[1].size !== 0) throw new Error("COSE unprotected headers must be empty");
  const canonicalUnsigned = asBytes(fields[2]!, "COSE payload");
  const signature = asBytes(fields[3]!, "COSE signature");
  if (signature.length !== 64) throw new Error("COSE ESP256 signature must be 64 bytes");
  const operation = decodeUnsignedOperation(canonicalUnsigned);
  const deviceId = hexToBytes(operation.deviceId);
  const expectedProtected = encodeCanonicalCbor(protectedHeaders(deviceId));
  if (!equalBytes(protectedBytes, expectedProtected)) throw new Error("COSE protected headers do not match POMO-SUITE-1");
  return { canonicalUnsigned, deviceId, signature, signatureInput: signatureStructure(protectedBytes, canonicalUnsigned) };
}

export async function verifyCoseOperation(publicKey: CryptoKey, envelope: Uint8Array): Promise<boolean> {
  try {
    const decoded = decodeCoseOperation(envelope);
    return await verifyP256LowS(publicKey, decoded.signatureInput, decoded.signature);
  } catch {
    return false;
  }
}

function encodeAuthenticatedWire(cose: Uint8Array, payload: Uint8Array): Uint8Array {
  return encodeCanonicalCbor([decodeCanonicalCbor(cose), payload]);
}

function decodeAuthenticatedWire(wire: Uint8Array): { cose: Uint8Array; payload: Uint8Array } {
  const fields = asArray(decodeCanonicalCbor(wire), 2, "authenticated Operation wire");
  if (!(fields[0] instanceof CborTag) || fields[0].tag !== COSE_SIGN1_TAG) throw new Error("authenticated Operation wire must carry COSE_Sign1");
  return { cose: encodeCanonicalCbor(fields[0]), payload: asBytes(fields[1]!, "Operation fact payload") };
}

export class CoseOperationSigner implements OperationSigner {
  constructor(private readonly privateKey: CryptoKey) {}

  async sign(
    operation: UnsignedOperation,
    payload: Uint8Array,
    canonicalUnsigned: Uint8Array,
    claimedId: string,
  ): Promise<Uint8Array> {
    await assertOperationIdentity(operation, payload, canonicalUnsigned, claimedId);
    const cose = await signCoseOperation(this.privateKey, hexToBytes(operation.deviceId), canonicalUnsigned);
    return encodeAuthenticatedWire(cose, payload);
  }
}

export type DeviceSigningKeyResolver = (deviceId: string) => Promise<CryptoKey | undefined> | CryptoKey | undefined;

export class CoseOperationVerifier implements OperationVerifier {
  constructor(private readonly resolvePublicKey: DeviceSigningKeyResolver) {}

  async verify(wire: Uint8Array): Promise<AuthenticatedOperation> {
    const { cose, payload } = decodeAuthenticatedWire(wire);
    const decoded = decodeCoseOperation(cose);
    const unsigned = decodeUnsignedOperation(decoded.canonicalUnsigned);
    const publicKey = await this.resolvePublicKey(unsigned.deviceId);
    if (publicKey === undefined || !(await verifyP256LowS(publicKey, decoded.signatureInput, decoded.signature))) {
      throw new Error("invalid COSE Operation signature");
    }
    const id = await operationId(decoded.canonicalUnsigned);
    const authenticated: AuthenticatedOperation = {
      unsigned,
      payload,
      canonicalUnsigned: decoded.canonicalUnsigned,
      operationId: id,
      signedEnvelope: wire.slice(),
    };
    await assertOperationIdentity(unsigned, payload, decoded.canonicalUnsigned, id);
    return authenticated;
  }
}
