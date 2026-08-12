/*
 * THROWAWAY PROTOTYPE — not production synchronization code.
 *
 * This executable sketch mirrors the TypeScript OperationKernel. Cryptographic
 * signature verification is represented by fixture booleans; a production
 * adapter must implement POMO-SUITE-1 and its full negative corpus.
 */

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private typealias Hex = String
private typealias FeedKey = String

private enum class Disposition {
    ACCEPTED,
    DUPLICATE,
    PENDING_GAP,
    PENDING_CAUSAL,
    QUARANTINED_FORK,
    REJECTED_INVALID,
}

private enum class OperationKind(val code: Int) {
    HISTORY_APPEND(1),
    HISTORY_DELETE(2),
    PROFILE_SET(3),
}

private data class FrontierEntry(
    val deviceId: Hex,
    val incarnationId: Hex,
    val sequence: Int,
    val headHash: Hex,
)

private data class UnsignedOperation(
    val suite: Int = 1,
    val memberId: Hex,
    val deviceId: Hex,
    val incarnationId: Hex,
    val sequence: Int,
    val previousHash: Hex?,
    val frontier: List<FrontierEntry>,
    val authorizationEpoch: Int,
    val payloadSchema: Int,
    val kind: OperationKind,
    val payloadHash: Hex,
)

private data class SignedOperation(
    val unsigned: UnsignedOperation,
    val canonicalHex: Hex,
    val operationId: Hex,
    val contentIdentityValid: Boolean = true,
    val signatureValid: Boolean = true,
    val authorized: Boolean = true,
)

private data class AuthoringContext(
    val authorized: Boolean,
    val deviceReady: Boolean,
    val completePrerequisites: Set<String>,
)

private data class CheckpointFeed(
    val feed: FeedKey,
    val headHash: Hex,
    val coveredOperationIds: List<Pair<Int, Hex>>,
)

private data class Checkpoint(
    val valid: Boolean,
    val feeds: List<CheckpointFeed>,
)

private data class FeedState(
    var head: Int = 0,
    var headHash: Hex? = null,
    var forkedAt: Int? = null,
    val accepted: MutableMap<Int, SignedOperation> = linkedMapOf(),
    val candidates: MutableMap<Int, SignedOperation> = linkedMapOf(),
    val pending: MutableMap<Int, SignedOperation> = linkedMapOf(),
    val checkpointIds: MutableMap<Int, Hex> = linkedMapOf(),
)

private data class CausalSummary(
    val heads: List<String>,
    val gaps: List<String>,
    val waiting: List<String>,
    val forks: List<String>,
    val accepted: Int,
    val pending: Int,
    val quarantined: Int,
)

