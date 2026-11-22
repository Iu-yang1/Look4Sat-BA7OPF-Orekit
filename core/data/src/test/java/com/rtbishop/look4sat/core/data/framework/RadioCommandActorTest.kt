package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.ft4.Ft4CatWindow
import com.rtbishop.look4sat.core.domain.time.MonotonicTimeSource
import com.rtbishop.look4sat.core.domain.time.SystemDisciplinedClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RadioCommandActorTest {
    @Test fun slowReplyStopsTheNextFrameButAllowsRoleRestoration() = runTest {
        var now = 6_000L
        val clock = SystemDisciplinedClock(object : MonotonicTimeSource {
            override fun elapsedRealtimeNanos() = now * 1_000_000L
            override fun wallClockMillis() = now
        })
        val writes = mutableListOf<Byte>()
        val wire = TimedRadioTransport(object : RadioTransport {
            override val isConnected = true
            override suspend fun connect() = true
            override suspend fun disconnect() = Unit
            override suspend fun readAvailable(maxBytes: Int) = byteArrayOf()
            override suspend fun write(bytes: ByteArray): Boolean {
                writes += bytes.single()
                return true
            }
        })
        withContext(Ft4CatBudget(Ft4CatWindow(7_500L), clock)) {
            assertTrue(wire.write(byteArrayOf(1)))
            now = 8_001L
            assertTrue(runCatching { wire.write(byteArrayOf(2)) }.exceptionOrNull() is Ft4CatWindowExpired)
            withContext(Ft4CatBudget.cleanup) { assertTrue(wire.write(byteArrayOf(3))) }
        }
        assertEquals(listOf<Byte>(1, 3), writes)
    }

    @Test fun commandFailureCanBeHandledWithoutCancellingCallerOrWorker() = runTest {
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }
        val error = runCatching { actor.execute { error("failed configuration") } }.exceptionOrNull()
        assertEquals("failed configuration", error?.message)
        assertEquals(42, actor.execute { 42 })
    }

    @Test fun callerCancellationStopsOperationAndWorkerAcceptsNextCommand() = runTest {
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val command = async {
            actor.execute {
                started.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            }
        }
        started.await()
        command.cancel()
        runCurrent()
        assertTrue(cleaned)
        assertEquals(7, actor.execute { 7 })
    }

    @Test fun timeoutDoesNotPreventEmergencyStopFromRunning() = runTest {
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }
        val error = runCatching { actor.execute(timeoutMillis = 20) { awaitCancellation() } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(actor.executePttOff { true })
    }
}
