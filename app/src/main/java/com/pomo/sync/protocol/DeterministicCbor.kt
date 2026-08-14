package com.pomo.sync.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer

internal sealed interface CborValue {
    data class Integer(val value: Long) : CborValue

    class Bytes(value: ByteArray) : CborValue {
        val value: ByteArray = value.copyOf()

        override fun equals(other: Any?): kotlin.Boolean = other is Bytes && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()
    }

    data class Text(val value: String) : CborValue

    data class Boolean(val value: kotlin.Boolean) : CborValue

    data class Array(val values: List<CborValue>) : CborValue

    data class Map(val entries: List<Pair<CborValue, CborValue>>) : CborValue

    data class Tagged(val tag: Long, val value: CborValue) : CborValue

    data object Null : CborValue
}

/** Strict RFC 8949 Core Deterministic CBOR for the deliberately narrow Pomo profile. */
internal object DeterministicCbor {
    private const val MAX_INPUT_BYTES: Int = 1024 * 1024
    private const val MAX_DEPTH: Int = 32
    private const val MAX_COLLECTION_ITEMS: Int = 4096
    private const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L
    private val allowedTags: Set<Long> = setOf(18L)

    fun encode(value: CborValue): ByteArray {
        val output = ByteArrayOutputStream()
        encodeInto(value, output)
        return output.toByteArray()
    }

    fun decodeCanonical(bytes: ByteArray): CborValue {
        require(bytes.size <= MAX_INPUT_BYTES) { "CBOR input exceeds limit" }
        val decoder = Decoder(bytes)
        val decoded = decoder.read(0)
        require(decoder.finished()) { "Trailing CBOR bytes" }
        require(encode(decoded).contentEquals(bytes)) { "Non-canonical CBOR" }
        return decoded
    }

    private fun encodeInto(
        value: CborValue,
        output: ByteArrayOutputStream,
    ) {
        when (value) {
            is CborValue.Integer -> {
                require(value.value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) {
                    "CBOR integer exceeds the shared safe-integer profile"
                }
                if (value.value >= 0) {
                    writeHead(output, 0, value.value)
                } else {
                    writeHead(output, 1, -1L - value.value)
                }
            }
            is CborValue.Bytes -> {
                writeHead(output, 2, value.value.size.toLong())
                output.write(value.value)
            }
            is CborValue.Text -> {
                require(Normalizer.isNormalized(value.value, Normalizer.Form.NFC)) { "Text must be NFC" }
                val encoded = value.value.toByteArray(StandardCharsets.UTF_8)
                writeHead(output, 3, encoded.size.toLong())
                output.write(encoded)
            }
            is CborValue.Array -> {
                require(value.values.size <= MAX_COLLECTION_ITEMS)
                writeHead(output, 4, value.values.size.toLong())
                value.values.forEach { encodeInto(it, output) }
            }
            is CborValue.Map -> encodeMap(value, output)
            is CborValue.Tagged -> {
                require(value.tag in allowedTags) { "Unsupported CBOR tag" }
                writeHead(output, 6, value.tag)
                encodeInto(value.value, output)
            }
            is CborValue.Boolean -> output.write(if (value.value) 0xf5 else 0xf4)
            CborValue.Null -> output.write(0xf6)
        }
    }

    private fun encodeMap(
        map: CborValue.Map,
        output: ByteArrayOutputStream,
    ) {
        require(map.entries.size <= MAX_COLLECTION_ITEMS)
        val encoded = map.entries.map { (key, value) -> encode(key) to value }
        require(encoded.map { it.first.toHex() }.toSet().size == encoded.size) { "Duplicate map key" }
        // RFC 8949 section 4.2.1 Core Deterministic Encoding compares the
        // complete deterministic key encodings bytewise as unsigned integers.
        val sorted = encoded.sortedWith { left, right -> compareCanonicalKeys(left.first, right.first) }
        writeHead(output, 5, sorted.size.toLong())
        sorted.forEach { (key, value) ->
            output.write(key)
            encodeInto(value, output)
        }
    }

