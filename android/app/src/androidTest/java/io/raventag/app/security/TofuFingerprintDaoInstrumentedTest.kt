package io.raventag.app.security

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch

class TofuFingerprintDaoInstrumentedTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeDao() {
            TofuFingerprintDao.init(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }

    @Test
    fun rotationPersistsNewPinAndClearsRecordedMismatch() {
        val host = "${UUID.randomUUID()}.example"
        TofuFingerprintDao.pinFingerprint(host, "old")
        TofuFingerprintDao.recordMismatch(host, "old", "new", true)

        assertTrue(TofuFingerprintDao.conditionalRotateFingerprint(host, "old", "new"))
        assertEquals("new", TofuFingerprintDao.getFingerprint(host))
        assertNull(TofuFingerprintDao.getMismatch(host))
    }

    @Test
    fun concurrentRotationUsesCompareAndSwapAndOnlyOneAttemptWins() {
        val host = "${UUID.randomUUID()}.example"
        TofuFingerprintDao.pinFingerprint(host, "old")
        TofuFingerprintDao.recordMismatch(host, "old", "new", true)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val threads = List(2) {
            Thread {
                start.await()
                results += TofuFingerprintDao.conditionalRotateFingerprint(host, "old", "new")
            }.also { it.start() }
        }

        start.countDown()
        threads.forEach { it.join() }

        assertEquals(1, results.count { it })
        assertEquals(1, results.count { !it })
        assertEquals("new", TofuFingerprintDao.getFingerprint(host))
        assertFalse(TofuFingerprintDao.getAllMismatches().any { it.host == host })
    }
}
