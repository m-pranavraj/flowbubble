package com.flowbubble.app.voice

/**
 * Hindi and Hinglish requests are rewritten into phrases the English grammar already parses, so a
 * Hindi command takes exactly the same safety gates and verification path as an English one. The
 * rewrite is a fixed pattern table, not a translator: when nothing matches, the phrase stays
 * dictation instead of being guessed at.
 */
object HindiCommands {

    /**
     * English app names that Hindi spells phonetically. Transliteration turns यूट्यूब into
     * "yootyoob", which no installed label resembles, so the borrowed spelling is restored here.
     */
    private val APP_WORDS = mapOf(
        "\u092F\u0942\u091F\u094D\u092F\u0942\u092C" to "youtube",
        "\u0915\u094D\u0930\u094B\u092E" to "chrome",
        "\u0935\u094D\u0939\u093E\u091F\u094D\u0938\u090F\u092A" to "whatsapp",
        "\u0935\u094D\u0939\u093E\u091F\u094D\u0938\u0910\u092A" to "whatsapp",
        "\u0935\u093E\u091F\u094D\u0938\u0910\u092A" to "whatsapp",
        "\u0907\u0902\u0938\u094D\u091F\u093E\u0917\u094D\u0930\u093E\u092E" to "instagram",
        "\u092B\u0947\u0938\u092C\u0941\u0915" to "facebook",
        "\u091C\u0940\u092E\u0947\u0932" to "gmail",
        "\u092E\u0948\u092A\u094D\u0938" to "maps",
        "\u0915\u0948\u092E\u0930\u093E" to "camera",
        "\u092B\u094B\u0928" to "phone",
        "\u0938\u0947\u091F\u093F\u0902\u0917\u094D\u0938" to "settings",
        "\u0938\u094D\u092A\u0949\u091F\u093F\u092B\u093E\u0908" to "spotify",
        "\u091F\u0947\u0932\u0940\u0917\u094D\u0930\u093E\u092E" to "telegram",
        "\u0928\u0947\u091F\u092B\u094D\u0932\u093F\u0915\u094D\u0938" to "netflix",
        "\u0917\u0942\u0917\u0932" to "google",
        "\u0917\u0948\u0932\u0930\u0940" to "gallery",
        "\u092E\u094D\u092F\u0942\u091C\u093F\u0915" to "music",
        "\u091F\u093F\u0915\u091F\u0949\u0915" to "tiktok",
        "\u091F\u094D\u0935\u093F\u091F\u0930" to "twitter",
        "\u0938\u094D\u0928\u0948\u092A\u091A\u0948\u091F" to "snapchat",
        "\u091C\u093C\u0942\u092E" to "zoom",
        "\u092B\u094D\u0932\u0948\u0936\u0932\u093E\u0907\u091F" to "flashlight",
        "\u091F\u093E\u0907\u092E\u0930" to "timer",
        "\u0905\u0932\u093E\u0930\u094D\u092E" to "alarm",
        "\u0935\u0949\u0932\u094D\u092F\u0942\u092E" to "volume",
        "\u092C\u0948\u091F\u0930\u0940" to "battery",
        "\u0928\u094B\u091F\u093F\u092B\u093F\u0915\u0947\u0936\u0928" to "notifications"
    )

    fun containsDevanagari(text: String): Boolean = Devanagari.contains(text)

    /** Lowercase Latin with every Devanagari word either borrowed back or transliterated. */
    fun latinize(raw: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (!Devanagari.isDevanagari(char)) {
                out.append(char.lowercaseChar())
                index++
                continue
            }
            var end = index
            while (end < raw.length && Devanagari.isDevanagari(raw[end])) end++
            val run = raw.substring(index, end)
            val trailing = run.takeLastWhile { it == '\u0964' || it == '\u0965' }
            val word = run.dropLast(trailing.length)
            out.append(APP_WORDS[word] ?: Devanagari.word(word))
            out.append(".".repeat(trailing.length))
            index = end
        }
        return out.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Verb forms that only come from Hindi. Their presence is what marks a phrase for rewriting. */
    private val HINDI_VERBS = Regex(
        "\\b(?:kholo|khol|kholiye|kholna|chaaloo|chalu|khojo|khoj|dhoondo|dhundo|dhoondho|" +
            "padho|padh|padhiye|dabao|dabaao|bhejo|bhej|lagao|batao|badhao|milao|" +
            "maisej|sandes|chait|skrol|karo|jao|chalo|peechhe|peeche|vaapas|wapas)\\b"
    )

