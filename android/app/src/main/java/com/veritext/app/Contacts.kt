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
