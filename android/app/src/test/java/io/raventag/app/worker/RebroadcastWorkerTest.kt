package io.raventag.app.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Wave 0 tests for RebroadcastWorker constants and delay ladder (D-25).
// Context-dependent worker scheduling tests require Robolectric or instrumented runner.

class RebroadcastWorkerTest {

    @Test
    fun delay_ladder_has_five_rungs_matching_d25_spec() {
        assertEquals(5, RebroadcastWorker.DELAY_LADDER_MINUTES.size)
        assertEquals(30L, RebroadcastWorker.DELAY_LADDER_MINUTES[0])
        assertEquals(60L, RebroadcastWorker.DELAY_LADDER_MINUTES[1])
        assertEquals(120L, RebroadcastWorker.DELAY_LADDER_MINUTES[2])
        assertEquals(240L, RebroadcastWorker.DELAY_LADDER_MINUTES[3])
        assertEquals(480L, RebroadcastWorker.DELAY_LADDER_MINUTES[4])
    }

    @Test
    fun max_attempts_is_five() {
        assertEquals(5, RebroadcastWorker.MAX_ATTEMPTS)
    }

    @Test
    fun delay_ladder_values_are_strictly_ascending() {
        val ladder = RebroadcastWorker.DELAY_LADDER_MINUTES
        for (i in 1 until ladder.size) {
            assertTrue("Rung $i should be > rung ${i - 1}", ladder[i] > ladder[i - 1])
        }
    }
}
