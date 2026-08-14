package com.pomo.sync.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pomo.sync.crypto.CoseKernelSigner
import com.pomo.sync.crypto.CoseKernelVerifier
import com.pomo.sync.crypto.CoseSign1
import com.pomo.sync.crypto.HpkeP256
import com.pomo.sync.crypto.PomoCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPair

public class SharedCorpusTest {
    @Test
    public fun operationCorpusProducesFrozenCanonicalIdentity() {
        val fixture = resource("fixtures/operation.json").getAsJsonArray("cases")[0].asJsonObject
        val payloadFixture = fixture.getAsJsonObject("payload")
        val payload =
            OperationCodec.encodePreference(
                PreferenceSet(
                    payloadFixture.string("key"),
                    PreferenceValue.Text(payloadFixture.string("value")),
                ),
            )
        assertEquals(fixture.string("payloadCborHex"), payload.hex())
        assertEquals(fixture.string("payloadSha256Hex"), OperationCodec.payloadHash(payload).toString())

        val unsigned = fixture.getAsJsonObject("unsigned")
        val operation =
            UnsignedOperation(
                suite = unsigned.int("suite"),
                suiteGeneration = unsigned.long("suiteGeneration"),
                memberId = bytes(unsigned.string("memberIdHex"), PomoSuite.ID_BYTES),
                deviceId = bytes(unsigned.string("deviceIdHex"), PomoSuite.ID_BYTES),
                incarnationId = bytes(unsigned.string("incarnationIdHex"), PomoSuite.INCARNATION_BYTES),
                sequence = unsigned.long("sequence"),
                previousOperationId = null,
                frontier = emptyList(),
                authorizationEpoch = unsigned.long("authorizationEpoch"),
                payloadSchema = unsigned.int("payloadSchema"),
                kind = unsigned.int("operationKind"),
                payloadHash = OperationCodec.payloadHash(payload),
            )
        val canonical = OperationCodec.encodeUnsigned(operation)
        assertEquals(fixture.string("unsignedCborHex"), canonical.hex())
        assertEquals(fixture.string("operationIdHex"), OperationCodec.operationId(canonical).toString())
        val protected = CoseSign1.protectedHeaders(operation)
        assertEquals(fixture.string("coseProtectedHeadersHex"), protected.hex())
        assertEquals(fixture.string("coseSignatureStructureHex"), CoseSign1.signatureStructure(protected, canonical).hex())

        val signingFixture = resource("fixtures/primitives.json").getAsJsonArray("ecdsaP256Sha256")[0].asJsonObject
        val signingPrivateKey = HpkeP256.privateKeyFromScalar(signingFixture.hex("privateKeyHex"))
        val signingPublicKey =
            HpkeP256.publicKeyFromUncompressed(
                ("04" + signingFixture.string("publicXHex") + signingFixture.string("publicYHex")).decodeHex(),
            )
        val kernel =
            OperationKernel(
                CoseKernelSigner(signingPrivateKey),
                CoseKernelVerifier { signingPublicKey },
                OperationStore { _, _, _, _ -> },
                CheckpointVerifier { },
            )
        val authored =
            kernel.author(
                AuthorRequest(
                    memberId = operation.memberId,
                    deviceId = operation.deviceId,
                    incarnationId = operation.incarnationId,
                    authorizationEpoch = operation.authorizationEpoch,
                    frontier = operation.frontier,
                    preference =
                        PreferenceSet(
                            payloadFixture.string("key"),
                            PreferenceValue.Text(payloadFixture.string("value")),
                        ),
                    authorized = true,
                    deviceReady = true,
                    completePrerequisites = setOf("PROFILE_FRONTIER"),
                ),
            ) as AuthorResult.Authored
        assertEquals(fixture.string("operationIdHex"), authored.value.operationId.toString())
        assertEquals(IngestDisposition.ACCEPTED, authored.disposition)
        assertEquals(payloadFixture.string("value"), kernel.materializedPreference(payloadFixture.string("key")))
    }

