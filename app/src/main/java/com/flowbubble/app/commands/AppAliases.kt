package com.flowbubble.app.commands

/**
 * Nicknames people actually say for an app, plus the label the app installs under. Deliberate
 * short forms ("fb", "mobile match", "calc"), not guesses at mishearings — those belong to
 * PhraseResolver, which always asks first.
 */
object AppAliases {

    private data class Alias(
        val spoken: List<String>,
        val keywords: List<String>,
        val storePackage: String? = null
    )

    private val aliases = listOf(
        Alias(listOf("whatsapp", "wats app", "vp", "mobile match", "what app"), listOf("whatsapp"), "com.whatsapp"),
        Alias(listOf("instagram", "insta", "ig"), listOf("instagram"), "com.instagram.android"),
        Alias(listOf("facebook", "fb"), listOf("facebook"), "com.facebook.katana"),
        Alias(listOf("messenger", "fb messenger"), listOf("messenger"), "com.facebook.orca"),
        Alias(listOf("telegram", "tg"), listOf("telegram"), "org.telegram.messenger"),
        Alias(listOf("twitter", "x", "x app"), listOf("twitter"), "com.twitter.android"),
        Alias(listOf("snapchat", "snap"), listOf("snapchat"), "com.snapchat.android"),
        Alias(listOf("youtube", "yt"), listOf("youtube"), "com.google.android.youtube"),
        Alias(listOf("maps", "google maps", "navigation", "nav"), listOf("maps"), "com.google.android.apps.maps"),
        Alias(listOf("chrome", "browser"), listOf("chrome", "browser"), "com.android.chrome"),
        Alias(listOf("gmail", "mail", "email", "google mail"), listOf("gmail", "mail", "email"), "com.google.android.gm"),
        Alias(listOf("play store", "playstore", "play", "market"), listOf("play store", "playstore"), "com.android.vending"),
        Alias(listOf("keep", "notes", "keep notes"), listOf("keep", "notes", "notepad"), "com.google.android.keep"),
        Alias(listOf("spotify", "spotifi"), listOf("spotify"), "com.spotify.music"),
        Alias(listOf("netflix", "net flix"), listOf("netflix"), "com.netflix.mediaclient"),
        Alias(listOf("prime", "prime video", "amazon prime"), listOf("prime video"), null),
        Alias(listOf("amazon", "amazon shopping"), listOf("amazon"), null),
        Alias(listOf("flipkart"), listOf("flipkart"), "com.flipkart.android"),
        Alias(listOf("meesho"), listOf("meesho"), null),
        Alias(listOf("myntra"), listOf("myntra"), null),
        Alias(listOf("zomato", "zomato app"), listOf("zomato"), "com.application.zomato"),
        Alias(listOf("swiggy", "swiggi"), listOf("swiggy"), "in.swiggy.android"),
        Alias(listOf("blinkit", "quickr"), listOf("blinkit", "quickr"), null),
        Alias(listOf("zepto"), listOf("zepto"), null),
        Alias(listOf("paytm"), listOf("paytm"), "net.one97.paytm"),
        Alias(listOf("phonepe", "phone pe", "pe"), listOf("phonepe", "phone pe"), null),
        Alias(listOf("gpay", "google pay", "pay"), listOf("google pay", "gpay"), null),
        Alias(listOf("uber", "cab", "taxi"), listOf("uber", "ola", "cab"), null),
        Alias(listOf("irctc", "train", "railway"), listOf("irctc", "railway", "ixigo"), null),
        Alias(listOf("calculator", "calc"), listOf("calculator", "calc"), null),
        Alias(listOf("clock", "alarm", "timer"), listOf("clock"), null),
        Alias(listOf("calendar", "agenda"), listOf("calendar"), null),
        Alias(listOf("camera", "photo camera"), listOf("camera"), null),
        Alias(listOf("gallery", "photos", "albums"), listOf("gallery", "photos", "albums"), null),
        Alias(listOf("settings", "setting", "config"), listOf("settings"), null),
        Alias(listOf("phone", "dialer", "dial pad"), listOf("dialer", "phone"), null),
        Alias(listOf("messages", "sms", "texting"), listOf("messages", "messaging", "sms"), null),
        Alias(listOf("link", "linkedin"), listOf("linkedin"), "com.linkedin.android"),
        Alias(listOf("reddit"), listOf("reddit"), "com.reddit.frontpage"),
        Alias(listOf("discord"), listOf("discord"), null),
        Alias(listOf("sharechat"), listOf("sharechat"), null)
    )

    fun keywordsFor(spoken: String): List<String> {
        val key = normalize(spoken)
        if (key.isEmpty()) return emptyList()
        return aliases.firstOrNull { alias -> alias.spoken.any { normalize(it) == key } }?.keywords
            ?: emptyList()
    }

    /** Only present for apps whose package id is stable enough to link into Google Play. */
    fun storePackageFor(spoken: String): String? {
        val key = normalize(spoken)
        if (key.isEmpty()) return null
        return aliases.firstOrNull { alias -> alias.spoken.any { normalize(it) == key } }?.storePackage
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .split(Regex("\\s+"))
            .map { word -> word.filter { it in 'a'..'z' || it in '0'..'9' } }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
