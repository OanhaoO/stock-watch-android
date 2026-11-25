package com.stockwatch.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AlertStateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun oncePerDayAndCooldownRulesMatchPcState() {
        var now = 1_000_000L
        val state = AlertState(nowMillis = { now })
        assertTrue(state.allow("code|bias_up|MA5", "20261125", 60))

        state.record("code|bias_up|MA5", "20261125", 3.2)
        assertFalse(state.allow("code|bias_up|MA5", "20261125", null))
        assertFalse(state.allow("code|bias_up|MA5", "20261125", 60))
        now += 59 * 60_000L
        assertFalse(state.allow("code|bias_up|MA5", "20261125", 60))
        now += 60_000L
        assertTrue(state.allow("code|bias_up|MA5", "20261125", 60))
        assertTrue(state.allow("code|bias_up|MA5", "20261126", null))
    }

    @Test
    fun alertAndTZoneStatePersistAcrossInstances() {
        val file = File(temporaryFolder.root, "data/state.json")
        var now = 2_000_000L
        val state = AlertState(file, nowMillis = { now })
        state.record("000001|change|day", "20261125", 5.1)
        state.setZone("000001|zone", "20261125", "above2")

        val restored = AlertState(file, nowMillis = { now })
        assertFalse(restored.allow("000001|change|day", "20261125", null))
        assertEquals("above2", restored.zone("000001|zone", "20261125"))
        assertTrue(file.exists())
    }

    @Test
    fun tZoneDefaultsToInsideOnNewDayAndCanRearm() {
        val state = AlertState()
        state.setZone("000001|zone", "20261125", "below1")
        assertEquals(TSide.IN, state.zone("000001|zone", "20261126"))
        state.setZone("000001|zone", "20261126", TSide.IN)
        assertEquals(TSide.IN, state.zone("000001|zone", "20261126"))
    }

    @Test
    fun malformedStateFileStartsEmpty() {
        val file = temporaryFolder.newFile("state.json")
        file.writeText("{broken")
        val state = AlertState(file)
        assertTrue(state.allow("key", "20261125", null))
        assertEquals(TSide.IN, state.zone("key", "20261125"))
    }
}
