package com.veritext.app

/** Pure helpers for reading who a messaging notification is from (never its text). */
object NotifSender {
    private fun isBidiMark(c: Char) = c.code in 0x200E..0x200F || c.code in 0x202A..0x202E || c.code in 0x2066..0x2069
    private const val SEPARATORS = "+()-.   "

    /**
     * Messaging apps title a notification with the contact's name when the sender is saved,
     * and with the bare number when it isn't. Returns the number for an unsaved sender, or
     * null for names, groups and brand names.
     */
    fun phoneFromTitle(title: CharSequence?): String? {
        val t = title?.toString()?.filterNot(::isBidiMark)?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (!t.all { it.isDigit() || it in SEPARATORS }) return null
        return if (t.count { it.isDigit() } in 5..15) t else null
    }
}
