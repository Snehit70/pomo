package com.pomo.tags

import org.junit.Assert.assertEquals
import org.junit.Test

public class TagUsageOrderTest {
    @Test
    public fun mostUsedTagsComeFirstAndConfiguredOrderBreaksTies() {
        val tags = listOf("Work", "Study", "Exercise", "Personal")
        val usage = mapOf("Study" to 12, "Exercise" to 12, "Work" to 3)

        assertEquals(
            listOf("Study", "Exercise", "Work", "Personal"),
            orderTagsByUsage(tags, usage),
        )
    }
}
