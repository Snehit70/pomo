package com.pomo.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

public class LinkLogTest {
    @Before
    public fun clearLog() {
        LinkLog.clear()
    }

    @Test
    public fun snapshot_ordersOldestFirst() {
        LinkLog.record("first")
        LinkLog.record("second")
        val lines = LinkLog.snapshot().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("first"))
        assertTrue(lines[1].endsWith("second"))
    }

    @Test
    public fun snapshot_capsAtCapacity() {
        repeat(LinkLog.CAPACITY + 10) { LinkLog.record("line $it") }
        val lines = LinkLog.snapshot().lines()
        assertEquals(LinkLog.CAPACITY, lines.size)
        assertTrue(lines.first().endsWith("line 10"))
        assertTrue(lines.last().endsWith("line ${LinkLog.CAPACITY + 9}"))
    }

    @Test
    public fun record_ignoresBlankAndCollapsesWhitespace() {
        LinkLog.record("   ")
        LinkLog.record("a   b\nc")
        assertEquals(1, LinkLog.snapshot().lines().size)
        assertTrue(LinkLog.snapshot().endsWith("a b c"))
    }

    @Test
    public fun describe_formatsTimerSummary() {
        assertEquals("running 15:00 work", LinkLog.describe("running", "work", 900.0))
        assertEquals("paused 00:05 short", LinkLog.describe("paused", "short", 5.0))
        assertEquals("stopped 00:00 work", LinkLog.describe("stopped", "work", -3.0))
    }
}
