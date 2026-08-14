import { describe, expect, test } from "bun:test";
import { bytesToBase64Url, utf8ToBytes } from "../../src/shared/bytes";
import { bytesToHex, hexToBytes } from "../../src/shared/hex";
import { CoseOperationSigner, CoseOperationVerifier, coseProtectedHeaders, coseSignatureStructure } from "../../src/sync/crypto/CoseOperation";
import {
  assertRecoveryArgon2idProfile,
  decryptAes256Gcm,
  deriveRecoveryKey,
  encryptAes256Gcm,
  generateP256SigningKeyPair,
  hkdfSha256,
  importHpkeP256KeyPair,
  openHpkeBase,
  sealHpkeBase,
  verifyP256LowS,
  type RecoveryArgon2idProfile,
} from "../../src/sync/crypto/PomoCrypto";
import { canonicalUnsignedOperation, operationId, payloadHash } from "../../src/sync/protocol/operation";
import { encodeSharedPreferenceFact } from "../../src/sync/materialize/sharedPreferences";
import { OperationKind, type UnsignedOperation } from "../../src/sync/protocol/types";

interface PrimitiveCorpus {
  readonly hkdfSha256: readonly { readonly ikmHex: string; readonly saltHex: string; readonly infoHex: string; readonly length: number; readonly okmHex: string }[];
  readonly hpkeP256: readonly {
    readonly ephemeralPrivateKeyHex: string; readonly ephemeralPublicKeyHex: string;
    readonly recipientPrivateKeyHex: string; readonly recipientPublicKeyHex: string;
    readonly infoHex: string; readonly aadHex: string; readonly plaintextHex: string;
    readonly encapsulatedKeyHex: string; readonly ciphertextHex: string;
  }[];
  readonly aes256Gcm: readonly {
    readonly keyHex: string; readonly nonceHex: string; readonly plaintextHex: string; readonly aadHex: string;
    readonly ciphertextHex: string; readonly tagHex: string;
  }[];
  readonly ecdsaP256Sha256: readonly {
    readonly messageUtf8: string; readonly publicXHex: string; readonly publicYHex: string; readonly pomoRawSignatureHex: string;
  }[];
  readonly pomoRecoveryArgon2idProfile: RecoveryArgon2idProfile & {
    readonly passwordUtf8: string; readonly saltHex: string; readonly tagHex: string;
  };
}

interface NegativeCorpus {
  readonly cases: readonly {
    readonly id: string;
    readonly rawSignatureHex?: string;
    readonly parameters?: RecoveryArgon2idProfile;
    readonly expected: string;
  }[];
}

interface OperationFixture {
  readonly payload: { readonly key: string; readonly value: string };
  readonly payloadSha256Hex: string;
  readonly unsigned: {
    readonly suite: number; readonly suiteGeneration: number; readonly memberIdHex: string; readonly deviceIdHex: string;
    readonly incarnationIdHex: string; readonly sequence: number; readonly previousOperationIdHex: string | null;
    readonly authorizationEpoch: number; readonly payloadSchema: number; readonly operationKind: number;
  };
  readonly operationIdHex: string;
  readonly unsignedCborHex: string;
  readonly coseProtectedHeadersHex: string;
  readonly coseSignatureStructureHex: string;
}

const fixtureRoot = new URL("../../../sync-protocol/", import.meta.url);
const primitives = (await Bun.file(new URL("fixtures/primitives.json", fixtureRoot)).json()) as PrimitiveCorpus;
const negatives = (await Bun.file(new URL("fixtures/negative.json", fixtureRoot)).json()) as NegativeCorpus;
const operationFixture = ((await Bun.file(new URL("fixtures/operation.json", fixtureRoot)).json()) as { readonly cases: readonly OperationFixture[] }).cases[0]!;

function p256Jwk(privateHex: string, publicHex: string): JsonWebKey {
  const publicBytes = hexToBytes(publicHex);
  if (publicBytes.length !== 65 || publicBytes[0] !== 4) throw new Error("fixture P-256 point is not uncompressed");
  return {
    kty: "EC",
    crv: "P-256",
    x: bytesToBase64Url(publicBytes.subarray(1, 33)),
    y: bytesToBase64Url(publicBytes.subarray(33, 65)),
    d: bytesToBase64Url(hexToBytes(privateHex)),
  };
}

