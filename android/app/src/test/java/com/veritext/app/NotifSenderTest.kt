package com.veritext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotifSenderTest {
    @Test fun unsavedSendersShowAsNumbers() {
        assertEquals("+1 205-394-6375", NotifSender.phoneFromTitle("+1 205-394-6375"))
        assertEquals("(205) 394-6375", NotifSender.phoneFromTitle("(205) 394-6375"))
        assertEquals("95730", NotifSender.phoneFromTitle("95730")) // short code
    }

    @Test fun bidiMarksFromMessagingAppsAreIgnored() {
        assertEquals("+1 205-394-6375", NotifSender.phoneFromTitle("‪+1 205-394-6375‬"))
    }

    @Test fun namesGroupsAndBrandsAreNotStrangerNumbers() {
        assertNull(NotifSender.phoneFromTitle("Mom"))
        assertNull(NotifSender.phoneFromTitle("Chase Bank"))
        assertNull(NotifSender.phoneFromTitle("Group 4 chat"))
        assertNull(NotifSender.phoneFromTitle("Tiffany 2"))
        assertNull(NotifSender.phoneFromTitle(null))
        assertNull(NotifSender.phoneFromTitle(""))
    }

    @Test fun tooShortOrTooLongDigitStringsAreRejected() {
        assertNull(NotifSender.phoneFromTitle("1234"))
        assertNull(NotifSender.phoneFromTitle("1234567890123456"))
    }

    @Test fun normalizeMatchesServerRule() {
        assertEquals("2053946375", Tokens.normalize("+1 (205) 394-6375"))
        assertEquals("tmobile", Tokens.normalize("TMobile"))
        assertEquals("95730", Tokens.normalize("95730"))
    }
}
