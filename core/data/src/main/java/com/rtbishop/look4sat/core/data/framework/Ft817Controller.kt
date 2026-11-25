/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.framework

import android.bluetooth.BluetoothManager
import android.util.Log
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.FrequencyPairResult
import com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class Ft817Controller(
    bluetoothManager: BluetoothManager?,
    private val deviceAddress: String,
    private val transport: RadioTransport = BluetoothSppRadioTransport(
        requireNotNull(bluetoothManager),
        deviceAddress
    ),
    private val variant: YaesuCatVariant = YaesuCatVariant.FT817,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics
) : IRadioController {

    private val tag = if (variant == YaesuCatVariant.FT817) "FT817" else "FT857"
    private val ioMutex = Mutex()
    private val responseTimeoutMs = if (variant == YaesuCatVariant.FT817) 3_000L else 200L
    private val pttRetries = if (variant == YaesuCatVariant.FT817) 5 else 0
    private var readCommandAcks = true
    override val supportsAtomicPair get() = variant == YaesuCatVariant.FT817
    // Capture the operator's selected receive VFO; never infer its identity from frequency.
    private var presetRxVfoA: Boolean? = null

    @Volatile private var connected = false
    override var isConnected: Boolean
        get() = connected && transport.isConnected
        private set(value) { connected = value }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext true
        if (deviceAddress.isBlank()) return@withContext false
        try {
            readCommandAcks = true
            presetRxVfoA = null
            isConnected = transport.connect()
            if (isConnected && readFrequencyAndMode() == null) {
                transport.disconnect()
                isConnected = false
                Log.e(tag, "Connected transport did not return a valid CAT response")
            }
            Log.i(tag, "Connected to $deviceAddress")
            isConnected
        } catch (cancelled: CancellationException) {
            isConnected = false
            withContext(NonCancellable) { runCatching { transport.disconnect() } }
            throw cancelled
        } catch (e: Exception) {
            Log.e(tag, "Connect error: ${e.message}")
            runCatching { transport.disconnect() }
            isConnected = false
            false
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO + NonCancellable) {
        try {
            if (isConnected) withTimeoutOrNull(PTT_OFF_TIMEOUT_MS) { pttOff() }
            transport.disconnect()
        } catch (e: Exception) {
            Log.e(tag, "Disconnect error: ${e.message}")
            runCatching { transport.disconnect() }
        } finally {
            isConnected = false
            Log.i(tag, "Disconnected from $deviceAddress")
        }
    }

    override suspend fun setFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            setFrequencyLocked(frequencyHz)
        }
    }

    private suspend fun setFrequencyLocked(frequencyHz: Long): Boolean {
        val command = runCatching { Ft817CatProtocol.buildSetFreqCommand(frequencyHz) }
            .getOrElse { return false }
        val acknowledged = sendCommandWithAck(command)
        if (acknowledged && variant == YaesuCatVariant.FT817) delay(FT817_FREQUENCY_SETTLE_MS)
        return acknowledged
    }

    private fun catFailure(stage: String, message: String) {
        diagnostics.record(ControlDiagnosticSource.RADIO, "yaesu/$stage", message,
            ControlDiagnosticSeverity.WARNING)
    }

    private suspend fun readEepromLocked(address: Int): ByteArray? {
        if (!sendCommand(Ft817CatProtocol.buildEepromReadCommand(address))) return null
        return readExact(2, EEPROM_TIMEOUT_MS).also {
            if (it == null) catFailure("eeprom", "address=0x${address.toString(16)} expected=2 timeout")
        }
    }

    private suspend fun readVfoLocked(): Boolean? =
        readEepromLocked(Ft817CatProtocol.VFO_STATE_ADDRESS)?.let(Ft817CatProtocol::parseVfoA)

    private suspend fun readSplitLocked(): Boolean? =
        readEepromLocked(Ft817CatProtocol.SPLIT_STATE_ADDRESS)?.let(Ft817CatProtocol::parseSplitEnabled)

    private suspend fun readPttLocked(): Boolean? {
        if (!sendCommand(Ft817CatProtocol.buildReadTxStatusCommand())) return null
        return readExact(1, PTT_STATUS_TIMEOUT_MS)?.singleOrNull()?.let {
            Ft817CatProtocol.parsePttState(it, variant)
        }
    }

    private suspend fun selectVfoLocked(vfoA: Boolean): Boolean {
        val current = readVfoLocked() ?: return false
        if (current == vfoA) return true
        if (readPttLocked() != false) return false
        if (!sendCommandWithAck(Ft817CatProtocol.buildVfoToggleCommand())) return false
        delay(FT817_FREQUENCY_SETTLE_MS)
        val actual = readVfoLocked()
        if (actual != vfoA) catFailure("vfo", "targetA=$vfoA actualA=$actual")
        return actual == vfoA
    }

    suspend fun getVfo(): Boolean? = withContext(Dispatchers.IO) {
        ioMutex.withLock { if (supportsAtomicPair) readVfoLocked() else null }
    }

    suspend fun getSplitMode(): Boolean? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (supportsAtomicPair && readPttLocked() == false) readSplitLocked() else null
        }
    }

    override suspend fun setVfo(vfoA: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            supportsAtomicPair && readPttLocked() == false && selectVfoLocked(vfoA)
        }
    }

    override suspend fun setSplitMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!supportsAtomicPair || readPttLocked() != false) return@withLock false
            val current = readSplitLocked() ?: return@withLock false
            if (current == enabled) return@withLock true
            if (!sendCommandWithAck(Ft817CatProtocol.buildSplitCommand(enabled))) return@withLock false
            delay(FT817_FREQUENCY_SETTLE_MS)
            readSplitLocked() == enabled
        }
    }

    private suspend fun restoreRxLocked(rxVfoA: Boolean): Boolean = withContext(NonCancellable) {
        withTimeoutOrNull(1_000L) { selectVfoLocked(rxVfoA) } == true
    }.also { if (!it) catFailure("restore", "RX VFO restoration failed targetA=$rxVfoA") }

    // ioMutex and the outer RadioCommandActor own the complete VFO transaction.
    private suspend fun <T : Any> withPresetVfosLocked(
        requireSplit: Boolean, operation: suspend (Boolean) -> T?
    ): T? {
        val current = readVfoLocked() ?: return null
        val rxVfoA = presetRxVfoA ?: current.also { presetRxVfoA = it }
        if (requireSplit && readSplitLocked() != true) {
            catFailure("split", "SPLIT not confirmed; retaining operator configuration")
            return null
        }
        var restored = false
        val result = try {
            operation(rxVfoA)
        } finally {
            restored = restoreRxLocked(rxVfoA)
        }
        return result.takeIf { restored }
    }

    private suspend fun readFrequencyLocked(): Pair<Long, String>? {
        if (!sendCommand(Ft817CatProtocol.buildReadFreqModeCommand())) return null
        return readExact(RESPONSE_SIZE, responseTimeoutMs)?.let(Ft817CatProtocol::parseReadResponse)
    }

    private suspend fun tuneSelectedLocked(frequencyHz: Long): Long? {
        val target = Ft817CatProtocol.quantizeFrequencyHz(frequencyHz)
        val before = readFrequencyLocked()?.first ?: return null
        if (before == target) return before
        if (readPttLocked() != false || !setFrequencyLocked(target)) return null
        val actual = readFrequencyLocked()?.first
        if (actual != target) catFailure("frequency", "targetHz=$target actualHz=$actual")
        return actual?.takeIf { it == target }
    }

    private suspend fun tunePairLocked(rxHz: Long, txHz: Long?): Pair<FrequencyPairResult, RadioBaseFrequencies?> {
        if (!supportsAtomicPair) return FrequencyPairResult.UNSUPPORTED to null
        when (readPttLocked()) {
            true -> return FrequencyPairResult.HELD to null
            null -> return FrequencyPairResult.FAILED to null
            false -> Unit
        }
        val pair = withPresetVfosLocked(txHz != null) { rxA ->
            if (!selectVfoLocked(rxA)) return@withPresetVfosLocked null
            val rx = tuneSelectedLocked(rxHz) ?: return@withPresetVfosLocked null
            val tx = if (txHz != null) {
                if (!selectVfoLocked(!rxA)) return@withPresetVfosLocked null
                tuneSelectedLocked(txHz) ?: return@withPresetVfosLocked null
            } else null
            RadioBaseFrequencies(rx, tx)
        }
        return if (pair != null) FrequencyPairResult.APPLIED to pair
        else FrequencyPairResult.FAILED to null
    }

    override suspend fun setTrackingFrequencies(rxHz: Long, txHz: Long?): FrequencyPairResult =
        withContext(Dispatchers.IO) { ioMutex.withLock { tunePairLocked(rxHz, txHz).first } }

    override suspend fun tuneAndReadTrackingFrequencies(rxHz: Long, txHz: Long?): RadioBaseFrequencies? =
        withContext(Dispatchers.IO) { ioMutex.withLock { tunePairLocked(rxHz, txHz).second } }

    override suspend fun readTrackingFrequencies(allowBandExchange: Boolean): RadioBaseFrequencies? =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                if (!supportsAtomicPair || !allowBandExchange || readPttLocked() != false) return@withLock null
                withPresetVfosLocked(false) { rxA ->
                    if (!selectVfoLocked(rxA)) return@withPresetVfosLocked null
                    val rx = readFrequencyLocked()?.first ?: return@withPresetVfosLocked null
                    if (readSplitLocked() != true) return@withPresetVfosLocked RadioBaseFrequencies(rx, null)
                    if (!selectVfoLocked(!rxA)) return@withPresetVfosLocked null
                    val tx = readFrequencyLocked()?.first ?: return@withPresetVfosLocked null
                    RadioBaseFrequencies(rx, tx)
                }
            }
        }

    private suspend fun <T : Any> accessLeg(tx: Boolean, operation: suspend () -> T?): T? =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                if (readPttLocked() != false) return@withLock null
                withPresetVfosLocked(tx) { rxA ->
                    if (!selectVfoLocked(if (tx) !rxA else rxA)) null else operation()
                }
            }
        }

    override suspend fun setWorkingFrequency(frequencyHz: Long): Boolean =
        if (supportsAtomicPair) accessLeg(false) { tuneSelectedLocked(frequencyHz) } != null
        else setFrequency(frequencyHz)

    override suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean =
        if (supportsAtomicPair) accessLeg(true) { tuneSelectedLocked(frequencyHz) } != null
        else setFrequency(frequencyHz)

    override suspend fun readWorkingFrequency(): Long? =
        if (supportsAtomicPair) accessLeg(false) { readFrequencyLocked()?.first }
        else readFrequencyAndMode()?.first

    override suspend fun readTxVfoFrequency(): Long? =
        if (supportsAtomicPair) accessLeg(true) { readFrequencyLocked()?.first }
        else readFrequencyAndMode()?.first

    override suspend fun setSplitModes(rxMode: String?, txMode: String?): Boolean {
        if (!supportsAtomicPair) return super<IRadioController>.setSplitModes(rxMode, txMode)
        return withContext(Dispatchers.IO) {
            ioMutex.withLock {
                if (readPttLocked() != false) return@withLock false
                withPresetVfosLocked(txMode != null) { rxA ->
                    suspend fun modeOn(vfo: Boolean, mode: String?): Boolean {
                        if (mode == null) return true
                        val command = Ft817CatProtocol.buildSetModeCommand(mode) ?: return false
                        return selectVfoLocked(vfo) && sendCommandWithAck(command)
                    }
                    modeOn(rxA, rxMode) && modeOn(!rxA, txMode)
                } == true
            }
        }
    }

    override suspend fun setTxDataMode(enabled: Boolean, baseMode: String): Boolean {
        if (!supportsAtomicPair) return setDataMode(enabled, baseMode)
        val target = if (!enabled) baseMode else if (
            baseMode.equals("FM", true) || baseMode.equals("FM-N", true)
        ) "PKT" else "DIG"
        val command = Ft817CatProtocol.buildSetModeCommand(target) ?: return false
        return accessLeg(true) { sendCommandWithAck(command) } == true
    }

    override suspend fun setMode(mode: String): Boolean = withContext(Dispatchers.IO) {
        val cmd = Ft817CatProtocol.buildSetModeCommand(mode) ?: return@withContext false
        ioMutex.withLock { sendCommandWithAck(cmd) }
    }

    override suspend fun setDataMode(enabled: Boolean, baseMode: String): Boolean {
        val target = if (enabled) {
            if (baseMode.equals("FM", ignoreCase = true) || baseMode.equals("FM-N", ignoreCase = true)) {
                "PKT"
            } else {
                "DIG"
            }
        } else {
            baseMode
        }
        return setMode(target)
    }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithAck(Ft817CatProtocol.buildCtcssModeCommand(enabled))
        }
    }

    override suspend fun setCtcssTone(toneHz: Double): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            sendCommandWithAck(Ft817CatProtocol.buildSetCtcssToneCommand(toneHz))
        }
    }

    override suspend fun readFrequencyAndMode(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val sent = sendCommand(Ft817CatProtocol.buildReadFreqModeCommand())
            if (!sent) return@withContext null
            val response = readExact(RESPONSE_SIZE, responseTimeoutMs)
            val parsed=response?.let(Ft817CatProtocol::parseReadResponse)
            if(parsed==null) {
                isConnected=false
                withContext(NonCancellable) { runCatching { transport.disconnect() } }
            }
            parsed
        }
    }

    override suspend fun pttOn(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { setPttAndConfirm(enabled = true) }
    }

    override val supportsPttReadback get() = true
    override suspend fun readPttState(): Boolean? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!sendCommand(Ft817CatProtocol.buildReadTxStatusCommand())) return@withLock null
            readExact(1, PTT_STATUS_TIMEOUT_MS)?.singleOrNull()?.let {
                Ft817CatProtocol.parsePttState(it, variant)
            }
        }
    }

    override suspend fun pttOff(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { setPttAndConfirm(enabled = false) }
    }

    private suspend fun sendCommand(bytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!isConnected || !transport.write(bytes)) {
                    isConnected = false
                    return@withContext false
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(tag, "Send error: ${e.message}")
                isConnected = false
                false
            }
        }
    }

    /**
     * Legacy Yaesu CAT documents a one-byte command acknowledgement but does not define its value.
     * Some compatible interfaces omit it entirely, which Hamlib handles by disabling ACK reads after
     * the first timeout. Mirror that behaviour instead of incorrectly requiring an ACK value of zero.
     */
    private suspend fun sendCommandWithAck(bytes: ByteArray): Boolean {
        if (!sendCommand(bytes)) return false
        if (!readCommandAcks) {
            delay(NO_ACK_POST_WRITE_DELAY_MS)
            return true
        }
        if (readByteWithTimeout(ACK_TIMEOUT_MS) == null) {
            if (!isConnected) return false
            readCommandAcks = false
            Log.i(tag, "CAT interface does not return command ACK bytes; continuing without ACK reads")
        }
        return true
    }

    private suspend fun setPttAndConfirm(enabled: Boolean): Boolean {
        val command = if (enabled) {
            Ft817CatProtocol.buildPttOnCommand()
        } else {
            Ft817CatProtocol.buildPttOffCommand()
        }
        if (variant == YaesuCatVariant.FT857) {
            val sent = sendCommandWithAck(command)
            if (sent && !enabled) delay(FT857_PTT_OFF_SETTLE_MS)
            return sent
        }

        val attempts = if (enabled) 1 else pttRetries + 1
        repeat(attempts) { attempt ->
            if (!sendCommandWithAck(command)) return false
            if (!sendCommand(Ft817CatProtocol.buildReadTxStatusCommand())) return false
            val status = readExact(1, PTT_STATUS_TIMEOUT_MS)?.singleOrNull()
            if (status != null && Ft817CatProtocol.parsePttState(status, variant) == enabled) return true
            if (attempt + 1 < attempts) delay(FT817_PTT_RETRY_DELAY_MS)
        }
        Log.e(tag, "PTT ${if (enabled) "ON" else "OFF"} was not confirmed by TX status")
        return false
    }

    private suspend fun readExact(size: Int, timeoutMs: Long): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val buffer = ByteArray(size)
                var read = 0
                val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
                while (read < size) {
                    if (!transport.isConnected) {
                        isConnected = false
                        return@withContext null
                    }
                    val chunk = transport.readAvailable(size - read)
                    if (chunk.isEmpty()) {
                        if (System.nanoTime() >= deadline) return@withContext null
                        delay(POLL_INTERVAL_MS.milliseconds)
                        continue
                    }
                    chunk.copyInto(buffer, read)
                    read += chunk.size
                }
                buffer
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(tag, "Read error: ${e.message}")
                isConnected = false
                null
            }
        }
    }

    private suspend fun readByteWithTimeout(timeoutMs: Long): Int? = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
        try {
            while (System.nanoTime() < deadline) {
                if (!transport.isConnected) {
                    isConnected = false
                    return@withContext null
                }
                val value = transport.readAvailable(1).firstOrNull()
                if (value != null) return@withContext value.toInt() and 0xFF
                delay(POLL_INTERVAL_MS.milliseconds)
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(tag, "ACK read error: ${error.message}")
            isConnected = false
            null
        }
    }

    private companion object {
        const val EEPROM_TIMEOUT_MS = 500L
        const val RESPONSE_SIZE = 5
        const val ACK_TIMEOUT_MS = 200L
        const val PTT_STATUS_TIMEOUT_MS = 200L
        const val PTT_OFF_TIMEOUT_MS = 1_500L
        const val POLL_INTERVAL_MS = 10L
        const val FT817_FREQUENCY_SETTLE_MS = 50L
        const val FT817_PTT_RETRY_DELAY_MS = 100L
        const val FT857_PTT_OFF_SETTLE_MS = 200L
        const val NO_ACK_POST_WRITE_DELAY_MS = 10L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
