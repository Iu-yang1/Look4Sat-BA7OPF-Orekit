package com.rtbishop.look4sat.feature.settings

import androidx.compose.runtime.saveable.listSaver
import com.rtbishop.look4sat.core.domain.audio.RadioAudioBackend
import com.rtbishop.look4sat.core.domain.audio.RadioAudioSettings

internal val radioAudioDraftSaver = listSaver<RadioAudioSettings, Any>(
    save = { listOf(it.backend.name, it.rxDeviceKey, it.txDeviceKey, it.host,
        it.controlPort, it.networkCat, it.txLatencyMillis) },
    restore = { RadioAudioSettings(RadioAudioBackend.valueOf(it[0] as String),
        it[1] as String, it[2] as String, it[3] as String, it[4] as Int,
        it[5] as Boolean, it[6] as Long) }
)
