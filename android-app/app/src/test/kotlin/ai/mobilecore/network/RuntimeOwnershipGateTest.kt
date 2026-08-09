package ai.mobilecore.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RuntimeOwnershipGateTest {
    @Test
    fun `load cannot enter while atomic uninstall owns runtime`() {
        val gate = RuntimeOwnershipGate()
        val uninstallEntered = CountDownLatch(1)
        val releaseUninstall = CountDownLatch(1)
        val worker = Thread {
            check(gate.tryAcquire())
            try {
                uninstallEntered.countDown()
                releaseUninstall.await(2L, TimeUnit.SECONDS)
            } finally {
                gate.release()
            }
        }

        worker.start()
        assertTrue(uninstallEntered.await(2L, TimeUnit.SECONDS))
        assertFalse(gate.tryAcquire())
        releaseUninstall.countDown()
        worker.join(2_000L)
        assertFalse(worker.isAlive)
        assertTrue(gate.tryAcquire())
        gate.release()
    }
}