    @Test
    public fun primitiveCorpusIsConsumedByAndroidAdapters() {
        val fixture = resource("fixtures/primitives.json")
        val hkdf = fixture.getAsJsonArray("hkdfSha256")[0].asJsonObject
        assertEquals(
            hkdf.string("okmHex"),
            PomoCrypto.hkdfSha256(
                hkdf.hex("ikmHex"),
                hkdf.hex("saltHex"),
                hkdf.hex("infoHex"),
                hkdf.int("length"),
            ).hex(),
        )

        val hpke = fixture.getAsJsonArray("hpkeP256")[0].asJsonObject
        assertEquals(PomoSuite.HPKE_KEM_ID, hpke.int("kemId"))
        assertEquals(PomoSuite.HPKE_KDF_ID, hpke.int("kdfId"))
        assertEquals(PomoSuite.HPKE_AEAD_ID, hpke.int("aeadId"))
        val recipientPublic = HpkeP256.publicKeyFromUncompressed(hpke.hex("recipientPublicKeyHex"))
        val recipientPrivate = HpkeP256.privateKeyFromScalar(hpke.hex("recipientPrivateKeyHex"))
        val ephemeral =
            KeyPair(
                HpkeP256.publicKeyFromUncompressed(hpke.hex("ephemeralPublicKeyHex")),
                HpkeP256.privateKeyFromScalar(hpke.hex("ephemeralPrivateKeyHex")),
            )
        val hpkeSealed =
            HpkeP256.sealWithEphemeralKey(
                recipientPublic,
                ephemeral,
                hpke.hex("infoHex"),
                hpke.hex("aadHex"),
                hpke.hex("plaintextHex"),
            )
        assertEquals(hpke.string("encapsulatedKeyHex"), hpkeSealed.encapsulatedKey.hex())
        assertEquals(hpke.string("ciphertextHex"), hpkeSealed.ciphertextAndTag.hex())
        assertEquals(
            hpke.string("plaintextHex"),
            HpkeP256.open(
                recipientPrivate,
                recipientPublic,
                hpkeSealed,
                hpke.hex("infoHex"),
                hpke.hex("aadHex"),
            ).hex(),
        )

        val aes = fixture.getAsJsonArray("aes256Gcm")[0].asJsonObject
        val sealed = PomoCrypto.encryptAesGcm(aes.hex("keyHex"), aes.hex("nonceHex"), aes.hex("aadHex"), aes.hex("plaintextHex"))
        assertEquals(aes.string("ciphertextHex") + aes.string("tagHex"), sealed.ciphertextAndTag.hex())
        assertEquals(aes.string("plaintextHex"), PomoCrypto.decryptAesGcm(aes.hex("keyHex"), sealed, aes.hex("aadHex")).hex())

        val ecdsa = fixture.getAsJsonArray("ecdsaP256Sha256")[0].asJsonObject
        val publicKey =
            HpkeP256.publicKeyFromUncompressed(
                ("04" + ecdsa.string("publicXHex") + ecdsa.string("publicYHex")).decodeHex(),
            )
        PomoCrypto.verifyP256LowS(
            publicKey,
            ecdsa.string("messageUtf8").toByteArray(StandardCharsets.UTF_8),
            ecdsa.hex("pomoRawSignatureHex"),
        )

        val argon = fixture.getAsJsonArray("argon2id")[0].asJsonObject
        assertEquals(
            argon.string("tagHex"),
            PomoCrypto.argon2idVector(
                password = argon.hex("passwordHex"),
                salt = argon.hex("saltHex"),
                memoryKiB = argon.int("memoryKiB"),
                passes = argon.int("passes"),
                parallelism = argon.int("parallelism"),
                outputLength = argon.int("tagLength"),
                secret = argon.hex("secretHex"),
                associatedData = argon.hex("associatedDataHex"),
            ).hex(),
        )

        val pomoArgon = fixture.getAsJsonObject("pomoRecoveryArgon2idProfile")
        assertEquals(
            pomoArgon.string("tagHex"),
            PomoCrypto.argon2id(
                pomoArgon.string("passwordUtf8").toByteArray(StandardCharsets.UTF_8),
                pomoArgon.hex("saltHex"),
            ).hex(),
        )
    }

    @Test
    public fun negativeCorpusFailsClosedOnAndroid() {
        val cases = resource("fixtures/negative.json").getAsJsonArray("cases")
        cases.filter { it.asJsonObject.string("expected") == "REJECT_NON_CANONICAL" && it.asJsonObject.has("inputHex") }
            .forEach { fixture ->
                assertThrows(IllegalArgumentException::class.java) {
                    DeterministicCbor.decodeCanonical(fixture.asJsonObject.hex("inputHex"))
                }
            }
        cases.filter {
            it.asJsonObject.string("expected") in
                setOf(
                    "REJECT_UNSUPPORTED_SUITE",
                    "REJECT_UNSUPPORTED_SUITE_GENERATION",
                )
        }.forEach { fixture ->
            val input = fixture.asJsonObject.hex("inputHex")
            assertThrows(IllegalArgumentException::class.java) {
                OperationCodec.decodeUnsigned(input)
            }
            val verified = OperationCodec.decodeUnsignedForVerification(input)
            assertEquals(input.hex(), OperationCodec.encodeUnsignedForVerification(verified).hex())
        }
        val signatureFixture =
            resource("fixtures/primitives.json")
                .getAsJsonArray("ecdsaP256Sha256")[0].asJsonObject
        val publicKey =
            HpkeP256.publicKeyFromUncompressed(
                ("04" + signatureFixture.string("publicXHex") + signatureFixture.string("publicYHex")).decodeHex(),
            )
        cases.filter {
            it.asJsonObject.string("expected") in
                setOf("REJECT_HIGH_S", "REJECT_SIGNATURE_LENGTH")
        }.forEach { fixture ->
            assertThrows(IllegalArgumentException::class.java) {
                PomoCrypto.verifyP256LowS(
                    publicKey,
                    signatureFixture.string("messageUtf8").toByteArray(StandardCharsets.UTF_8),
                    fixture.asJsonObject.hex("rawSignatureHex"),
                )
            }
        }
        cases.filter { it.asJsonObject.has("parameters") }.forEach { fixture ->
            val parameters = fixture.asJsonObject.getAsJsonObject("parameters")
            assertThrows(IllegalArgumentException::class.java) {
                PomoCrypto.requireArgon2Profile(
                    version = parameters.int("version"),
                    memoryKiB = parameters.int("memoryKiB"),
                    passes = parameters.int("passes"),
                    parallelism = parameters.int("parallelism"),
                    saltLength = parameters.int("saltLength"),
                    outputLength = parameters.int("outputLength"),
                )
            }
        }
    }

    private fun resource(path: String): JsonObject {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "Missing shared corpus $path" }
        return stream.reader(StandardCharsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }

    private fun bytes(
        hex: String,
        size: Int,
    ): ProtocolBytes = ProtocolBytes.of(hex.decodeHex(), size)

    private fun JsonObject.string(name: String): String = get(name).asString

    private fun JsonObject.int(name: String): Int = get(name).asInt

    private fun JsonObject.long(name: String): Long = get(name).asLong

    private fun JsonObject.hex(name: String): ByteArray = string(name).decodeHex()

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
