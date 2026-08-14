package com.pomo.sync.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

public class DeterministicCborTest {
    @Test
    public fun coreDeterministicMapUsesEncodedBytewiseKeyOrder() {
        val encoded =
            DeterministicCbor.encode(
                CborValue.Map(
                    listOf(
                        CborValue.Text("a") to CborValue.Integer(3),
                        CborValue.Integer(-1) to CborValue.Integer(2),
                        CborValue.Integer(10) to CborValue.Integer(1),
                    ),
                ),
            )
        assertArrayEquals("a30a012002616103".hex(), encoded)
        DeterministicCbor.decodeCanonical(encoded)
    }

    @Test
    public fun coreDeterministicMapSortsByUnsignedLexicalBytes() {
        val encoded =
            DeterministicCbor.encode(
                CborValue.Map(
                    listOf(
                        CborValue.Integer(24) to CborValue.Integer(1),
                        CborValue.Text("") to CborValue.Integer(2),
                    ),
                ),
            )
        assertArrayEquals("a21818016002".hex(), encoded)
        assertThrows(IllegalArgumentException::class.java) {
            DeterministicCbor.decodeCanonical("a26002181801".hex())
        }
    }

    @Test
    public fun rejectsNonMinimalIndefiniteDuplicateAndTrailingForms() {
        listOf(
            "1817",
            "9f01ff",
            "a201010102",
            "0101",
            "f90000",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                DeterministicCbor.decodeCanonical(invalid.hex())
            }
        }
    }

    @Test
    public fun rejectsIntegersOutsideTheCrossRuntimeSafeIntegerProfile() {
        listOf(
            "1b0020000000000000",
            "3b001fffffffffffff",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                DeterministicCbor.decodeCanonical(invalid.hex())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeterministicCbor.encode(CborValue.Integer(9_007_199_254_740_992L))
        }
    }

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