    /** Contact first, then the message verb, with the body kept exactly as it was spoken. */
    private val MESSAGE = Regex(
        "^(.+?)\\s+(?:\u0915\u094B|ko)\\s+" +
            "(?:\u092E\u0948\u0938\u0947\u091C|\u092E\u0947\u0938\u0947\u091C|\u0938\u0902\u0926\u0947\u0936|maisej|message|sandes)" +
            "\\s+(?:\u092D\u0947\u091C\u094B|\u092D\u0947\u091C\u093F\u090F|\u092D\u0947\u091C|bhejo|bhej|send\\s+karo|send\\s+do)" +
            "(?:\\s+(?:\u0915\u093F|ki|that)\\s+(.+))?$"
    )
    private val CHAT = Regex(
        "^(.+?)\\s+(?:\u0938\u0947|se|\u0915\u0940|ki|\u0915\u0947\\s+\u0938\u093E\u0925|ke\\s+saath)\\s+" +
            "(?:\u091A\u0948\u091F|chait|chat|\u092C\u093E\u0924|baat)\\s+" +
            "(?:\u0916\u094B\u0932\u094B|kholo|\u0915\u0930\u094B|karo|open|" +
            "\u0936\u0941\u0930\u0942\\s+\u0915\u0930\u094B|shuru\\s+karo)$"
    )
    private val TIMER = Regex(
        "^(?:(\\d+|[a-z]+)\\s+)?" +
            "(seconds?|secs?|sekand|minutes?|mins?|minat|minit|hours?|hrs?|ghanta|ghante)" +
            "\\s+(?:ka\\s+)?(?:timer|taimar|taaimar)\\s+(?:lagao|set\\s+karo|set)$"
    )
    private val TORCH = Regex(
        "^(?:torch|flashlight)\\s+" +
            "(?:(?:on|chalu|chaloo|chaaloo|chaalu)(?:\\s+(?:karo|kar\\s+do))?|" +
            "(?:off|band)(?:\\s+(?:karo|kar\\s+do))?)$"
    )
    private val VOLUME = Regex(
        "^(?:volume|aavaaz|aavaz|awaaz|sound)\\s+" +
            "(?:(?:badhao|barhao|up|zyada)(?:\\s+(?:karo|kar\\s+do))?|" +
            "(?:kam|down|ghatao)(?:\\s+(?:karo|kar\\s+do))?)$"
    )
    private val BATTERY = Regex("^(?:battery|baitaree|baitari)\\s+(?:batao|status|kitni\\s+hai)$")
    private val NOTIFICATIONS =
        Regex("^(?:notifications|notiphikeshan|soochna)\\s+(?:padho|parho|read\\s+karo|dikhao)$")
    private val BACK = Regex("^(?:back|wapas|vaapas|peechhe|peeche)(?:\\s+(?:jao|chalo|karo))?$")
    private val HOME = Regex("^(?:home|hom)(?:\\s+(?:jao|chalo|karo))?$")

    private val NUMBER_WORDS = mapOf(
        "ek" to 1, "do" to 2, "teen" to 3, "chaar" to 4, "paanch" to 5, "panch" to 5,
        "chhah" to 6, "chah" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10
    )

    private val OPEN_AFTER = Regex(
        "^(?:(?:please|kripya)\\s+)?(.+?)\\s+(?:ko\\s+)?(?:" +
            "open|kholo|khol|kholiye|khol\\s+do|kholna\\s+hai|chalu\\s+karo|chaaloo\\s+karo|" +
            "shuru\\s+karo|start\\s+karo|open\\s+karo)(?:\\s+please)?$"
    )

    private val OPEN_BEFORE = Regex(
        "^(?:kholo|khol|kholiye|open\\s+karo|chalu\\s+karo)\\s+(?:the\\s+)?(.+)$"
    )

