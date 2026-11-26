package com.rtbishop.look4sat.core.presentation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class RefreshRateMode(val width: Int, val height: Int, val rate: Float)

internal fun preferredUiRefreshRate(width: Int, height: Int, modes: List<RefreshRateMode>, powerSave: Boolean): Float =
    if (powerSave) 0f else modes.asSequence().filter {
        it.width == width && it.height == height && it.rate.isFinite() && it.rate > 0f
    }.maxOfOrNull { it.rate } ?: 0f

/** A foreground preference; Android retains control over power, thermals and seamless switching. */
@Composable
fun HighRefreshRateWindow() {
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(view, owner) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.findActivity()?.window
        if (window == null || view.isInEditMode) return@DisposableEffect onDispose {}
        val displays = view.context.getSystemService(DisplayManager::class.java)
        val power = view.context.getSystemService(PowerManager::class.java)
        val original = window.attributes.preferredRefreshRate
        fun update() {
            val display = view.display
            val active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && view.isAttachedToWindow
            val preferred = if (!active || display == null) original else {
                val current = display.mode
                preferredUiRefreshRate(current.physicalWidth, current.physicalHeight,
                    display.supportedModes.map { RefreshRateMode(it.physicalWidth, it.physicalHeight, it.refreshRate) },
                    power?.isPowerSaveMode == true)
            }
            if (window.attributes.preferredRefreshRate != preferred) {
                window.attributes = window.attributes.apply { preferredRefreshRate = preferred }
            }
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) = update()
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = update()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = update()
        }
        val lifecycle = LifecycleEventObserver { _, _ -> update() }
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = update()
            override fun onViewDetachedFromWindow(view: View) = update()
        }
        displays?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        ContextCompat.registerReceiver(view.context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        owner.lifecycle.addObserver(lifecycle)
        view.addOnAttachStateChangeListener(attachment)
        update()
        onDispose {
            displays?.unregisterDisplayListener(listener)
            view.context.unregisterReceiver(receiver)
            owner.lifecycle.removeObserver(lifecycle)
            view.removeOnAttachStateChangeListener(attachment)
            window.attributes = window.attributes.apply { preferredRefreshRate = original }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
