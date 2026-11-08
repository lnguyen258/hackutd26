package com.veritext.app

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

object NotificationAccess {
    fun isEnabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun openSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Catches texts that never reach the SMS database (RCS chats in Google/Samsung Messages).
 * Privacy: reads ONLY the notification title (the sender). It never touches the message
 * text, and only increments a counter.
 */
class MessageListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val ctx = applicationContext
        if (sbn.packageName == ctx.packageName || sbn.packageName !in MESSAGING_APPS) return
        if (Prefs(ctx).role != "customer") return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // Unsaved senders show up as a bare number; saved contacts, groups and brands show a name.
        val number = NotifSender.phoneFromTitle(n.extras.getCharSequence(Notification.EXTRA_TITLE)) ?: return

        // Without Contacts access we can't tell strangers from friends, so don't guess.
        if (!Contacts.hasPermission(ctx) || Trust.isTrusted(ctx, number)) return

        // A verified business is not a scam; a revoked one is.
        val owner = Verifier.ownerOf(ctx, number)
        if (owner != null && owner.status == "approved") return

        Blocked.record(ctx, number, "notif|${sbn.key}|${sbn.postTime}", sbn.postTime)
    }

    companion object {
        val MESSAGING_APPS = setOf(
            "com.google.android.apps.messaging", // Google Messages (SMS + RCS)
            "com.samsung.android.messaging",     // Samsung Messages
            "com.android.mms",
        )
    }
}