    private const val SEARCH_VERB =
        "(?:\u0916\u094B\u091C\u094B|\u0916\u094B\u091C|\u0922\u0942\u0902\u0922\u094B|" +
            "\u0922\u0942\u0901\u0922\u094B|khojo|khoj|dhoondo|dhundo|dhoondho)"
    private const val DO_SUFFIX =
        "(?:\\s+(?:\u0915\u0930\u094B|karo|\u0915\u0930\\s+\u0926\u094B|kar\\s+do|\u0926\u094B|do))?"

    /** "chrome mein mausam khojo" — the query keeps its script, only the app is normalised. */
    private val SEARCH_IN_APP = Regex(
        "^(.+?)\\s+(?:\u092E\u0947\u0902|mein|men|me)\\s+(.+?)\\s+$SEARCH_VERB$DO_SUFFIX$"
    )
    private val SEARCH_HERE = Regex(
        "^(?:\u0907\u0938\\s+\u0910\u092A|\u0907\u0938\\s+\u0938\u094D\u0915\u094D\u0930\u0940\u0928|" +
            "\u092F\u0939\u093E\u0901|\u092F\u0939\u093E\u0902|is\\s+app|is\\s+screen|yahaan|yahin)" +
            "\\s*(?:\u092E\u0947\u0902|\u092A\u0930|mein|men|par|me)?\\s*(.+?)\\s+$SEARCH_VERB$DO_SUFFIX$"
    )
    private val SEARCH_BARE = Regex("^(.+?)\\s+$SEARCH_VERB$DO_SUFFIX$")

    private val READ_SCREEN = Regex(
        "^(?:(?:\u0907\u0938|\u092F\u0939|yah|is)\\s+)?(?:\u0938\u094D\u0915\u094D\u0930\u0940\u0928|screen|skreen)" +
            "\\s+(?:\u092A\u0922\u093C\u094B|\u092A\u0922\u093C\u093F\u090F|\u092A\u0922\u093C|padho|padhiye|padh)" +
            "(?:\\s+(?:\u0915\u0930\u094B|karo))?" +
            "|^(?:\u092A\u0922\u093C\u094B|\u092A\u0922\u093C|padho|padh)\\s+" +
            "(?:(?:\u0907\u0938|\u092F\u0939)\\s+)?(?:\u0938\u094D\u0915\u094D\u0930\u0940\u0928|screen|skreen)$"
    )

    private const val SCROLL_VERB = "(?:\u0938\u094D\u0915\u094D\u0930\u0949\u0932|scroll|skrol)"
    private val SCROLL_DOWN = Regex(
        "^(?:\u0928\u0940\u091A\u0947|neeche|niche|down)\\s+$SCROLL_VERB(?:\\s+(?:\u0915\u0930\u094B|karo))?$" +
            "|^$SCROLL_VERB\\s+(?:\u0921\u093E\u0909\u0928|down)(?:\\s+(?:\u0915\u0930\u094B|karo))?$"
    )
    private val SCROLL_UP = Regex(
        "^(?:\u090A\u092A\u0930|upar|oopar|up)\\s+$SCROLL_VERB(?:\\s+(?:\u0915\u0930\u094B|karo))?$" +
            "|^$SCROLL_VERB\\s+(?:\u0905\u092A|up)(?:\\s+(?:\u0915\u0930\u094B|karo))?$"
    )

    private const val TAP_VERB =
        "(?:\u091F\u0948\u092A|taip|tap|\u0915\u094D\u0932\u093F\u0915|click|\u0926\u092C\u093E\u0913|dabao|dabaao)"
    private val TAP_AFTER = Regex(
        "^(.+?)\\s+(?:\u092A\u0930|par)\\s+$TAP_VERB$DO_SUFFIX$"
    )
    private val TAP_BEFORE = Regex(
        "^$TAP_VERB\\s+(?:(?:\u0915\u0930\u094B|karo)\\s+)?(.+)$"
    )

    private val CALL = Regex(
        "^(.+?)\\s+(?:\u0915\u094B|ko)\\s+(?:\u0915\u0949\u0932|call|kol|\u092B\u094B\u0928|phone|" +
            "\u092E\u093F\u0932\u093E\u0913|milao)(?:\\s+(?:\u0915\u0930\u094B|karo|\u0932\u0917\u093E\u0913|lagao))?$"
    )

