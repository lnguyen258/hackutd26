package com.veritext.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Is this sender saved in the user's Contacts? Stays on the phone; nothing is uploaded. */
object Contacts {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Contact display name for [address], or null if not saved / no permission / not a phone number. */
    fun nameFor(ctx: Context, address: String): String? {
        if (!hasPermission(ctx)) return null
        if (address.any { it.isLetter() }) return null // alphanumeric sender IDs can't be saved contacts
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        return try {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }
}

/** Senders the user marked "Not a scam". Stored on the phone only. */
object Allowlist {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("veritext_allow", Context.MODE_PRIVATE)

    fun has(ctx: Context, address: String): Boolean =
        sp(ctx).getStringSet("numbers", emptySet())!!.contains(Tokens.normalize(address))

    fun add(ctx: Context, address: String) {
        val s = HashSet(sp(ctx).getStringSet("numbers", emptySet())!!)
        s.add(Tokens.normalize(address))
        sp(ctx).edit().putStringSet("numbers", s).apply()
    }
}

/** One rule for "trust this sender without checking": a saved contact or an allowed number. */
object Trust {
    fun isTrusted(ctx: Context, address: String): Boolean =
        Contacts.nameFor(ctx, address) != null || Allowlist.has(ctx, address)
}
