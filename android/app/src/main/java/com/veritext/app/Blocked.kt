package com.veritext.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Running count of scam messages caught on this phone. Stores only one-way hashes of
 * (sender|text) to avoid double counting. Message text itself is never kept here.
 */
object Blocked {
    private const val BURST_MS = 15_000L
    private var state: MutableStateFlow<Int>? = null

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("veritext_blocked", Context.MODE_PRIVATE)

    @Synchronized
    fun count(ctx: Context): StateFlow<Int> {
        val s = state ?: MutableStateFlow(sp(ctx).getStringSet("keys", emptySet())!!.size).also { state = it }
        return s
    }

    /** Counting starts the first time this runs, so old inbox messages never inflate the counter. */
    private fun since(ctx: Context): Long {
        val s = sp(ctx)
        val t = s.getLong("since", 0L)
        if (t != 0L) return t
        val now = System.currentTimeMillis()
        s.edit().putLong("since", now).apply()
        return now
    }

    /** Returns true if this was a newly caught message (counter went up). */
    @Synchronized
    fun record(ctx: Context, from: String, body: String, date: Long = System.currentTimeMillis()): Boolean {
        if (date < since(ctx)) return false
        // The same text can be seen twice (SMS receiver + its notification): one burst per sender counts once.
        val burstKey = "last_" + Tokens.normalize(from)
        if (kotlin.math.abs(date - sp(ctx).getLong(burstKey, 0L)) < BURST_MS) return false
        val keys = HashSet(sp(ctx).getStringSet("keys", emptySet())!!)
        if (!keys.add(Verifier.keyFor(from, body))) return false
        sp(ctx).edit().putStringSet("keys", keys).putLong(burstKey, date).apply()
        (count(ctx) as MutableStateFlow<Int>).value = keys.size
        return true
    }

    @Synchronized
    fun reset(ctx: Context) {
        sp(ctx).edit().remove("keys").putLong("since", System.currentTimeMillis()).apply()
        (count(ctx) as MutableStateFlow<Int>).value = 0
    }
}
