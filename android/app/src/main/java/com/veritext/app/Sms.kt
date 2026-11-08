package com.veritext.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class Sms(val id: Long, val address: String, val body: String, val date: Long)

object SmsRepo {
    fun hasReadPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Newest first. Works without being the default SMS app (READ_SMS is enough). */
    fun readInbox(ctx: Context, limit: Int = 300): List<Sms> {
        if (!hasReadPermission(ctx)) return emptyList()
        val out = ArrayList<Sms>()
        val cols = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, cols, null, null, "date DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext() && out.size < limit) {
                val addr = c.getString(iAddr) ?: continue
                out += Sms(c.getLong(iId), addr, c.getString(iBody).orEmpty(), c.getLong(iDate))
            }
        }
        return out
    }
}

object SmsSender {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun send(ctx: Context, to: String, body: String) {
        val sm: SmsManager =
            if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sm.divideMessage(body)
        if (parts.size > 1) sm.sendMultipartTextMessage(to, null, parts, null, null)
        else sm.sendTextMessage(to, null, body, null, null)
    }
}

object Notifier {
    private const val CHANNEL = "verified_messages"

    fun show(ctx: Context, from: String, text: String, r: VerifyResult) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Verified messages", NotificationManager.IMPORTANCE_HIGH),
        )

        val owner = r.orgName ?: Verifier.ownerOf(ctx, from)?.name
        val title = when (r.decision) {
            Decision.VERIFIED -> "✓ ${r.orgName} · Verified"
            Decision.SUSPICIOUS -> "⚠ Not verified — claims to be ${owner ?: from}"
            Decision.UNKNOWN -> from
        }
        val shown = if (r.decision == Decision.VERIFIED) Tokens.strip(text) else text

        val open = PendingIntent.getActivity(
            ctx, from.hashCode(),
            Intent(ctx, MainActivity::class.java)
                .putExtra("address", from)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(shown)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shown))
            .setColor(if (r.decision == Decision.SUSPICIOUS) 0xFFB42318.toInt() else 0xFF0A66C2.toInt())
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), n)
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (Prefs(ctx).role != "customer") return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val from = parts.firstOrNull()?.originatingAddress ?: return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Make sure we know about businesses approved moments ago.
                Verifier.refreshDirectory(ctx)
                val r = Verifier.verify(ctx, from, body)
                // Saved contacts come through untouched: no scam alert for them.
                val saved = Trust.isTrusted(ctx, from)
                // Live counter +1: flagged scams, plus strangers (not a saved contact, not a verified business).
                val caught = r != null && r.decision != Decision.VERIFIED && !saved &&
                    (r.decision == Decision.SUSPICIOUS || Contacts.hasPermission(ctx))
                if (caught) Blocked.record(ctx, from, body)
                if (r != null && r.decision != Decision.UNKNOWN && !(saved && r.decision == Decision.SUSPICIOUS)) Notifier.show(ctx, from, body, r)
                Bus.tick.tryEmit(Unit)
            } finally {
                pending.finish()
            }
        }
    }
}
