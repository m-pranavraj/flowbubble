package com.flowbubble.app.commands

/**
 * Android has no queryable "open a chat with this person" API, so the only deterministic routes
 * are the deep-link grammar each chat app publishes plus the address-the-number SMS intent.
 * Keeping that grammar as plain strings makes every link unit testable.
 */
enum class ChatApp(val label: String, val packageName: String?, private val aliases: List<String>) {
    WHATSAPP("WhatsApp", "com.whatsapp", listOf("whatsapp", "wa", "whats app")),
    WHATSAPP_BUSINESS(
        "WhatsApp Business",
        "com.whatsapp.w4b",
        listOf("whatsapp business", "wa business", "business whatsapp")
    ),
    MESSAGES("Messages", null, listOf("messages", "sms", "text message", "messaging"));

    companion object {
        /** Longest alias first, so "whatsapp business" never resolves to plain WhatsApp. */
        private val byAlias: List<Pair<String, ChatApp>> =
            entries.flatMap { app -> app.aliases.map { it to app } }
                .sortedByDescending { it.first.length }

        fun named(raw: String?): ChatApp? {
            val phrase = raw?.trim()?.lowercase()?.replace(Regex("\\s+"), " ") ?: return null
            return byAlias.firstOrNull { it.first == phrase }?.second
        }

        /** The words speech recognition can produce for a chat app, for the parser grammar. */
        const val SPOKEN_WORDS = "whatsapp(?:\\s+business)?|wa|messages|sms|text\\s+message|messaging"
    }
}

object ChatLinks {

    fun digits(number: String): String = number.filter(Char::isDigit)

    /**
     * wa.me needs a full international number without "+" or a national trunk zero; with either
     * the link silently opens no chat or the wrong one. Callers must pass E.164.
     */
    fun whatsappLink(e164: String): String = "https://wa.me/${digits(e164)}"

    fun smsLink(number: String): String = "smsto:${number.trim()}"

    /**
     * Which app a message draft will open in, in preference order. The confirmation panel names
     * this same app, so it can never promise WhatsApp and then open something else.
     */
    fun draftApp(
        international: Boolean,
        hasWhatsApp: Boolean,
        hasWhatsAppBusiness: Boolean,
        hasSms: Boolean
    ): ChatApp? = when {
        international && hasWhatsApp -> ChatApp.WHATSAPP
        international && hasWhatsAppBusiness -> ChatApp.WHATSAPP_BUSINESS
        hasSms -> ChatApp.MESSAGES
        else -> null
    }

    /** A deep link is only dependable when the number can be dialled internationally. */
    fun isInternational(number: String): Boolean {
        val trimmed = number.trim()
        return trimmed.startsWith("+") && digits(trimmed).length in 8..15
    }

    /** Last four digits are enough to tell two same-named contacts apart on screen. */
    fun tail(number: String): String = digits(number).takeLast(4)

    /**
     * Whether a chat screen visibly names the person. The whole name is tried first, then its
     * longest word, because headers often show only the first name. One- and two-letter tokens are
     * ignored so an initial does not count as evidence.
     */
    fun mentions(screenText: String, name: String): Boolean {
        val haystack = screenText.lowercase()
        if (haystack.isBlank()) return false
        val full = name.trim().lowercase()
        if (full.length >= 3 && haystack.contains(full)) return true
        val token = name.split(' ', '\t')
            .map { it.trim() }
            .filter { it.length >= 3 }
            .maxByOrNull { it.length }
            ?.lowercase()
        return token != null && haystack.contains(token)
    }
}
