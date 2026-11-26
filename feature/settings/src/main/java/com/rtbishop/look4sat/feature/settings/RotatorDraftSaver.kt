package com.rtbishop.look4sat.feature.settings

import androidx.compose.runtime.saveable.mapSaver
import com.rtbishop.look4sat.core.domain.rotator.RotatorSettings
import com.rtbishop.look4sat.core.domain.rotator.RotatorProtocol
import com.rtbishop.look4sat.core.domain.rotator.RotatorTransport
import com.rtbishop.look4sat.core.domain.rotator.RotatorAzimuthRange

internal val rotatorDraftSaver = mapSaver(
    save = { draft -> mapOf(
        "enabled" to draft.enabled,
        "protocol" to draft.protocol.name,
        "transport" to draft.transport.name,
        "deviceAddress" to draft.deviceAddress,
        "host" to draft.host,
        "port" to draft.port,
        "baudRate" to draft.baudRate,
        "customPointTemplate" to draft.customPointTemplate,
        "customStopTemplate" to draft.customStopTemplate,
        "customQueryTemplate" to draft.customQueryTemplate,
        "prepositionLeadSeconds" to draft.prepositionLeadSeconds,
        "trackingLeadSeconds" to draft.trackingLeadSeconds,
        "azimuthLookAheadSeconds" to draft.azimuthLookAheadSeconds,
        "azimuthRange" to draft.azimuthRange.name,
        "azimuthOffsetDegrees" to draft.azimuthOffsetDegrees,
        "elevationOffsetDegrees" to draft.elevationOffsetDegrees,
        "deadbandDegrees" to draft.deadbandDegrees,
        "magneticCorrection" to draft.magneticCorrection,
        "parkAzimuthDegrees" to draft.parkAzimuthDegrees,
        "parkElevationDegrees" to draft.parkElevationDegrees,
        "parkOnLos" to draft.parkOnLos,
        "parkOnDisconnect" to draft.parkOnDisconnect,
        "flipOverheadPasses" to draft.flipOverheadPasses,
        "minimumElevationDegrees" to draft.minimumElevationDegrees,
        "updateIntervalMillis" to draft.updateIntervalMillis,
        "sampleTimeoutMillis" to draft.sampleTimeoutMillis,
        "customResponseTemplate" to draft.customResponseTemplate
    ) },
    restore = { values -> RotatorSettings(
        enabled = values["enabled"] as Boolean,
        protocol = RotatorProtocol.valueOf(values["protocol"] as String),
        transport = RotatorTransport.valueOf(values["transport"] as String),
        deviceAddress = values["deviceAddress"] as String,
        host = values["host"] as String,
        port = values["port"] as Int,
        baudRate = values["baudRate"] as Int,
        customPointTemplate = values["customPointTemplate"] as String,
        customStopTemplate = values["customStopTemplate"] as String,
        customQueryTemplate = values["customQueryTemplate"] as String,
        prepositionLeadSeconds = values["prepositionLeadSeconds"] as Int,
        trackingLeadSeconds = values["trackingLeadSeconds"] as Int,
        azimuthLookAheadSeconds = values["azimuthLookAheadSeconds"] as Int,
        azimuthRange = RotatorAzimuthRange.valueOf(values["azimuthRange"] as String),
        azimuthOffsetDegrees = values["azimuthOffsetDegrees"] as Double,
        elevationOffsetDegrees = values["elevationOffsetDegrees"] as Double,
        deadbandDegrees = values["deadbandDegrees"] as Double,
        magneticCorrection = values["magneticCorrection"] as Boolean,
        parkAzimuthDegrees = values["parkAzimuthDegrees"] as Double,
        parkElevationDegrees = values["parkElevationDegrees"] as Double,
        parkOnLos = values["parkOnLos"] as Boolean,
        parkOnDisconnect = values["parkOnDisconnect"] as Boolean,
        flipOverheadPasses = values["flipOverheadPasses"] as Boolean,
        minimumElevationDegrees = values["minimumElevationDegrees"] as Double,
        updateIntervalMillis = values["updateIntervalMillis"] as Long,
        sampleTimeoutMillis = values["sampleTimeoutMillis"] as Long,
        customResponseTemplate = values["customResponseTemplate"] as String
    ) }
)
