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
    private var recoveryNeeded = false
    private var transactionId = 0L
    private val recentTransactions = ArrayDeque<String>()
    override val supportsAtomicPair get() = variant == YaesuCatVariant.FT817
    override fun quantizeTrackingFrequency(frequencyHz: Long) = Ft817CatProtocol.quantizeFrequencyHz(frequencyHz)
    // Capture the operator's selected receive VFO; never infer its identity from frequency.
    private var presetRxVfoA: Boolean? = null
    private var originalSplit: Boolean? = null
    private var originalDigitalMode: Int? = null
    private var appliedDigitalMode: Int? = null

    @Volatile private var connected = false
    override var isConnected: Boolean
        get() = connected && transport.isConnected
        private set(value) { connected = value }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext true
        if (deviceAddress.isBlank()) return@withContext false
        try {
            recoveryNeeded = false
            transactionId = 0L
            recentTransactions.clear()
            presetRxVfoA = null
            originalSplit = null
            originalDigitalMode = null
            appliedDigitalMode = null
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
            if (isConnected) withTimeoutOrNull(PTT_OFF_TIMEOUT_MS) {
                if (pttOff()) {
                    ioMutex.withLock { restoreDigitalModeLocked() }
                    originalSplit?.let { setSplitMode(it) }
                }
            }
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
            setFrequencyLocked(frequencyHz) && readFrequencyLocked()?.first == quantizeTrackingFrequency(frequencyHz)
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

    private suspend fun readEepromLocked(address: Int): ByteArray? =
        exchangeLocked(Ft817CatProtocol.buildEepromReadCommand(address), 2, EEPROM_TIMEOUT_MS)

    private suspend fun readVfoLocked(): Boolean? =
        readEepromLocked(Ft817CatProtocol.VFO_STATE_ADDRESS)?.let(Ft817CatProtocol::parseVfoA)

    private suspend fun readSplitLocked(): Boolean? =
        readEepromLocked(Ft817CatProtocol.SPLIT_STATE_ADDRESS)?.let(Ft817CatProtocol::parseSplitEnabled)

    private suspend fun readPttLocked(): Boolean? =
        exchangeLocked(Ft817CatProtocol.buildReadTxStatusCommand(), 1, PTT_STATUS_TIMEOUT_MS)
            ?.singleOrNull()?.let { Ft817CatProtocol.parsePttState(it, variant) }

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
            if (originalSplit == null) originalSplit = current
            if (current == enabled) return@withLock true
            if (!sendCommandWithAck(Ft817CatProtocol.buildSplitCommand(enabled))) return@withLock false
            delay(FT817_FREQUENCY_SETTLE_MS)
            readSplitLocked() == enabled
        }
    }

    override suspend fun prepareTrackingContext(contextKey: String): Boolean {
        if (!supportsAtomicPair) return true
        return withContext(Dispatchers.IO) {
            ioMutex.withLock {
                if (readPttLocked() != false) return@withLock false
                val selected = readVfoLocked() ?: return@withLock false
                presetRxVfoA = selected
                diagnostics.record(ControlDiagnosticSource.RADIO, "yaesu/preset",
                    "rxVfo=${if (selected) "A" else "B"} split=${readSplitLocked()}")
                true
            }
        }
    }

    private suspend fun restoreRxLocked(rxVfoA: Boolean): Boolean = withContext(NonCancellable) {
        withTimeoutOrNull(RX_RESTORE_TIMEOUT_MS) {
            // A lost ACK does not mean the toggle failed. Read actual VFO on each attempt.
            selectVfoLocked(rxVfoA) || selectVfoLocked(rxVfoA)
        } == true
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
        val response = exchangeLocked(Ft817CatProtocol.buildReadFreqModeCommand(), RESPONSE_SIZE,
            responseTimeoutMs) ?: return null
        return Ft817CatProtocol.parseReadResponse(response).also {
            if (it == null) {
                recoveryNeeded = true
                reportCatAnomaly("invalid frequency/mode response=${response.hex()}")
            }
        }
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
                    val split = readSplitLocked() ?: return@withPresetVfosLocked null
                    if (!split) return@withPresetVfosLocked RadioBaseFrequencies(rx, null)
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
                        return selectVfoLocked(vfo) && setModeAndConfirmLocked(command)
                    }
                    modeOn(rxA, rxMode) && modeOn(!rxA, txMode)
                } == true
            }
        }
    }

    override suspend fun configureTxCtcss(toneHz: Double?): Boolean {
        if (!supportsAtomicPair) return super<IRadioController>.configureTxCtcss(toneHz)
        return accessLeg(true) {
            if (toneHz != null) {
                sendCommandWithAck(Ft817CatProtocol.buildSetCtcssToneCommand(toneHz)) &&
                    sendCommandWithAck(Ft817CatProtocol.buildCtcssModeCommand(true))
            } else sendCommandWithAck(Ft817CatProtocol.buildCtcssModeCommand(false))
        } == true
    }

    override suspend fun setTxDataMode(enabled: Boolean, baseMode: String): Boolean {
        if (!supportsAtomicPair) return setDataMode(enabled, baseMode)
        val target = if (!enabled) baseMode else if (
            baseMode.equals("FM", true) || baseMode.equals("FM-N", true)
        ) "PKT" else "DIG"
        val command = Ft817CatProtocol.buildSetModeCommand(target) ?: return false
        return accessLeg(true) {
            setModeAndConfirmLocked(command) && (!enabled || target != "DIG" || configureDigitalModeLocked(baseMode))
        } == true
    }

    private suspend fun configureDigitalModeLocked(baseMode: String): Boolean {
        val target = when (baseMode.uppercase(java.util.Locale.US)) {
            "USB" -> 4 // USER-U
            "LSB" -> 3 // USER-L
            else -> return false
        }
        val current = readEepromLocked(Ft817CatProtocol.DIGITAL_MODE_ADDRESS) ?: return false
        val before = (current[0].toInt() and 0xE0) ushr 5
        if (before == target) return true
        if (before !in 0..4) return false
        if (originalDigitalMode == null) originalDigitalMode = before
        appliedDigitalMode = target
        return writeDigitalModeLocked(current, target)
    }

    private suspend fun writeDigitalModeLocked(current: ByteArray, target: Int): Boolean {
        val command = Ft817CatProtocol.buildDigitalModeCommand(current, target)
        // EEPROM writes have no ACK. Confirm both bytes; byte 0x66 is DCS INV.
        if (!sendCommand(command)) return false
        delay(FT817_FREQUENCY_SETTLE_MS)
        return readEepromLocked(Ft817CatProtocol.DIGITAL_MODE_ADDRESS)
            ?.contentEquals(command.copyOfRange(2, 4)) == true
    }

    private suspend fun restoreDigitalModeLocked() {
        val original = originalDigitalMode ?: return
        if (readPttLocked() != false) return
        val current = readEepromLocked(Ft817CatProtocol.DIGITAL_MODE_ADDRESS) ?: return
        // Retain a later menu change made by the operator.
        if ((current[0].toInt() and 0xE0) ushr 5 != appliedDigitalMode) return
        if (!writeDigitalModeLocked(current, original)) catFailure("data-mode", "Original DIG MODE was not restored")
    }

    override suspend fun setMode(mode: String): Boolean = withContext(Dispatchers.IO) {
        val cmd = Ft817CatProtocol.buildSetModeCommand(mode) ?: return@withContext false
        ioMutex.withLock { setModeAndConfirmLocked(cmd) }
    }

    private suspend fun setModeAndConfirmLocked(command: ByteArray): Boolean {
        if (!sendCommandWithAck(command)) return false
        delay(FT817_FREQUENCY_SETTLE_MS)
        val actual = readFrequencyLocked()?.second ?: return false
        // Frequency/mode readback exposes the base mode, not a separate narrow-filter state.
        val actualCode = Ft817CatProtocol.buildSetModeCommand(actual)?.firstOrNull() ?: return false
        return actualCode.toInt() and 0x7F == command[0].toInt() and 0x7F
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
        if (variant != YaesuCatVariant.FT817 || !enabled || target != "DIG") return setMode(target)
        val command = Ft817CatProtocol.buildSetModeCommand(target) ?: return false
        return withContext(Dispatchers.IO) {
            ioMutex.withLock { setModeAndConfirmLocked(command) && configureDigitalModeLocked(baseMode) }
        }
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
        ioMutex.withLock { readFrequencyLocked() }
    }

    override suspend fun pttOn(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { setPttAndConfirm(enabled = true) }
    }

    override val supportsPttReadback get() = true
    override suspend fun readPttState(): Boolean? = withContext(Dispatchers.IO) {
        ioMutex.withLock { readPttLocked() }
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

    // Every write gets its own ACK window. A missing ACK must never disable
    // consumption of subsequent acknowledgements.
    private suspend fun sendCommandWithAck(bytes: ByteArray): Boolean =
        exchangeLocked(bytes, 1, ACK_TIMEOUT_MS, optionalAck = true) != null

    private fun ByteArray.hex(): String = joinToString(" ") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
    }

    private fun traceCat(message: String) {
        Log.d(tag, message)
        recentTransactions.addLast(message)
        if (recentTransactions.size > 8) recentTransactions.removeFirst()
    }

    private fun reportCatAnomaly(message: String) {
        catFailure("cat", message)
        recentTransactions.forEach { catFailure("cat/trace", it) }
    }

    // CAT has no response delimiter or transaction ID. Quarantine stale bytes
    // after incomplete exchanges before sending another opcode.
    private suspend fun recoverInputLocked(): Boolean {
        val deadline = System.nanoTime() + RECOVERY_LIMIT_MS * NANOS_PER_MILLISECOND
        var lastData = System.nanoTime()
        while (isConnected && System.nanoTime() < deadline) {
            val chunk = transport.readAvailable(64)
            if (chunk.isNotEmpty()) {
                traceCat("discard=${chunk.hex()}")
                lastData = System.nanoTime()
            } else if (System.nanoTime() - lastData >= RECOVERY_QUIET_MS * NANOS_PER_MILLISECOND) {
                recoveryNeeded = false
                return true
            }
            delay(POLL_INTERVAL_MS)
        }
        reportCatAnomaly("CAT response stream did not become idle")
        return false
    }

    // Caller owns ioMutex for the complete exchange, including recovery.
    private suspend fun exchangeLocked(
        command: ByteArray, expected: Int, timeoutMs: Long, optionalAck: Boolean = false
    ): ByteArray? {
        val id = ++transactionId
        val prefix = "id=$id tx=${command.hex()} expected=$expected"
        try {
            if (recoveryNeeded && !recoverInputLocked()) {
                // Attempt PTT release even if its acknowledgement cannot be trusted.
                if (command.last() == Ft817CatProtocol.CMD_PTT_OFF) sendCommand(command)
                return null
            }
            recoveryNeeded = true
            if (!sendCommand(command)) return null
            val response = readResponse(expected, timeoutMs)
            traceCat("$prefix rx=${if (response.isEmpty()) "empty" else response.hex()}")
            if (response.size == expected && isConnected) {
                recoveryNeeded = false
                return response
            }
            if (optionalAck && response.isEmpty() && isConnected) {
                if (!recoverInputLocked()) return null
                reportCatAnomaly("$prefix missing or late ACK")
                return response
            }
            reportCatAnomaly("$prefix invalid response length=${response.size}")
            return null
        } catch (cancelled: CancellationException) {
            traceCat("$prefix cancelled")
            throw cancelled
        } catch (error: Exception) {
            reportCatAnomaly("$prefix error=${error.javaClass.simpleName}")
            return null
        }
    }

    private suspend fun setPttAndConfirm(enabled: Boolean): Boolean {
        val command = if (enabled) {
            Ft817CatProtocol.buildPttOnCommand()
        } else {
            Ft817CatProtocol.buildPttOffCommand()
        }
        val attempts = if (enabled) 1 else pttRetries + 1
        repeat(attempts) { attempt ->
            if (!sendCommandWithAck(command)) return false
            if (variant == YaesuCatVariant.FT857 && !enabled) delay(FT857_PTT_OFF_SETTLE_MS)
            if (readPttLocked() == enabled) return true
            if (attempt + 1 < attempts) delay(FT817_PTT_RETRY_DELAY_MS)
        }
        Log.e(tag, "PTT ${if (enabled) "ON" else "OFF"} was not confirmed by TX status")
        return false
    }

    private suspend fun readResponse(expected: Int, timeoutMs: Long): ByteArray {
        val buffer = ByteArray(expected + 1)
        var count = 0
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
        while (count < expected && isConnected && System.nanoTime() < deadline) {
            val chunk = transport.readAvailable(buffer.size - count)
            if (chunk.isEmpty()) {
                delay(POLL_INTERVAL_MS)
            } else {
                chunk.copyInto(buffer, count)
                count += chunk.size
            }
        }
        if (count == expected && isConnected) {
            // A stale ACK plus a complete query response is not a valid reply.
            val extra = transport.readAvailable(1)
            if (extra.isNotEmpty()) buffer[count++] = extra[0]
        }
        return buffer.copyOf(count)
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
        const val RECOVERY_QUIET_MS = 120L
        const val RECOVERY_LIMIT_MS = 500L
        const val RX_RESTORE_TIMEOUT_MS = 3_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
