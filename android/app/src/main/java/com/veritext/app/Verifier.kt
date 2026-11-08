package com.veritext.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/**
 * Consumer-side verification.
 *
 * Privacy rule: a message is only sent to the server if it carries a VeriText token
 * or comes from a number in the public registry directory. Everything else (friends,
 * other apps' OTPs...) never leaves the phone.
 */
object Verifier {

    private fun sha(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun keyFor(from: String, body: String) = cacheKey(from, body)

    private fun cacheKey(from: String, body: String) = "c_" + sha(from + "|" + body)

    // ---- registry directory (public list of verified sender numbers) ----

    suspend fun refreshDirectory(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val p = Prefs(ctx)
        try {
            val list = Api.directory(p.serverUrl)
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("n", it.norm).put("name", it.name).put("s", it.status)) }
            p.putString("directory", arr.toString())
            true
        } catch (_: IOException) {
            false
        } catch (_: ApiException) {
            false
        }
    }

    fun directory(ctx: Context): List<DirEntry> {
        val raw = Prefs(ctx).string("directory") ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { val o = arr.getJSONObject(it); DirEntry(o.getString("n"), o.getString("name"), o.getString("s")) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun ownerOf(ctx: Context, address: String): DirEntry? {
        val n = Tokens.normalize(address)
        return directory(ctx).firstOrNull { it.norm == n }
    }

    // ---- per-message verification ----

    fun cached(ctx: Context, from: String, body: String): VerifyResult? {
        val raw = Prefs(ctx).string(cacheKey(from, body)) ?: return null
        return try {
            val o = JSONObject(raw)
            VerifyResult(
                Decision.valueOf(o.getString("d")), o.getString("r"),
                o.optString("n").ifEmpty { null }, o.optString("dom").ifEmpty { null },
                if (o.has("c")) o.getInt("c") else null,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun store(ctx: Context, from: String, body: String, r: VerifyResult) {
        val o = JSONObject().put("d", r.decision.name).put("r", r.reason)
        r.orgName?.let { o.put("n", it) }
        r.domain?.let { o.put("dom", it) }
        r.color?.let { o.put("c", it) }
        Prefs(ctx).putString(cacheKey(from, body), o.toString())
    }

    /** Does this message need the server at all? */
    fun relevant(ctx: Context, from: String, body: String) =
        Tokens.hasMarker(body) || ownerOf(ctx, from) != null

    /**
     * Returns a result, or null if we should have checked but the network failed
     * (caller shows "couldn't verify" and retries later).
     */
    suspend fun verify(ctx: Context, from: String, body: String): VerifyResult? {
        cached(ctx, from, body)?.let { return it }
        if (!relevant(ctx, from, body)) return VerifyResult(Decision.UNKNOWN, "not_in_registry")
        return withContext(Dispatchers.IO) {
            try {
                Api.verify(Prefs(ctx).serverUrl, from, body).also { store(ctx, from, body, it) }
            } catch (_: IOException) {
                null
            } catch (_: ApiException) {
                null
            }
        }
    }
}
