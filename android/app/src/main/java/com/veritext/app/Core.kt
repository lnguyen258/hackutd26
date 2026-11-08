package com.veritext.app

import android.content.Context
import android.graphics.Color as AColor
import kotlinx.coroutines.flow.MutableSharedFlow

enum class Decision { VERIFIED, SUSPICIOUS, UNKNOWN }

data class VerifyResult(
    val decision: Decision,
    val reason: String,
    val orgName: String? = null,
    val domain: String? = null,
    val color: Int? = null,
)

data class DirEntry(val norm: String, val name: String, val status: String)

data class OrgInfo(
    val name: String,
    val domain: String,
    val status: String,
    val color: Int,
    val senders: List<String>,
)

object Tokens {
    // Mirrors the server: trailing "vt:XXXXXXXX" (Crockford base32).
    private val MARKER = Regex("""\s*\bvt:([0-9A-HJKMNP-TV-Z]{8})\s*$""", RegexOption.IGNORE_CASE)

    fun hasMarker(body: String) = MARKER.containsMatchIn(body)
    fun strip(body: String) = MARKER.replace(body, "")
    fun code(body: String): String? = MARKER.find(body)?.groupValues?.get(1)?.uppercase()

    /** Same rule as the server's normalizeAddress. */
    fun normalize(addr: String): String {
        val a = addr.trim()
        if (a.any { it.isLetter() }) return a.lowercase().filterNot { it.isWhitespace() }
        val digits = a.filter { it.isDigit() }
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }
}

fun parseColor(hex: String?, fallback: Int = 0xFF0A66C2.toInt()): Int =
    try { AColor.parseColor(hex) } catch (_: Exception) { fallback }

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("veritext", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()

    /** "customer" | "enterprise" | null (not chosen yet) */
    var role: String?
        get() = sp.getString("role", null)
        set(v) = sp.edit().putString("role", v).apply()

    var apiKey: String?
        get() = sp.getString("apiKey", null)
        set(v) = sp.edit().putString("apiKey", v).apply()

    fun string(key: String): String? = sp.getString(key, null)
    fun putString(key: String, v: String?) = sp.edit().putString(key, v).apply()

    companion object {
        // Hosted registry on Vercel. Editable in the app.
        const val DEFAULT_SERVER = "https://veritext-spacetime.vercel.app"
    }
}

/** In-process event bus: the SMS receiver pokes the UI to reload. */
object Bus {
    val tick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}
