package ai.zoya.assistant.tools

import android.annotation.SuppressLint
import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Contacts
 * --------
 * Name lookup for tool calls.
 *
 * Typing a name into the dialler (ACTION_DIAL with a "tel:" URI) does NOT search
 * contacts — a common and embarrassing assumption. So we query the Contacts
 * Provider ourselves and resolve the number before handing it to an Intent.
 *
 * Matching is conservative and ordered:
 *   1. exact, case-insensitive display name
 *   2. display name starts with the query
 *   3. the query equals the contact's first name
 *   4. the query is contained in the display name
 * Several matches are returned, not one, so Zoya can ask "which Riya?" instead
 * of guessing and calling the wrong person.
 */
object Contacts {

    private const val TAG = "Zoya/Contacts"

    data class Match(
        val name: String,
        val phones: List<String>,
        val matchedBy: String,
        val score: Int,
    )

    @SuppressLint("Range")
    suspend fun lookup(context: Context, query: String, limit: Int = 5): List<Match> =
        withContext(Dispatchers.IO) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return@withContext emptyList()

            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )

            // display name -> numbers, de-duped by digits so the same contact
            // saved twice with different formatting is not offered twice.
            val numbers = linkedMapOf<String, MutableList<String>>()

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                )
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var guard = 0
                while (cursor.moveToNext() && guard < 20_000) {
                    guard++
                    val name = cursor.getString(nameIdx)?.trim().orEmpty()
                    val number = cursor.getString(numberIdx)?.trim().orEmpty()
                    if (name.isEmpty() || number.isEmpty()) continue
                    val list = numbers.getOrPut(name) { mutableListOf() }
                    if (list.none { digitsOf(it) == digitsOf(number) }) list.add(number)
                }
            }

            numbers.map { (display, phones) -> score(display, q) to phones }
                .filter { it.first.score > 0 }
                .sortedWith(
                    compareByDescending<Pair<Match, List<String>>> { it.first.score }
                        .thenBy { it.first.name },
                )
                .take(limit)
                .map { (match, phones) -> match.copy(phones = phones) }
                .also { if (it.isEmpty()) Log.i(TAG, "no contact matched \"$q\"") }
        }

    private fun score(display: String, query: String): Match {
        val lower = display.lowercase()
        val first = lower.split(' ', '\n').firstOrNull().orEmpty()
        val (value, how) = when {
            lower == query -> 100 to "exact"
            lower.startsWith(query) -> 80 to "prefix"
            first == query -> 70 to "first_name"
            lower.contains(query) -> 50 to "contains"
            else -> 0 to "none"
        }
        return Match(name = display, phones = emptyList(), matchedBy = how, score = value)
    }

    private fun digitsOf(raw: String): String = raw.filter { it.isDigit() }.takeLast(10)
}
