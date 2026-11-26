package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.FrequencyPairResult
import com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Ft817NativeSplitTest {
    private class Radio : RadioTransport {
        override var isConnected = false
        var aSelected = true
        var split = true
        var ptt = false
        var ignoreFrequencyWrite = false
        var omitNextWriteAck = false
        var fragmentReplies = false
        val frequencies = longArrayOf(7_100_000L, 28_500_000L)
        val modes = byteArrayOf(1, 8)
        val opcodes = mutableListOf<Int>()
        private val pending = ArrayDeque<Byte>()
        override suspend fun connect(): Boolean { isConnected = true; return true }
        override suspend fun disconnect() { isConnected = false; pending.clear() }
        override suspend fun readAvailable(maxBytes: Int): ByteArray =
            ByteArray(minOf(maxBytes, pending.size, if (fragmentReplies) 1 else maxBytes)) { pending.removeFirst() }
        override suspend fun write(bytes: ByteArray): Boolean {
            val index = if (aSelected) 0 else 1
            val opcode = bytes[4].toInt() and 255
            opcodes += opcode
            val response = when (opcode) {
                0x03 -> Ft817CatProtocol.encodeFrequencyBcd(frequencies[index]) + byteArrayOf(modes[index])
                0x01 -> { if (!ignoreFrequencyWrite) frequencies[index] =
                    Ft817CatProtocol.decodeFrequencyBcd(bytes.copyOfRange(0, 4)); byteArrayOf(0) }
                0xBB -> when (bytes[1].toInt() and 255) {
                    0x55 -> byteArrayOf(if (aSelected) 0 else 1, 0)
                    0x7A -> byteArrayOf(if (split) 0x80.toByte() else 0, 0)
                    else -> error("Unexpected EEPROM address")
                }
                0x81 -> { check(!ptt); aSelected = !aSelected; byteArrayOf(0) }
                0x02, 0x82 -> { split = opcode == 2; byteArrayOf(0) }
                0xF7 -> byteArrayOf(if (ptt) 0 else 0xFF.toByte())
                0x88 -> { ptt = false; byteArrayOf(0) }
                0x0A, 0x0B -> byteArrayOf(0)
                else -> error("Unexpected opcode $opcode")
            }
            if (omitNextWriteAck && opcode == 0x01) omitNextWriteAck = false
            else pending.addAll(response.toList())
            return true
        }
    }

    @Test fun tunesBothLegsWithEitherReceiveVfoAndKeepsModesAndSplit() = runBlocking {
        for (rxA in listOf(true, false)) {
            val link = Radio().apply { aSelected = rxA }
            val radio = Ft817Controller(null, "sim", link)
            assertTrue(radio.connect())
            val result = tunePresetHalfDuplex(radio, false, 435_101_237L, 145_901_589L)
            assertTrue(result.success)
            assertEquals(435_101_230L, result.rxHz)
            assertEquals(145_901_580L, result.txHz)
            assertEquals(435_101_230L, link.frequencies[if (rxA) 0 else 1])
            assertEquals(145_901_580L, link.frequencies[if (rxA) 1 else 0])
            assertEquals(rxA, link.aSelected)
            assertTrue(link.split)
            assertArrayEquals(byteArrayOf(1, 8), link.modes)
            assertEquals(RadioBaseFrequencies(435_101_230L, 145_901_580L), radio.readTrackingFrequencies())
            assertEquals(rxA, link.aSelected)
            assertTrue(radio.configureTxCtcss(67.0))
            assertEquals(rxA, link.aSelected)
            val writes = link.opcodes.count { it == 1 }
            assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_101_237L, 145_901_589L))
            assertEquals(writes, link.opcodes.count { it == 1 })
            radio.disconnect()
        }
    }

    @Test fun intermittentAckAndFragmentationDoNotCorruptNativeSplit() = runBlocking {
        for (rxA in listOf(true, false)) {
            val link = Radio().apply { aSelected = rxA; fragmentReplies = true; omitNextWriteAck = true }
            val radio = Ft817Controller(null, "sim", link)
            assertTrue(radio.connect())
            assertTrue(radio.prepareTrackingContext("satellite"))
            repeat(5) { step ->
                val rx = 145_962_860L + step * 30
                val tx = 435_101_000L - step * 50
                assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(rx, tx))
                assertEquals(RadioBaseFrequencies(rx, tx), radio.readTrackingFrequencies())
                assertEquals(rxA, link.aSelected)
                assertEquals(false, radio.readPttState())
            }
            radio.disconnect()
            assertTrue(radio.connect())
            assertEquals(false, radio.readPttState())
            radio.disconnect()
        }
    }

    @Test fun newTrackingContextUsesNewlySelectedReceiveVfo() = runBlocking {
        val link = Radio()
        val radio = Ft817Controller(null, "sim", link)
        assertTrue(radio.connect())
        assertTrue(radio.prepareTrackingContext("first"))
        link.aSelected = false
        assertTrue(radio.prepareTrackingContext("second"))
        assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_100_000, 145_900_000))
        assertEquals(435_100_000L, link.frequencies[1])
        assertEquals(145_900_000L, link.frequencies[0])
        assertFalse(link.aSelected)
        radio.disconnect()
    }

    @Test fun holdsWhileTransmittingAndRestoresAfterFailedWrite() = runBlocking {
        val link = Radio()
        val radio = Ft817Controller(null, "sim", link)
        assertTrue(radio.connect())
        link.ptt = true
        assertEquals(FrequencyPairResult.HELD, radio.setTrackingFrequencies(435_100_000, 145_900_000))
        assertFalse(link.opcodes.contains(0x81))
        link.ptt = false
        link.frequencies[0] = 435_100_000
        link.ignoreFrequencyWrite = true
        assertEquals(FrequencyPairResult.FAILED, radio.setTrackingFrequencies(435_100_000, 145_900_000))
        assertTrue(link.aSelected)
        assertTrue(link.opcodes.contains(0x81))
        radio.disconnect()
    }

    @Test fun requiresActualSplitForPairWithoutChangingOperatorSetting() = runBlocking {
        val link = Radio().apply { split = false }
        val radio = Ft817Controller(null, "sim", link)
        assertTrue(radio.connect())
        assertEquals(FrequencyPairResult.FAILED, radio.setTrackingFrequencies(435_100_000, 145_900_000))
        assertFalse(link.split)
        assertFalse(link.opcodes.contains(0x81))
        assertTrue(radio.setSplitMode(true))
        assertEquals(true, radio.getSplitMode())
        assertTrue(radio.setVfo(false))
        assertEquals(false, radio.getVfo())
        radio.disconnect()
    }
}
