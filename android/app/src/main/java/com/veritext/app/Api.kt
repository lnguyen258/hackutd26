package com.veritext.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val status: Int, message: String) : Exception(message)

/** Minimal blocking HTTP client; call from Dispatchers.IO. Network failures throw IOException. */
object Api {
    private fun call(
        base: String,
        method: String,
        path: String,
        body: JSONObject? = null,
        bearer: String? = null,
        connectMs: Int = 4000,
        readMs: Int = 6000,
    ): JSONObject {
        val conn = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.setRequestProperty("Accept", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (code !in 200..299) throw ApiException(code, json.optString("error", "HTTP $code"))
            return json
        } finally {
            conn.disconnect()
        }
    }

    fun health(base: String) {
        call(base, "GET", "/api/health", connectMs = 2500, readMs = 2500)
    }

    fun verify(base: String, from: String, body: String): VerifyResult {
        val r = call(base, "POST", "/api/verify", JSONObject().put("from", from).put("body", body))
        val org = r.optJSONObject("org")
        return VerifyResult(
            decision = when (r.optString("decision")) {
                "verified" -> Decision.VERIFIED
                "suspicious" -> Decision.SUSPICIOUS
                else -> Decision.UNKNOWN
            },
            reason = r.optString("reason"),
            orgName = org?.optString("name"),
            domain = org?.optString("domain"),
            color = org?.let { parseColor(it.optString("color")) },
        )
    }

    fun directory(base: String): List<DirEntry> {
        val arr: JSONArray = call(base, "GET", "/api/directory").getJSONArray("senders")
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            DirEntry(o.getString("norm"), o.getString("name"), o.getString("status"))
        }
    }

    // ---- enterprise side ----

    /** Returns the new API key. */
    fun apply(base: String, name: String, domain: String, color: String, sender: String): String {
        val r = call(
            base, "POST", "/api/orgs/apply",
            JSONObject()
                .put("name", name).put("domain", domain).put("color", color)
                .put("senders", JSONArray().put(sender)),
        )
        return r.getString("apiKey")
    }

    fun me(base: String, apiKey: String): OrgInfo {
        val r = call(base, "GET", "/api/orgs/me", bearer = apiKey)
        val s = r.getJSONArray("senders")
        return OrgInfo(
            name = r.getString("name"),
            domain = r.getString("domain"),
            status = r.getString("status"),
            color = parseColor(r.optString("color")),
            senders = List(s.length()) { s.getString(it) },
        )
    }

    /** Returns the full SMS body (text + token marker) to send. */
    fun issueToken(base: String, apiKey: String, text: String): String =
        call(base, "POST", "/api/tokens", JSONObject().put("text", text), bearer = apiKey).getString("smsBody")
}