    private fun writeHead(
        output: ByteArrayOutputStream,
        major: Int,
        value: Long,
    ) {
        require(value >= 0)
        when {
            value < 24 -> output.write((major shl 5) or value.toInt())
            value <= 0xff -> {
                output.write((major shl 5) or 24)
                output.write(value.toInt())
            }
            value <= 0xffff -> {
                output.write((major shl 5) or 25)
                output.write((value ushr 8).toInt())
                output.write(value.toInt())
            }
            value <= 0xffff_ffffL -> {
                output.write((major shl 5) or 26)
                repeat(4) { index -> output.write((value ushr (24 - index * 8)).toInt()) }
            }
            else -> {
                output.write((major shl 5) or 27)
                repeat(8) { index -> output.write((value ushr (56 - index * 8)).toInt()) }
            }
        }
    }

    private fun compareCanonicalKeys(
        left: ByteArray,
        right: ByteArray,
    ): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class Decoder(private val input: ByteArray) {
        private var offset: Int = 0

        fun finished(): Boolean = offset == input.size

        fun read(depth: Int): CborValue {
            require(depth <= MAX_DEPTH) { "CBOR nesting exceeds limit" }
            val initial = octet()
            val major = initial ushr 5
            val additional = initial and 0x1f
            if (major == 7) {
                return when (additional) {
                    20 -> CborValue.Boolean(false)
                    21 -> CborValue.Boolean(true)
                    22 -> CborValue.Null
                    else -> throw IllegalArgumentException("Unsupported CBOR simple or float value")
                }
            }
            require(additional != 31) { "Indefinite CBOR is forbidden" }
            val argument = argument(additional)
            return when (major) {
                0 ->
                    CborValue.Integer(
                        argument.also {
                            require(it <= MAX_SAFE_INTEGER) { "CBOR integer exceeds the shared safe-integer profile" }
                        },
                    )
                1 ->
                    CborValue.Integer(
                        -1L -
                            argument.also {
                                require(it < MAX_SAFE_INTEGER) { "CBOR integer exceeds the shared safe-integer profile" }
                            },
                    )
                2 -> CborValue.Bytes(take(argument))
                3 -> CborValue.Text(decodeText(take(argument)))
                4 -> CborValue.Array(readItems(argument, depth))
                5 -> readMap(argument, depth)
                6 -> {
                    require(argument in allowedTags) { "Unsupported CBOR tag" }
                    CborValue.Tagged(argument, read(depth + 1))
                }
                else -> throw IllegalArgumentException("Unsupported CBOR major type")
            }
        }

        private fun readItems(
            count: Long,
            depth: Int,
        ): List<CborValue> {
            require(count <= MAX_COLLECTION_ITEMS)
            return List(count.toInt()) { read(depth + 1) }
        }

        private fun readMap(
            count: Long,
            depth: Int,
        ): CborValue.Map {
            require(count <= MAX_COLLECTION_ITEMS)
            val entries = ArrayList<Pair<CborValue, CborValue>>(count.toInt())
            repeat(count.toInt()) {
                entries += read(depth + 1) to read(depth + 1)
            }
            return CborValue.Map(entries)
        }

        private fun argument(additional: Int): Long =
            when (additional) {
                in 0..23 -> additional.toLong()
                24 -> octet().toLong().also { require(it >= 24) { "Non-minimal CBOR integer" } }
                25 -> unsigned(2).also { require(it > 0xff) { "Non-minimal CBOR integer" } }
                26 -> unsigned(4).also { require(it > 0xffff) { "Non-minimal CBOR integer" } }
                27 -> unsigned(8).also { require(it > 0xffff_ffffL) { "Non-minimal CBOR integer" } }
                else -> throw IllegalArgumentException("Reserved CBOR additional information")
            }

        private fun unsigned(count: Int): Long {
            require(offset + count <= input.size) { "Truncated CBOR" }
            if (count == 8) require((input[offset].toInt() and 0x80) == 0) { "Integer exceeds signed profile" }
            var value = 0L
            repeat(count) { value = (value shl 8) or octet().toLong() }
            return value
        }

        private fun take(length: Long): ByteArray {
            require(length <= MAX_INPUT_BYTES)
            val count = length.toInt()
            require(offset + count <= input.size) { "Truncated CBOR" }
            return input.copyOfRange(offset, offset + count).also { offset += count }
        }

        private fun octet(): Int {
            require(offset < input.size) { "Truncated CBOR" }
            return input[offset++].toInt() and 0xff
        }

        private fun decodeText(bytes: ByteArray): String {
            val decoder =
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            require(Normalizer.isNormalized(text, Normalizer.Form.NFC)) { "Text must be NFC" }
            return text
        }
    }
}
