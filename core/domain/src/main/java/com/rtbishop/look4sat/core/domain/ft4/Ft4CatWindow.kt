package com.rtbishop.look4sat.core.domain.ft4

/** A CAT transaction must finish before the guard at the end of this window. */
data class Ft4CatWindow(val nextSlotUtcMillis: Long) {
    val startUtcMillis: Long get() = nextSlotUtcMillis - 1_500L
    val deadlineUtcMillis: Long get() = nextSlotUtcMillis - 160L
    val midpointUtcMillis: Long get() = nextSlotUtcMillis + waveform.startOffsetMillis + waveform.durationMillis / 2

    fun remainingMillis(now: Long): Long =
        if (now in startUtcMillis until deadlineUtcMillis) deadlineUtcMillis - now else 0L

    companion object {
        private val waveform = Ft4WaveformSpec.ft4(12_000)

        fun following(now: Long): Ft4CatWindow =
            Ft4CatWindow((Math.floorDiv(now, waveform.slotMillis) + 1) * waveform.slotMillis)
    }
}