    fun rewrite(raw: String): String? {
        val trimmed = raw.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ',', '\u0964')
        if (trimmed.isBlank()) return null
        val hasDevanagari = containsDevanagari(trimmed)
        val latin = latinize(trimmed)
        if (!hasDevanagari && !HINDI_VERBS.containsMatchIn(latin)) return null

        MESSAGE.matchEntire(trimmed)?.let { match ->
            val contact = cleanEntity(latinize(match.groupValues[1]))
            val body = match.groupValues[2].trim()
            if (contact.isNotBlank()) {
                return if (body.isNotBlank()) "text $contact saying $body" else "chat with $contact"
            }
        }
        CHAT.matchEntire(trimmed)?.let { match ->
            val contact = cleanEntity(latinize(match.groupValues[1]))
            if (contact.isNotBlank()) return "chat with $contact"
        }
        TIMER.matchEntire(latin)?.let { match ->
            val count = timerCount(match.groupValues[1]) ?: return@let
            val unit = timerUnit(match.groupValues[2]) ?: return@let
            return "set a timer for $count $unit"
        }
        TORCH.matchEntire(latin)?.let { match ->
            val words = match.value.split(' ')
            val on = words.any { it in setOf("on", "chalu", "chaloo", "chaaloo", "chaalu") }
            return if (on) "turn on the flashlight" else "turn off the flashlight"
        }
        VOLUME.matchEntire(latin)?.let { match ->
            val words = match.value.split(' ')
            val up = words.any { it in setOf("badhao", "barhao", "up", "zyada") }
            return if (up) "volume up" else "volume down"
        }
        if (BATTERY.matches(latin)) return "battery status"
        if (NOTIFICATIONS.matches(latin)) return "read notifications"
        if (BACK.matches(latin)) return "back"
        if (HOME.matches(latin)) return "home"

        SEARCH_HERE.matchEntire(trimmed)?.let { match ->
            val query = cleanEntity(match.groupValues[1])
            if (query.isNotBlank()) return "search $query in this app"
        }
        SEARCH_IN_APP.matchEntire(trimmed)?.let { match ->
            val app = cleanEntity(latinize(match.groupValues[1]))
            val query = cleanEntity(match.groupValues[2])
            if (app.isNotBlank() && query.isNotBlank()) return "search $query in $app"
        }
        SEARCH_BARE.matchEntire(trimmed)?.let { match ->
            val query = cleanEntity(match.groupValues[1])
            if (query.isNotBlank()) return "search $query"
        }
        if (READ_SCREEN.matches(trimmed)) return "read this screen"
        if (SCROLL_DOWN.matches(trimmed)) return "scroll down"
        if (SCROLL_UP.matches(trimmed)) return "scroll up"
        TAP_AFTER.matchEntire(trimmed)?.let { match ->
            val label = cleanEntity(match.groupValues[1])
            if (label.isNotBlank()) return "tap $label"
        }
        TAP_BEFORE.matchEntire(trimmed)?.let { match ->
            val label = cleanEntity(match.groupValues[1])
            if (label.isNotBlank()) return "tap $label"
        }
        CALL.matchEntire(trimmed)?.let { match ->
            val contact = cleanEntity(latinize(match.groupValues[1]))
            if (contact.isNotBlank()) return "call $contact"
        }
        OPEN_AFTER.matchEntire(latin)?.let { match ->
            val entity = cleanEntity(match.groupValues[1])
            if (entity.isNotBlank()) return "open $entity"
        }
        OPEN_BEFORE.matchEntire(latin)?.let { match ->
            val entity = cleanEntity(match.groupValues[1])
            if (entity.isNotBlank()) return "open $entity"
        }
        return null
    }

    private fun timerCount(raw: String): Int? {
        val value = raw.trim()
        if (value.isEmpty()) return 1
        value.toIntOrNull()?.let { return it.takeIf { count -> count > 0 } }
        return NUMBER_WORDS[value]
    }

    private fun timerUnit(raw: String): String? = when {
        raw.startsWith("s") -> "seconds"
        raw.startsWith("h") || raw.startsWith("ghant") -> "hours"
        raw.startsWith("m") -> "minutes"
        else -> null
    }

    private fun cleanEntity(raw: String): String =
        raw.trim().removeSuffix(" ko").removePrefix("the ").trim().trimEnd('.', '!', '?', ',', '\u0964')
}
