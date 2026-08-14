import {
  Aes128Gcm,
  CipherSuite,
  DhkemP256HkdfSha256,
  HkdfSha256,
} from "@hpke/core";
import { argon2id } from "hash-wasm";
import { bufferOf, utf8ToBytes } from "../../shared/bytes";

const P256_ORDER = BigInt("0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551");
const P256_HALF_ORDER = P256_ORDER >> 1n;
const RAW_P256_SIGNATURE_BYTES = 64;
const GCM_NONCE_BYTES = 12;
const GCM_TAG_BITS = 128;

export const POMO_RECOVERY_ARGON2ID_PROFILE = Object.freeze({
  version: 19,
  memoryKiB: 65_536,
  passes: 3,
  parallelism: 4,
  saltLength: 16,
  outputLength: 32,
} as const);

export type RecoveryArgon2idProfile = Readonly<{
  version: number;
  memoryKiB: number;
  passes: number;
  parallelism: number;
  saltLength: number;
  outputLength: number;
}>;

export interface AesGcmCiphertext {
  readonly nonce: Uint8Array;
  readonly ciphertextAndTag: Uint8Array;
}

export interface HpkeCiphertext {
  readonly encapsulatedKey: Uint8Array;
  readonly ciphertext: Uint8Array;
}

function unsignedBigInt(bytes: Uint8Array): bigint {
  let value = 0n;
  for (const byte of bytes) value = (value << 8n) | BigInt(byte);
  return value;
}

function fixedUnsigned(value: bigint, length: number): Uint8Array {
  if (value < 0n) throw new Error("negative integer cannot be encoded unsigned");
  const output = new Uint8Array(length);
  let remaining = value;
  for (let index = length - 1; index >= 0; index--) {
    output[index] = Number(remaining & 0xffn);
    remaining >>= 8n;
  }
  if (remaining !== 0n) throw new Error("integer does not fit fixed-width encoding");
  return output;
}

function validateRawP256Signature(signature: Uint8Array, requireLowS: boolean): { r: bigint; s: bigint } {
  if (signature.length !== RAW_P256_SIGNATURE_BYTES) throw new Error("P-256 signature must be fixed 64-byte r || s");
  const r = unsignedBigInt(signature.subarray(0, 32));
  const s = unsignedBigInt(signature.subarray(32));
  if (r <= 0n || r >= P256_ORDER || s <= 0n || s >= P256_ORDER) throw new Error("P-256 signature scalar is outside the curve order");
  if (requireLowS && s > P256_HALF_ORDER) throw new Error("P-256 signature is not low-S");
  return { r, s };
}

export async function sha256(value: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bufferOf(value)));
}

export async function hkdfSha256(
  ikm: Uint8Array,
  salt: Uint8Array,
  info: Uint8Array,
  outputLength: number,
): Promise<Uint8Array> {
  if (!Number.isSafeInteger(outputLength) || outputLength < 1 || outputLength > 255 * 32) throw new Error("invalid HKDF output length");
  const key = await crypto.subtle.importKey("raw", bufferOf(ikm), "HKDF", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "HKDF", hash: "SHA-256", salt: bufferOf(salt), info: bufferOf(info) },
    key,
    outputLength * 8,
  );
  return new Uint8Array(bits);
}

export async function encryptAes256Gcm(
  keyBytes: Uint8Array,
  nonce: Uint8Array,
  aad: Uint8Array,
  plaintext: Uint8Array,
): Promise<AesGcmCiphertext> {
  if (keyBytes.length !== 32) throw new Error("POMO-SUITE-1 content key must be AES-256");
  if (nonce.length !== GCM_NONCE_BYTES) throw new Error("POMO-SUITE-1 AES-GCM nonce must be 12 bytes");
  const key = await crypto.subtle.importKey("raw", bufferOf(keyBytes), "AES-GCM", false, ["encrypt"]);
  const sealed = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: bufferOf(nonce), additionalData: bufferOf(aad), tagLength: GCM_TAG_BITS },
    key,
    bufferOf(plaintext),
  );
  return { nonce: nonce.slice(), ciphertextAndTag: new Uint8Array(sealed) };
}

export async function decryptAes256Gcm(
  keyBytes: Uint8Array,
  sealed: AesGcmCiphertext,
  aad: Uint8Array,
): Promise<Uint8Array> {
  if (keyBytes.length !== 32) throw new Error("POMO-SUITE-1 content key must be AES-256");
  if (sealed.nonce.length !== GCM_NONCE_BYTES || sealed.ciphertextAndTag.length < GCM_TAG_BITS / 8) throw new Error("invalid AES-GCM ciphertext");
  const key = await crypto.subtle.importKey("raw", bufferOf(keyBytes), "AES-GCM", false, ["decrypt"]);
  return new Uint8Array(await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: bufferOf(sealed.nonce), additionalData: bufferOf(aad), tagLength: GCM_TAG_BITS },
    key,
    bufferOf(sealed.ciphertextAndTag),
  ));
}

export async function generateP256SigningKeyPair(): Promise<CryptoKeyPair> {
  return await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, false, ["sign", "verify"]);
}

export async function signP256LowS(privateKey: CryptoKey, message: Uint8Array): Promise<Uint8Array> {
  const raw = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, privateKey, bufferOf(message)));
  const { r, s } = validateRawP256Signature(raw, false);
  return new Uint8Array([...fixedUnsigned(r, 32), ...fixedUnsigned(s > P256_HALF_ORDER ? P256_ORDER - s : s, 32)]);
}

