package com.flowbubble.app.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLinksTest {

    @Test
    fun `whatsapp links keep only the international digits`() {
        assertEquals("https://wa.me/919876543210", ChatLinks.whatsappLink("+91 98765 43210"))
    }

    @Test
    fun `a national number is not treated as an international one`() {
        assertFalse(ChatLinks.isInternational("098765 43210"))
        assertFalse(ChatLinks.isInternational("9876543210"))
        assertTrue(ChatLinks.isInternational("+919876543210"))
    }

    @Test
    fun `the longest app alias wins so business never resolves to plain whatsapp`() {
        assertEquals(ChatApp.WHATSAPP_BUSINESS, ChatApp.named("WhatsApp Business"))
        assertEquals(ChatApp.WHATSAPP, ChatApp.named("whatsapp"))
        assertEquals(ChatApp.WHATSAPP, ChatApp.named("wa"))
        assertEquals(ChatApp.MESSAGES, ChatApp.named("SMS"))
        assertNull(ChatApp.named("telegram"))
    }

    @Test
    fun `a chat screen counts as evidence only when the name is actually there`() {
        assertTrue(ChatLinks.mentions("Pranav Test\nonline", "Pranav Test"))
        assertTrue(ChatLinks.mentions("Pranav\nonline", "Pranav Test"))
        assertFalse(ChatLinks.mentions("Chats\nNew chat", "Pranav Test"))
        assertFalse(ChatLinks.mentions("P", "Pranav"))
        assertFalse(ChatLinks.mentions("", "Pranav"))
    }

    @Test
    fun `only the last four digits are shown next to a name`() {
        assertEquals("3210", ChatLinks.tail("+91 98765 43210"))
    }

    @Test
    fun `the confirmation names the app that will actually open`() {
        assertEquals(
            ChatApp.WHATSAPP,
            ChatLinks.draftApp(international = true, hasWhatsApp = true, hasWhatsAppBusiness = true, hasSms = true)
        )
        assertEquals(
            ChatApp.WHATSAPP_BUSINESS,
            ChatLinks.draftApp(international = true, hasWhatsApp = false, hasWhatsAppBusiness = true, hasSms = true)
        )
        assertEquals(
            ChatApp.MESSAGES,
            ChatLinks.draftApp(international = true, hasWhatsApp = false, hasWhatsAppBusiness = false, hasSms = true)
        )
        assertEquals(
            ChatApp.MESSAGES,
            ChatLinks.draftApp(international = false, hasWhatsApp = true, hasWhatsAppBusiness = true, hasSms = true)
        )
        assertNull(
            ChatLinks.draftApp(international = true, hasWhatsApp = false, hasWhatsAppBusiness = false, hasSms = false)
        )
    }
}
