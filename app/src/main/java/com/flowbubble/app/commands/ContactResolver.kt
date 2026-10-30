package com.flowbubble.app.commands

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.Locale

data class ContactMatch(val name: String, val number: String)

/**
 * One person, not one phone row: a contact with three saved numbers is still one choice on screen,
 * and each number is already in E.164 where Android could normalise it, because chat deep links
 * take international numbers only.
 */
data class ContactPerson(val id: Long, val name: String, val numbers: List<String>) {
    val primaryNumber: String? get() = numbers.firstOrNull()
}

data class EmailMatch(val name: String, val email: String)

object ContactResolver {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun find(context: Context, query: String, limit: Int = 5): List<ContactMatch> {
        if (!hasPermission(context)) return emptyList()
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val digits = q.filter { it.isDigit() }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
        }.getOrNull() ?: return emptyList()

        val seen = HashSet<String>()
        val matches = ArrayList<ContactMatch>()
        cursor.use { c ->
            val nameIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIndex < 0 || numberIndex < 0) return emptyList()
            while (c.moveToNext()) {
                val name = c.getString(nameIndex)?.trim().orEmpty()
                val number = c.getString(numberIndex)?.trim().orEmpty()
                if (name.isBlank() || number.isBlank()) continue
                val numberDigits = number.filter { it.isDigit() }
                val nameMatches = name.lowercase().contains(q.lowercase())
                val numberMatches = digits.length >= 3 && numberDigits.contains(digits)
                if (!nameMatches && !numberMatches) continue
                if (!seen.add("${name.lowercase()}|$numberDigits")) continue
                matches.add(ContactMatch(name, number))
            }
        }

        return rankMatches(matches, q) { it.name }.take(limit)
    }

    /**
     * Exact, then prefix, then contains, then everything else (number-only hits). The tiers are
     * kept disjoint on purpose: an exact name is also a prefix and a contains match, and counting
     * it once per tier made one saved contact look like two people.
     */
    fun <T> rankMatches(matches: List<T>, query: String, nameOf: (T) -> String): List<T> {
        val lower = query.lowercase()
        val exact = matches.filter { nameOf(it).lowercase() == lower }
        val prefix = matches.filter { nameOf(it).lowercase().startsWith(lower) && it !in exact }
        val contains = matches.filter {
            nameOf(it).lowercase().contains(lower) && it !in exact && it !in prefix
        }
        val other = matches.filter { it !in exact && it !in prefix && it !in contains }
        return exact + prefix + contains + other
    }

    /**
     * People whose name matches [query], aggregated by contact id with every saved number attached,
     * mobile first. People are read from the contacts table rather than the phone table so a
     * contact with no number is still found and can be reported honestly. Call off the main thread.
     */
    fun findPeople(context: Context, query: String, limit: Int = 5): List<ContactPerson> {
        if (!hasPermission(context)) return emptyList()
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val digits = q.filter { it.isDigit() }

        val names = LinkedHashMap<Long, String>()
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"),
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )
        }.getOrNull() ?: return emptyList()
        cursor.use { c ->
            val idIndex = c.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            if (idIndex < 0 || nameIndex < 0) return emptyList()
            while (c.moveToNext()) {
                val id = c.getLong(idIndex)
                val name = c.getString(nameIndex)?.trim().orEmpty()
                if (id > 0 && name.isNotBlank() && names.size < MAX_PEOPLE_LOOKUP) names[id] = name
            }
        }
        if (names.isEmpty()) return emptyList()

        val numbersById = HashMap<Long, MutableList<String>>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN (${names.keys.joinToString(",")})",
                null,
                null
            )
        }.getOrNull()?.use { c ->
            val idIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val numberIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            if (idIndex >= 0 && numberIndex >= 0) {
                while (c.moveToNext()) {
                    val id = c.getLong(idIndex)
                    val number = c.getString(numberIndex)?.trim().orEmpty()
                    if (id <= 0 || number.isBlank()) continue
                    val normalized = e164(context, number) ?: number
                    val known = numbersById.getOrPut(id) { ArrayList() }
                    if (known.none { ChatLinks.digits(it) == ChatLinks.digits(normalized) }) {
                        // Mobile first: it is the number that reaches the person's own phone.
                        val mobile = typeIndex >= 0 &&
                            c.getInt(typeIndex) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                        if (mobile) known.add(0, normalized) else known.add(normalized)
                    }
                }
            }
        }

        val people = names.map { (id, name) -> ContactPerson(id, name, numbersById[id].orEmpty()) }
        val lower = q.lowercase()
        val matching = people.filter { person ->
            val nameMatches = person.name.lowercase().contains(lower)
            val numberMatches = digits.length >= 3 &&
                person.numbers.any { ChatLinks.digits(it).contains(digits) }
            nameMatches || numberMatches
        }
        return rankMatches(matching, q) { it.name }.take(limit)
    }

    private const val MAX_PEOPLE_LOOKUP = 200

    /**
     * E.164 using the SIM's country, because a number saved as "09876543210" cannot be turned
     * into a chat deep link otherwise. Returns null when the number cannot be normalised.
     */
    fun e164(context: Context, number: String): String? {
        val cleaned = number.trim()
        if (cleaned.isBlank()) return null
        val region = region(context)
        val formatted = runCatching { PhoneNumberUtils.formatNumberToE164(cleaned, region) }.getOrNull()
        if (!formatted.isNullOrBlank()) return formatted
        val digits = ChatLinks.digits(cleaned)
        return cleaned.takeIf { it.startsWith("+") && digits.length in 8..15 }
    }

    private fun region(context: Context): String {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val fromSim = listOfNotNull(telephony?.networkCountryIso, telephony?.simCountryIso)
            .map { it.uppercase(Locale.US) }
            .firstOrNull { it.length == 2 }
        if (fromSim != null) return fromSim
        val fromLocale = Locale.getDefault().country
        return if (fromLocale.length == 2) fromLocale else "US"
    }

    fun findEmail(context: Context, query: String, limit: Int = 3): List<EmailMatch> {
        if (!hasPermission(context)) return emptyList()
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Email.ADDRESS
        )
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"),
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} ASC"
            )
        }.getOrNull() ?: return emptyList()

        val seen = HashSet<String>()
        val matches = ArrayList<EmailMatch>()
        cursor.use { c ->
            val nameIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
            val addressIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            if (nameIndex < 0 || addressIndex < 0) return emptyList()
            while (c.moveToNext()) {
                val name = c.getString(nameIndex)?.trim().orEmpty()
                val address = c.getString(addressIndex)?.trim().orEmpty()
                if (name.isBlank() || address.isBlank() || !address.contains('@')) continue
                if (!name.lowercase().contains(q.lowercase())) continue
                if (!seen.add(address.lowercase())) continue
                matches.add(EmailMatch(name, address))
            }
        }
        return rankMatches(matches, q) { it.name }.take(limit)
    }
}