export async function verifyP256LowS(publicKey: CryptoKey, message: Uint8Array, signature: Uint8Array): Promise<boolean> {
  try {
    validateRawP256Signature(signature, true);
  } catch {
    return false;
  }
  return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, publicKey, bufferOf(signature), bufferOf(message));
}

export function assertRecoveryArgon2idProfile(profile: RecoveryArgon2idProfile): void {
  const expected = POMO_RECOVERY_ARGON2ID_PROFILE;
  if (profile.version !== expected.version || profile.memoryKiB !== expected.memoryKiB || profile.passes !== expected.passes ||
      profile.parallelism !== expected.parallelism || profile.saltLength !== expected.saltLength || profile.outputLength !== expected.outputLength) {
    throw new Error("unsupported POMO-SUITE-1 Argon2id profile");
  }
}

export async function deriveRecoveryKey(
  passphrase: string | Uint8Array,
  salt: Uint8Array,
  profile: RecoveryArgon2idProfile = POMO_RECOVERY_ARGON2ID_PROFILE,
): Promise<Uint8Array> {
  assertRecoveryArgon2idProfile(profile);
  if (salt.length !== profile.saltLength) throw new Error("invalid POMO-SUITE-1 Argon2id salt length");
  if (typeof passphrase === "string" && passphrase.normalize("NFC") !== passphrase) throw new Error("recovery passphrase must be NFC-normalized");
  const password = typeof passphrase === "string" ? utf8ToBytes(passphrase) : passphrase.slice();
  if (password.length === 0) throw new Error("recovery passphrase must not be empty");
  try {
    const result = await argon2id({
      password,
      salt,
      iterations: profile.passes,
      parallelism: profile.parallelism,
      memorySize: profile.memoryKiB,
      hashLength: profile.outputLength,
      outputType: "binary",
    });
    if (!(result instanceof Uint8Array) || result.length !== profile.outputLength) throw new Error("invalid Argon2id provider output");
    return result;
  } finally {
    password.fill(0);
  }
}

function hpkeSuite(): CipherSuite {
  return new CipherSuite({
    kem: new DhkemP256HkdfSha256(),
    kdf: new HkdfSha256(),
    aead: new Aes128Gcm(),
  });
}

export async function generateHpkeRecipientKeyPair(): Promise<CryptoKeyPair> {
  return await hpkeSuite().kem.generateKeyPair();
}

export async function serializeHpkePublicKey(publicKey: CryptoKey): Promise<Uint8Array> {
  return new Uint8Array(await hpkeSuite().kem.serializePublicKey(publicKey));
}

export async function deserializeHpkePublicKey(encoded: Uint8Array): Promise<CryptoKey> {
  if (encoded.length !== 65 || encoded[0] !== 4) throw new Error("invalid uncompressed P-256 HPKE public key");
  return await hpkeSuite().kem.deserializePublicKey(bufferOf(encoded));
}

export async function importHpkeP256KeyPair(jwk: JsonWebKey): Promise<CryptoKeyPair> {
  if (jwk.kty !== "EC" || jwk.crv !== "P-256" || typeof jwk.x !== "string" || typeof jwk.y !== "string" || typeof jwk.d !== "string") {
    throw new Error("HPKE key pair must be a complete P-256 JWK");
  }
  const suite = hpkeSuite();
  const publicKey = await suite.kem.importKey("jwk", { kty: "EC", crv: "P-256", x: jwk.x, y: jwk.y }, true);
  const privateKey = await suite.kem.importKey("jwk", jwk, false);
  return { publicKey, privateKey };
}

export async function sealHpkeBase(
  recipientPublicKey: CryptoKey,
  plaintext: Uint8Array,
  info: Uint8Array,
  aad: Uint8Array,
  testOnlyEphemeralKeyPair?: CryptoKeyPair,
): Promise<HpkeCiphertext> {
  const sealed = await hpkeSuite().seal(
    { recipientPublicKey, info: bufferOf(info), ...(testOnlyEphemeralKeyPair === undefined ? {} : { ekm: testOnlyEphemeralKeyPair }) },
    bufferOf(plaintext),
    bufferOf(aad),
  );
  return { encapsulatedKey: new Uint8Array(sealed.enc), ciphertext: new Uint8Array(sealed.ct) };
}

export async function openHpkeBase(
  recipientKey: CryptoKey | CryptoKeyPair,
  sealed: HpkeCiphertext,
  info: Uint8Array,
  aad: Uint8Array,
): Promise<Uint8Array> {
  if (sealed.encapsulatedKey.length !== 65 || sealed.encapsulatedKey[0] !== 4) throw new Error("invalid HPKE encapsulated P-256 key");
  return new Uint8Array(await hpkeSuite().open(
    { recipientKey, enc: bufferOf(sealed.encapsulatedKey), info: bufferOf(info) },
    bufferOf(sealed.ciphertext),
    bufferOf(aad),
  ));
}

export function isExactRecoveryArgon2idProfile(profile: RecoveryArgon2idProfile): boolean {
  try {
    assertRecoveryArgon2idProfile(profile);
    return true;
  } catch {
    return false;
  }
}
