package com.rtbishop.look4sat.core.domain.model

object DisplayDensity {
    const val MIN_SCALE = 0.75f
    const val MAX_SCALE = 1.30f
    fun normalize(value: Float): Float = if (value.isFinite()) value.coerceIn(MIN_SCALE, MAX_SCALE) else 1f
}
