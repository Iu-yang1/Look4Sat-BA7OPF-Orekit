package com.rtbishop.look4sat.core.presentation

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.rtbishop.look4sat.core.domain.model.DisplayDensity
import kotlin.math.roundToInt

private data class DisplayEnvironment(val density: Density, val configuration: Configuration)
private val LocalDisplayEnvironment = staticCompositionLocalOf<DisplayEnvironment?> { null }

@Composable
fun AppDisplayDensity(scale: Float, content: @Composable () -> Unit) {
    val baseDensity = LocalDensity.current
    val configuration = LocalConfiguration.current
    val normalized = DisplayDensity.normalize(scale)
    val density = remember(baseDensity, normalized) {
        scaledAppDensity(baseDensity, normalized)
    }
    val scaledConfiguration = remember(configuration, density, normalized) {
        Configuration(configuration).apply {
            densityDpi = (density.density * 160f).roundToInt()
            screenWidthDp = (configuration.screenWidthDp / normalized).roundToInt()
            screenHeightDp = (configuration.screenHeightDp / normalized).roundToInt()
            smallestScreenWidthDp = (configuration.smallestScreenWidthDp / normalized).roundToInt()
        }
    }
    val environment = remember(density, scaledConfiguration) { DisplayEnvironment(density, scaledConfiguration) }
    CompositionLocalProvider(LocalDensity provides density, LocalConfiguration provides scaledConfiguration,
        LocalDisplayEnvironment provides environment, content = content)
}

/** Separate Android windows otherwise replace the parent composition's density. */
@Composable
fun AppDisplayWindow(content: @Composable () -> Unit) {
    val environment = LocalDisplayEnvironment.current
    HighRefreshRateWindow()
    if (environment == null) content() else CompositionLocalProvider(
        LocalDensity provides environment.density,
        LocalConfiguration provides environment.configuration,
        content = content
    )
}

internal fun scaledAppDensity(base: Density, scale: Float): Density {
    val normalized = DisplayDensity.normalize(scale)
    if (normalized == 1f) return base
    return object : Density {
        override val density = base.density * normalized
        override val fontScale = base.fontScale
        override fun Dp.toSp(): TextUnit = with(base) { this@toSp.toSp() }
        override fun TextUnit.toDp(): Dp = with(base) { this@toDp.toDp() }
    }
}
