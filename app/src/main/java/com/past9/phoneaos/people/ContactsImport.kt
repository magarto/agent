package com.past9.phoneaos.people

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Importing the phone's contacts (GitHub issue #13, made opt-in on purpose): the permission is in the manifest, and it
 * is asked for only at the moment the person taps Import on the People screen, never at app start. Only names are
 * read, nothing else, and only names that are not already on the list are added.
 */
object ContactsImport {
    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Every distinct contact name on the phone, sorted. Empty when the permission has not been given. */
    fun names(context: Context): List<String> {
        if (!allowed(context)) return emptyList()
        val out = LinkedHashSet<String>()
        runCatching {
            context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0)?.trim().orEmpty()
                    if (n.isNotEmpty()) out += n
                }
            }
        }
        return out.toList()
    }

    /**
     * The phone's contacts whose name contains [query] (accents and case ignored), each with what the address book
     * holds about them: phones, emails, birthday and other dates, address, work, relations, nickname and note.
     */
    fun details(context: Context, query: String, limit: Int = 40): List<String> {
        if (!allowed(context)) return emptyList()
        val byContact = LinkedHashMap<Long, Pair<String, MutableList<String>>>()
        runCatching {
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME_PRIMARY,
                ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3, ContactsContract.Data.DATA4),
                null, null, ContactsContract.Data.DISPLAY_NAME_PRIMARY + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)?.trim().orEmpty()
                    if (name.isEmpty() || !fold(name).contains(fold(query))) continue
                    val id = c.getLong(0)
                    if (id !in byContact && byContact.size >= limit) continue
                    val entry = byContact.getOrPut(id) { name to mutableListOf() }
                    detailLine(c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6))?.let { if (it !in entry.second) entry.second += it }
                }
            }
        }
        return byContact.values.map { (name, lines) -> (listOf(name) + lines.map { "  - $it" }).joinToString("\n") }
    }

    private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    internal fun detailLine(mime: String?, data1: String?, data2: String?, data3: String?, data4: String?): String? {
        val v = data1?.trim().orEmpty()
        if (v.isEmpty()) return null
        return when (mime) {
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> "phone: $v"
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> "email: $v"
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> when (data2?.toIntOrNull()) {
                ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY -> "birthday: $v"
                ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY -> "anniversary: $v"
                else -> "date (${data3?.takeIf { it.isNotBlank() } ?: "other"}): $v"
            }
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> "address: ${v.replace('\n', ' ')}"
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> "work: " + listOfNotNull(v, data4?.takeIf { it.isNotBlank() }).joinToString(", ")
            ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE -> "relation: $v"
            ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> "nickname: $v"
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> "note: ${v.replace('\n', ' ')}"
            else -> null
        }
    }

    /** Add the names not on the list yet. Returns how many were added. */
    suspend fun into(context: Context, repo: PeopleRepository): Int {
        val have = repo.people().map { it.name.trim().lowercase() }.toSet()
        return names(context).filter { it.lowercase() !in have }.take(500).count { name ->
            runCatching { repo.add(name); true }.getOrDefault(false)
        }
    }
}