describe("POMO-SUITE-1 shared primitive corpus", () => {
  for (const fixture of primitives.hkdfSha256) {
    test("matches RFC 5869 HKDF-SHA256", async () => {
      expect(bytesToHex(await hkdfSha256(hexToBytes(fixture.ikmHex), hexToBytes(fixture.saltHex), hexToBytes(fixture.infoHex), fixture.length))).toBe(fixture.okmHex);
    });
  }

  for (const fixture of primitives.aes256Gcm) {
    test("matches NIST AES-256-GCM", async () => {
      const sealed = await encryptAes256Gcm(hexToBytes(fixture.keyHex), hexToBytes(fixture.nonceHex), hexToBytes(fixture.aadHex), hexToBytes(fixture.plaintextHex));
      expect(bytesToHex(sealed.ciphertextAndTag)).toBe(fixture.ciphertextHex + fixture.tagHex);
      expect(bytesToHex(await decryptAes256Gcm(hexToBytes(fixture.keyHex), sealed, hexToBytes(fixture.aadHex)))).toBe(fixture.plaintextHex);
    });
  }

  for (const fixture of primitives.ecdsaP256Sha256) {
    test("accepts the shared low-S ESP256 vector and rejects alternate wire shapes", async () => {
      const publicKey = await crypto.subtle.importKey("jwk", {
        kty: "EC", crv: "P-256",
        x: bytesToBase64Url(hexToBytes(fixture.publicXHex)),
        y: bytesToBase64Url(hexToBytes(fixture.publicYHex)),
      }, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
      expect(await verifyP256LowS(publicKey, utf8ToBytes(fixture.messageUtf8), hexToBytes(fixture.pomoRawSignatureHex))).toBe(true);
      for (const negative of negatives.cases.filter((item) => item.rawSignatureHex !== undefined)) {
        expect(await verifyP256LowS(publicKey, utf8ToBytes(fixture.messageUtf8), hexToBytes(negative.rawSignatureHex!))).toBe(false);
      }
    });
  }

  for (const fixture of primitives.hpkeP256) {
    test("matches RFC 9180 P-256 Base-mode sequence zero", async () => {
      const recipient = await importHpkeP256KeyPair(p256Jwk(fixture.recipientPrivateKeyHex, fixture.recipientPublicKeyHex));
      const ephemeral = await importHpkeP256KeyPair(p256Jwk(fixture.ephemeralPrivateKeyHex, fixture.ephemeralPublicKeyHex));
      const sealed = await sealHpkeBase(
        recipient.publicKey,
        hexToBytes(fixture.plaintextHex),
        hexToBytes(fixture.infoHex),
        hexToBytes(fixture.aadHex),
        ephemeral,
      );
      expect(bytesToHex(sealed.encapsulatedKey)).toBe(fixture.encapsulatedKeyHex);
      expect(bytesToHex(sealed.ciphertext)).toBe(fixture.ciphertextHex);
      expect(bytesToHex(await openHpkeBase(recipient.privateKey, sealed, hexToBytes(fixture.infoHex), hexToBytes(fixture.aadHex)))).toBe(fixture.plaintextHex);
    });
  }

  test("matches the shared exact Argon2id recovery profile and rejects parameter drift", async () => {
    expect(() => assertRecoveryArgon2idProfile(primitives.pomoRecoveryArgon2idProfile)).not.toThrow();
    expect(bytesToHex(await deriveRecoveryKey(
      primitives.pomoRecoveryArgon2idProfile.passwordUtf8,
      hexToBytes(primitives.pomoRecoveryArgon2idProfile.saltHex),
    ))).toBe(primitives.pomoRecoveryArgon2idProfile.tagHex);
    for (const negative of negatives.cases.filter((item) => item.parameters !== undefined)) {
      expect(() => assertRecoveryArgon2idProfile(negative.parameters!)).toThrow(/profile/);
    }
  });
});

describe("dormant COSE Operation adapter", () => {
  test("round-trips a real tagged low-S COSE_Sign1 Operation wire", async () => {
    const keys = await generateP256SigningKeyPair();
    const payload = encodeSharedPreferenceFact(operationFixture.payload.key, operationFixture.payload.value);
    const unsigned: UnsignedOperation = {
      suite: operationFixture.unsigned.suite,
      suiteGeneration: operationFixture.unsigned.suiteGeneration,
      memberId: operationFixture.unsigned.memberIdHex,
      deviceId: operationFixture.unsigned.deviceIdHex,
      incarnationId: operationFixture.unsigned.incarnationIdHex,
      sequence: operationFixture.unsigned.sequence,
      previousHash: operationFixture.unsigned.previousOperationIdHex,
      frontier: [],
      authorizationEpoch: operationFixture.unsigned.authorizationEpoch,
      payloadSchema: operationFixture.unsigned.payloadSchema,
      kind: operationFixture.unsigned.operationKind as OperationKind,
      payloadHash: await payloadHash(payload),
    };
    const canonical = canonicalUnsignedOperation(unsigned);
    const protectedHeaders = coseProtectedHeaders(hexToBytes(unsigned.deviceId));
    expect(bytesToHex(protectedHeaders)).toBe(operationFixture.coseProtectedHeadersHex);
    expect(bytesToHex(coseSignatureStructure(protectedHeaders, canonical))).toBe(operationFixture.coseSignatureStructureHex);
    const id = await operationId(canonical);
    expect(unsigned.payloadHash).toBe(operationFixture.payloadSha256Hex);
    expect(id).toBe(operationFixture.operationIdHex);
    const wire = await new CoseOperationSigner(keys.privateKey).sign(unsigned, payload, canonical, id);
    const verified = await new CoseOperationVerifier((deviceId) => deviceId === unsigned.deviceId ? keys.publicKey : undefined).verify(wire);
    expect(verified.operationId).toBe(id);
    expect(verified.unsigned).toEqual(unsigned);
    expect(verified.payload).toEqual(payload);
  });
});