private fun bytes(hex: Hex): ByteArray {
    require(hex.length % 2 == 0 && hex.matches(Regex("[0-9a-f]*"))) { "invalid lowercase hex" }
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun hex(value: ByteArray): Hex =
    value.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun cborHead(major: Int, value: Long): ByteArray {
    require(value >= 0) { "CBOR integer outside prototype profile" }
    fun octet(number: Long): Byte = (number and 0xff).toByte()
    return when {
        value < 24 -> byteArrayOf(((major shl 5) or value.toInt()).toByte())
        value <= 0xff -> byteArrayOf(((major shl 5) or 24).toByte(), octet(value))
        value <= 0xffff -> byteArrayOf(
            ((major shl 5) or 25).toByte(),
            octet(value shr 8),
            octet(value),
        )
        value <= 0xffff_ffffL -> byteArrayOf(
            ((major shl 5) or 26).toByte(),
            octet(value shr 24),
            octet(value shr 16),
            octet(value shr 8),
            octet(value),
        )
        else -> byteArrayOf(
            ((major shl 5) or 27).toByte(),
            octet(value shr 56),
            octet(value shr 48),
            octet(value shr 40),
            octet(value shr 32),
            octet(value shr 24),
            octet(value shr 16),
            octet(value shr 8),
            octet(value),
        )
    }
}

private fun encode(value: Any?): ByteArray {
    val output = ByteArrayOutputStream()
    when (value) {
        null -> output.write(0xf6)
        is Int -> output.write(cborHead(0, value.toLong()))
        is Long -> output.write(cborHead(0, value))
        is String -> {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            output.write(cborHead(3, encoded.size.toLong()))
            output.write(encoded)
        }
        is ByteArray -> {
            output.write(cborHead(2, value.size.toLong()))
            output.write(value)
        }
        is List<*> -> {
            output.write(cborHead(4, value.size.toLong()))
            value.forEach { output.write(encode(it)) }
        }
        else -> error("unsupported CBOR value: ${value::class.simpleName}")
    }
    return output.toByteArray()
}

private fun canonicalUnsigned(operation: UnsignedOperation): ByteArray {
    val frontier = operation.frontier
        .sortedBy { "${it.deviceId}:${it.incarnationId}" }
        .map { entry ->
            listOf(
                bytes(entry.deviceId),
                bytes(entry.incarnationId),
                entry.sequence,
                bytes(entry.headHash),
            )
        }
    return encode(
        listOf(
            operation.suite,
            bytes(operation.memberId),
            bytes(operation.deviceId),
            bytes(operation.incarnationId),
            operation.sequence,
            operation.previousHash?.let(::bytes),
            frontier,
            operation.authorizationEpoch,
            operation.payloadSchema,
            operation.kind.code,
            bytes(operation.payloadHash),
        ),
    )
}

private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

private fun operationId(canonical: ByteArray): Hex =
    hex(sha256(encode(listOf("Pomo Operation ID", 1, canonical))))

private fun makeOperation(
    memberId: Hex,
    deviceId: Hex,
    incarnationId: Hex,
    sequence: Int,
    previousHash: Hex?,
    frontier: List<FrontierEntry>,
    authorizationEpoch: Int,
    payloadSchema: Int,
    kind: OperationKind,
    payload: String,
    contentIdentityValid: Boolean = true,
    signatureValid: Boolean = true,
    authorized: Boolean = true,
): SignedOperation {
    val unsigned = UnsignedOperation(
        memberId = memberId,
        deviceId = deviceId,
        incarnationId = incarnationId,
        sequence = sequence,
        previousHash = previousHash,
        frontier = frontier,
        authorizationEpoch = authorizationEpoch,
        payloadSchema = payloadSchema,
        kind = kind,
        payloadHash = hex(sha256(payload.toByteArray(StandardCharsets.UTF_8))),
    )
    val canonical = canonicalUnsigned(unsigned)
    return SignedOperation(
        unsigned = unsigned,
        canonicalHex = hex(canonical),
        operationId = operationId(canonical),
        contentIdentityValid = contentIdentityValid,
        signatureValid = signatureValid,
        authorized = authorized,
    )
}

private class OperationKernel {
    private val feeds = linkedMapOf<FeedKey, FeedState>()
    private val knownIds = linkedSetOf<Hex>()
    private val quarantined = linkedSetOf<Hex>()

    fun author(kind: OperationKind, context: AuthoringContext): String {
        if (!context.authorized) return "BLOCKED_PREREQUISITE"
        val prerequisites = mapOf(
            OperationKind.HISTORY_APPEND to listOf("AUTHORIZATION", "ACTIVE_PHASE_CHAIN"),
            OperationKind.HISTORY_DELETE to listOf("AUTHORIZATION", "FULL_HISTORY", "INDEPENDENT_CONFIRMATION"),
            OperationKind.PROFILE_SET to listOf("AUTHORIZATION", "PROFILE_FRONTIER"),
        )
        if (kind == OperationKind.HISTORY_DELETE && !context.deviceReady) return "BLOCKED_PREREQUISITE"
        return if (prerequisites.getValue(kind).all(context.completePrerequisites::contains)) {
            "AUTHORIZED"
        } else {
            "BLOCKED_PREREQUISITE"
        }
    }

    fun ingest(operation: SignedOperation): Disposition {
        if (!operation.contentIdentityValid || !operation.signatureValid || !operation.authorized) return Disposition.REJECTED_INVALID
        if (!knownIds.add(operation.operationId)) return Disposition.DUPLICATE

        val key = feedKey(operation.unsigned)
        val feed = feeds.getOrPut(key, ::FeedState)
        val checkpointId = feed.checkpointIds[operation.unsigned.sequence]
        if (checkpointId != null && checkpointId != operation.operationId) {
            quarantineFork(feed, operation.unsigned.sequence, checkpointId, operation)
            return Disposition.QUARANTINED_FORK
        }

        val existing = feed.candidates[operation.unsigned.sequence]
        if (existing != null && existing.operationId != operation.operationId) {
            quarantineFork(feed, operation.unsigned.sequence, existing.operationId, operation)
            return Disposition.QUARANTINED_FORK
        }
        feed.candidates[operation.unsigned.sequence] = operation

        val forkedAt = feed.forkedAt
        if (forkedAt != null && operation.unsigned.sequence >= forkedAt) {
            quarantined.add(operation.operationId)
            return Disposition.QUARANTINED_FORK
        }
        if (operation.unsigned.sequence != feed.head + 1) {
            feed.pending[operation.unsigned.sequence] = operation
            return Disposition.PENDING_GAP
        }
        if (operation.unsigned.previousHash != feed.headHash) return Disposition.REJECTED_INVALID
        if (!causalReady(operation)) {
            feed.pending[operation.unsigned.sequence] = operation
            return Disposition.PENDING_CAUSAL
        }

        accept(feed, operation)
        drainAll()
        return Disposition.ACCEPTED
    }

    fun summarize(): CausalSummary {
        val heads = mutableListOf<String>()
        val gaps = mutableListOf<String>()
        val waiting = mutableListOf<String>()
        val forks = mutableListOf<String>()
        var accepted = 0
        var pending = 0
        feeds.toSortedMap().forEach { (key, feed) ->
            heads += "$key@${feed.head}:${feed.headHash ?: "genesis"}"
            accepted += feed.checkpointIds.size + feed.accepted.size
            pending += feed.pending.size
            if (feed.pending.isNotEmpty()) {
                if (feed.pending.containsKey(feed.head + 1)) {
                    waiting += "$key@${feed.head + 1}"
                } else {
                    gaps += "$key@${feed.head + 1}"
                }
            }
            feed.forkedAt?.let { forks += "$key@$it" }
        }
        return CausalSummary(heads, gaps, waiting, forks, accepted, pending, quarantined.size)
    }

    fun restore(checkpoint: Checkpoint, trailing: List<SignedOperation>): String {
        if (!checkpoint.valid) return "REJECTED_CHECKPOINT"
        val restoredFeeds = linkedMapOf<FeedKey, FeedState>()
        checkpoint.feeds.forEach { checkpointFeed ->
            val covered = linkedMapOf<Int, Hex>().apply { putAll(checkpointFeed.coveredOperationIds) }
            val sequence = checkpointFeed.coveredOperationIds.size
            val contiguous = checkpointFeed.coveredOperationIds.withIndex().all { (index, entry) -> entry.first == index + 1 }
            if (
                covered.size != sequence ||
                !contiguous ||
                (sequence == 0) != checkpointFeed.headHash.isEmpty() ||
                (sequence > 0 && covered[sequence] != checkpointFeed.headHash)
            ) {
                return "REJECTED_CHECKPOINT"
            }
            restoredFeeds[checkpointFeed.feed] = FeedState(
                head = sequence,
                headHash = checkpointFeed.headHash.ifEmpty { null },
                checkpointIds = covered,
            )
        }
        feeds.clear()
        knownIds.clear()
        quarantined.clear()
        restoredFeeds.forEach { (key, feed) ->
            feeds[key] = feed
            knownIds.addAll(feed.checkpointIds.values)
        }
        trailing.forEach(::ingest)
        return "RESTORED"
    }

    private fun feedKey(operation: UnsignedOperation): FeedKey =
        "${operation.deviceId}:${operation.incarnationId}"

    private fun accept(feed: FeedState, operation: SignedOperation) {
        feed.pending.remove(operation.unsigned.sequence)
        feed.accepted[operation.unsigned.sequence] = operation
        feed.head = operation.unsigned.sequence
        feed.headHash = operation.operationId
    }

    private fun causalReady(operation: SignedOperation): Boolean =
        operation.unsigned.frontier.all { entry ->
            val dependency = feeds["${entry.deviceId}:${entry.incarnationId}"]
            if (dependency == null || dependency.head < entry.sequence) {
                false
            } else {
                val observed = dependency.accepted[entry.sequence]?.operationId ?: dependency.checkpointIds[entry.sequence]
                observed == entry.headHash
            }
        }

    private fun drainAll() {
        var advanced = true
        while (advanced) {
            advanced = false
            feeds.values.forEach { feed ->
                val next = feed.pending[feed.head + 1]
                if (next != null && next.unsigned.previousHash == feed.headHash && causalReady(next)) {
                    accept(feed, next)
                    advanced = true
                }
            }
        }
    }

    private fun quarantineFork(
        feed: FeedState,
        sequence: Int,
        existingId: Hex,
        incoming: SignedOperation,
    ) {
        feed.forkedAt = feed.forkedAt?.let { minOf(it, sequence) } ?: sequence
        quarantined += existingId
        quarantined += incoming.operationId
        val fork = requireNotNull(feed.forkedAt)

        feed.checkpointIds.filterKeys { it >= fork }.forEach { (position, operationId) ->
            feed.checkpointIds.remove(position)
            quarantined += operationId
        }
        feed.accepted.filterKeys { it >= fork }.forEach { (position, operation) ->
            feed.accepted.remove(position)
            quarantined += operation.operationId
        }
        feed.pending.filterKeys { it >= fork }.forEach { (position, operation) ->
            feed.pending.remove(position)
            quarantined += operation.operationId
        }
        feed.head = fork - 1
        feed.headHash = feed.accepted[feed.head]?.operationId ?: feed.checkpointIds[feed.head]
    }
}

private const val MEMBER = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
private const val DEVICE = "1000000000000000000000000000000000000000000000000000000000000001"
private const val INCARNATION = "20000000000000000000000000000001"
private const val DEVICE_B = "1000000000000000000000000000000000000000000000000000000000000002"
private const val INCARNATION_B = "20000000000000000000000000000002"
private const val FEED: FeedKey = "$DEVICE:$INCARNATION"
private const val FEED_B: FeedKey = "$DEVICE_B:$INCARNATION_B"

private data class Fixtures(
    val a1: SignedOperation,
    val b1: SignedOperation,
    val a2: SignedOperation,
    val a3: SignedOperation,
    val a2Fork: SignedOperation,
)

private fun fixtures(): Fixtures {
    fun operation(
        deviceId: Hex = DEVICE,
        incarnationId: Hex = INCARNATION,
        sequence: Int,
        previousHash: Hex?,
        frontier: List<FrontierEntry> = emptyList(),
        payload: String,
    ): SignedOperation = makeOperation(
        memberId = MEMBER,
        deviceId = deviceId,
        incarnationId = incarnationId,
        sequence = sequence,
        previousHash = previousHash,
        frontier = frontier,
        authorizationEpoch = 3,
        payloadSchema = 1,
        kind = OperationKind.HISTORY_APPEND,
        payload = payload,
    )
    val a1 = operation(sequence = 1, previousHash = null, payload = "work:alpha")
    val b1 = operation(
        deviceId = DEVICE_B,
        incarnationId = INCARNATION_B,
        sequence = 1,
        previousHash = null,
        payload = "tag:deep-work",
    )
    val observesB1 = listOf(FrontierEntry(DEVICE_B, INCARNATION_B, 1, b1.operationId))
    val a2 = operation(sequence = 2, previousHash = a1.operationId, frontier = observesB1, payload = "work:beta")
    val a3 = operation(sequence = 3, previousHash = a2.operationId, frontier = observesB1, payload = "work:gamma")
    val a2Fork = operation(sequence = 2, previousHash = a1.operationId, frontier = observesB1, payload = "work:fork")
    return Fixtures(a1, b1, a2, a3, a2Fork)
}

private fun compact(summary: CausalSummary): String = listOf(
    "heads=${summary.heads.joinToString(",")}",
    "gaps=${summary.gaps.joinToString(",").ifEmpty { "none" }}",
    "waiting=${summary.waiting.joinToString(",").ifEmpty { "none" }}",
    "forks=${summary.forks.joinToString(",").ifEmpty { "none" }}",
    "accepted=${summary.accepted}",
    "pending=${summary.pending}",
    "quarantined=${summary.quarantined}",
).joinToString(";")

fun main() {
    val (a1, b1, a2, a3, a2Fork) = fixtures()
    println("suite=1")
    println("a1.cbor=${a1.canonicalHex}")
    println("a1.id=${a1.operationId}")
    println("invalid.contentIdentity=${OperationKernel().ingest(a1.copy(contentIdentityValid = false))}")

    val reorder = OperationKernel()
    println("reorder.dispositions=${listOf(reorder.ingest(a3), reorder.ingest(a1), reorder.ingest(a1), reorder.ingest(a2), reorder.ingest(b1)).joinToString(",")}")
    println("reorder.summary=${compact(reorder.summarize())}")

    val fork = OperationKernel()
    println("fork.dispositions=${listOf(fork.ingest(a1), fork.ingest(b1), fork.ingest(a2), fork.ingest(a2Fork)).joinToString(",")}")
    println("fork.summary=${compact(fork.summarize())}")

    val restored = OperationKernel()
    val checkpoint = Checkpoint(
        valid = true,
        feeds = listOf(
            CheckpointFeed(FEED, a2.operationId, listOf(1 to a1.operationId, 2 to a2.operationId)),
            CheckpointFeed(FEED_B, b1.operationId, listOf(1 to b1.operationId)),
        ),
    )
    println("checkpoint.restore=${restored.restore(checkpoint, listOf(a3))}")
    println("checkpoint.summary=${compact(restored.summarize())}")
    println("checkpoint.tampered=${OperationKernel().restore(checkpoint.copy(valid = false), listOf(a3))}")

    val incomplete = AuthoringContext(
        authorized = true,
        deviceReady = false,
        completePrerequisites = setOf("AUTHORIZATION", "ACTIVE_PHASE_CHAIN"),
    )
    val full = AuthoringContext(
        authorized = true,
        deviceReady = true,
        completePrerequisites = setOf(
            "AUTHORIZATION",
            "ACTIVE_PHASE_CHAIN",
            "FULL_HISTORY",
            "INDEPENDENT_CONFIRMATION",
            "PROFILE_FRONTIER",
        ),
    )
    val authoring = OperationKernel()
    println("author.incomplete.append=${authoring.author(OperationKind.HISTORY_APPEND, incomplete)}")
    println("author.incomplete.delete=${authoring.author(OperationKind.HISTORY_DELETE, incomplete)}")
    println("author.incomplete.profile=${authoring.author(OperationKind.PROFILE_SET, incomplete)}")
    println("author.full.delete=${authoring.author(OperationKind.HISTORY_DELETE, full)}")
}
