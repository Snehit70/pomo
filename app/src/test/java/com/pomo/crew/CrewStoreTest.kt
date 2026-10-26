package com.pomo.crew

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
public class CrewStoreTest {
    private lateinit var context: Context

    @Before
    public fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    public fun savingMultipleMembershipsPersistsBothAndActivatesTheNewest() {
        val store = store()
        val first = membership("11")
        val second = membership("22")

        store.saveMembership(first)
        store.saveMembership(second)

        assertEquals(
            listOf(first.crewId, second.crewId),
            store.loadMemberships().map { it.crewId }.sorted(),
        )
        assertEquals(second.crewId, store.activeCrewId())
        assertEquals(second.crewId, store.loadMembership()?.crewId)

        val reopened = store()
        assertEquals(
            setOf(first.crewId, second.crewId),
            reopened.loadMemberships().map { it.crewId }.toSet(),
        )
        assertEquals(second.crewId, reopened.loadMembership()?.crewId)
    }

    @Test
    public fun selectingCrewChangesActiveMembershipAndRejectsUnknownCrew() {
        val store = store()
        val first = membership("11")
        val second = membership("22")
        store.saveMembership(first)
        store.saveMembership(second)

        assertTrue(store.selectCrew(first.crewId))
        assertEquals(first.crewId, store.activeCrewId())
        assertEquals(first.crewId, store.loadMembership()?.crewId)

        assertFalse(store.selectCrew("33".repeat(16)))
        assertEquals(first.crewId, store.activeCrewId())
    }

    @Test
    public fun leavingInactiveCrewPreservesActiveCrewAndOnlyRemovesTheRequestedMembership() {
        val store = store()
        val first = membership("11")
        val second = membership("22")
        store.saveMembership(first)
        store.saveMembership(second)

        assertEquals(first, store.leaveCrew(first.crewId))

        assertEquals(listOf(second.crewId), store.loadMemberships().map { it.crewId })
        assertEquals(second.crewId, store.activeCrewId())
        assertEquals(second.crewId, store.loadMembership()?.crewId)
    }

    @Test
    public fun leavingActiveCrewSelectsAnotherAndLeavingLastCrewClearsActiveSelection() {
        val store = store()
        val first = membership("11")
        val second = membership("22")
        store.saveMembership(first)
        store.saveMembership(second)

        assertEquals(second, store.leaveCrew(second.crewId))
        assertEquals(listOf(first.crewId), store.loadMemberships().map { it.crewId })
        assertEquals(first.crewId, store.activeCrewId())

        assertEquals(first, store.leaveCrew(first.crewId))
        assertTrue(store.loadMemberships().isEmpty())
        assertEquals(null, store.activeCrewId())
        assertEquals(null, store.loadMembership())
    }

    @Test
    public fun initArchivesLegacyMembershipsOnceAndExposesReadOnlyArchivedMemberships() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(
                LEGACY_MEMBERSHIPS_KEY,
                """
                [
                  {"crewId":"${"11".repeat(16)}","displayName":"Asha"},
                  {"crewId":"${"22".repeat(16)}","displayName":""}
                ]
                """.trimIndent(),
            )
            .commit()

        val store = store()
        val archived = store.loadArchivedMemberships()

        assertEquals(2, archived.size)
        assertTrue(archived.all { it.isArchived })
        assertTrue(archived.all { it.protocolVersion == 1 })
        assertEquals("Asha", archived.first().displayName)
        assertEquals("Me", archived.last().displayName)
        assertFalse(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .contains(LEGACY_MEMBERSHIPS_KEY),
        )
    }

    @Test
    public fun initArchivesLegacyCurrentCrewFallbackWhenMembershipListIsMissing() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(LEGACY_CURRENT_CREW_KEY, """{"crewId":"${"33".repeat(16)}","displayName":"Bo"}""")
            .commit()

        val store = store()
        val archived = store.loadArchivedMemberships()

        assertEquals(1, archived.size)
        assertEquals("Bo", archived.single().displayName)
        assertTrue(archived.single().isArchived)
    }

    private companion object {
        private const val PREFS_NAME: String = "crew_prefs"
        private const val LEGACY_CURRENT_CREW_KEY: String = "current_crew"
        private const val LEGACY_MEMBERSHIPS_KEY: String = "memberships"
    }

    private fun store(): CrewStore = CrewStore(context, TestCrewStoreCipher)

    private fun membership(prefix: String): CrewMembership =
        CrewMembership(
            crewId = prefix.repeat(16),
            crewName = "Crew $prefix",
            joinCode = "join-$prefix",
            relays = emptyList(),
            key = "ab".repeat(32),
            displayName = "Me",
        )

    private object TestCrewStoreCipher : CrewStoreCipher {
        override fun encryptString(plaintext: String): String = "test:$plaintext"

        override fun decryptString(envelope: String): String? =
            envelope.removePrefix("test:").takeIf { envelope.startsWith("test:") }
    }
}
