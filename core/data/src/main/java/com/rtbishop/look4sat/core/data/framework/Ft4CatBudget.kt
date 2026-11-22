package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.ft4.Ft4CatWindow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException

internal class Ft4CatBudget(
    private val window: Ft4CatWindow?,
    private val now: () -> Long
) : AbstractCoroutineContextElement(Key) {
    fun remainingMillis(): Long = window?.remainingMillis(now()) ?: Long.MAX_VALUE

    fun checkBeforeCommand() {
        if (remainingMillis() == 0L) throw CancellationException("FT4 CAT window closed")
    }

    companion object Key : CoroutineContext.Key<Ft4CatBudget> {
        val cleanup = Ft4CatBudget(null) { 0L }
    }
}
