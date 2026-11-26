package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.utility.DialFollowPolicy
import com.rtbishop.look4sat.core.domain.utility.DialFollowState
import com.rtbishop.look4sat.core.domain.utility.DialLeg
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CatControlReliabilityTest {
    @Test fun independentUsbPortsDoNotOverlapButCrossAssignedInterfacesDo() {
        val a = UsbSerialSelector(1, 0, 1, null, null)
        assertTrue(usbSelectorsOverlap(a, a.copy(controlInterfaceId = 1, dataInterfaceId = 2)))
        assertFalse(usbSelectorsOverlap(a, a.copy(controlInterfaceId = 2, dataInterfaceId = 3)))
        assertFalse(usbSelectorsOverlap(a, a.copy(deviceId = 2)))
    }

    private class Wire(val response: (ByteArray) -> ByteArray?) : RadioTransport {
        val pending = ArrayDeque<Byte>()
        val writes = mutableListOf<ByteArray>()
        override var isConnected = false
        override suspend fun connect(): Boolean { isConnected = true; return true }
        override suspend fun disconnect() { isConnected = false; pending.clear() }
        override suspend fun write(bytes: ByteArray): Boolean {
            if (!isConnected) return false
            writes += bytes.copyOf()
            response(bytes)?.forEach { pending.addLast(it) }
            return true
        }
        override suspend fun readAvailable(maxBytes: Int) =
            ByteArray(minOf(maxBytes, pending.size)) { pending.removeFirst() }
    }

    @Test fun timedOutTextQueryClosesStreamBeforeLateReplyCanMatchAnotherQuery() = runTest {
        val wire = Wire { null }
        wire.connect()
        val link = RadioTextLink(wire, ';')
        assertNull(link.query("FA", "FA", 10))
        assertFalse(wire.isConnected)
        wire.pending.addAll("FA00145900000;".encodeToByteArray().toList())
        assertNull(link.query("FA", "FA", 10))
        assertEquals(1, wire.writes.size)
    }

    @Test fun explicitTextRejectionDoesNotRequireReconnect() = runTest {
        val wire = Wire { "?;".encodeToByteArray() }
        wire.connect()
        assertNull(RadioTextLink(wire, ';').query("FA", "FA"))
        assertTrue(wire.isConnected)
    }

    @Test fun readOnlyRigctldRejectsEveryWriteIncludingSplitAndTone() = runTest {
        val wire = Wire {
            when (it.decodeToString().trim()) {
                "\\get_freq" -> "145900000\n"
                "\\get_mode" -> "USB\n2400\n"
                else -> "RPRT 0\n"
            }.encodeToByteArray()
        }
        val radio = HamlibRigctldController("audit:4532", wire,
            pttReadbackSupported = true, receiveOnly = true, supportsFrequencyControl = false)
        assertTrue(radio.connect())
        val reads = wire.writes.size
        assertFalse(radio.setFrequency(145901000))
        assertFalse(radio.setMode("CW"))
        assertFalse(radio.setTxVfoFrequency(145901000))
        assertFalse(radio.setTxDataMode(true, "USB"))
        assertFalse(radio.setSplitModes("USB", "LSB"))
        assertFalse(radio.setSplitMode(true))
        assertFalse(radio.setVfo(false))
        assertFalse(radio.setCtcssMode(true))
        assertFalse(radio.setCtcssTone(67.0))
        assertFalse(radio.configureTxCtcss(67.0))
        assertFalse(radio.pttOn())
        radio.disconnect()
        assertEquals(reads, wire.writes.size)
    }

    @Test fun handheldAppliedStepDoesNotBecomeAUserDialChange() = runTest {
        var actual = 145900000L
        var mode = '6'
        val wire = Wire { bytes ->
            when (val command = bytes.decodeToString().trim()) {
                "ID" -> "ID TH-D74\r".encodeToByteArray()
                "FO 1" -> "FO 1,${actual.toString().padStart(10, '0')},0\r".encodeToByteArray()
                "MD 1" -> "MD 1,$mode\r".encodeToByteArray()
                else -> {
                    if (command.startsWith("FQ 1,")) actual = command.substringAfter(',').toLong()
                    null
                }
            }
        }
        val radio = KenwoodController(wire, KenwoodModel.TH_D74, false)
        assertTrue(radio.connect())
        assertTrue(radio.setFrequency(145901234))
        val applied = radio.quantizeTrackingFrequency(145901234)
        assertEquals(actual, applied)
        val settled = DialFollowPolicy.update(DialFollowState(), DialLeg.RX,
            actual, applied, 1500, 200, 1500)
        assertNull(settled.acceptedFrequencyHz)
        assertNull(settled.state.activeLeg)
        mode = '4'
        val fineRadio = KenwoodController(wire, KenwoodModel.TH_D74, false)
        assertTrue(fineRadio.connect())
        assertTrue(fineRadio.setFrequency(145901234))
        assertEquals(145901240L, actual)
        assertEquals(actual, fineRadio.quantizeTrackingFrequency(145901234))
    }
}
