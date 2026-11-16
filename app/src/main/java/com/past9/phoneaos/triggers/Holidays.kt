package com.past9.phoneaos.triggers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.time.LocalDate

/** Public holidays from the free Nager.Date API, one cached list per year and country, so a routine can skip them. */
object Holidays {
    private const val DAY = 86_400_000L

    /** "US" or "US-CA": an ISO country code, optionally with a subdivision. */
    fun validRegion(region: String) = Regex("^[A-Z]{2}(-[A-Z0-9]{1,3})?$").matches(region)

    /** Is [date] a holiday in [region] per a Nager.Date list: one for the whole country, or one whose counties name the region. */
    fun matches(list: JSONArray, date: LocalDate, region: String): Boolean = (0 until list.length()).map { list.getJSONObject(it) }.any { h ->
        h.optString("date") == date.toString() && (h.optBoolean("global") ||
            h.optJSONArray("counties")?.let { c -> (0 until c.length()).any { c.optString(it).equals(region, true) } } == true)
    }

    /**
     * Is [date] a public holiday in [region]? Null when the year's list could not be had (the caller runs anyway).
     * A list is fetched once and kept; a failed fetch is retried at most once a day.
     */
    fun isHoliday(dir: File, date: LocalDate, region: String, now: Long, fetch: (String) -> String?): Boolean? {
        val country = region.substringBefore('-')
        val file = File(dir, "${date.year}-$country.json")
        if (!file.exists()) {
            val tried = File(dir, "${date.year}-$country.tried")
            if (now - tried.lastModified() < DAY) return null
            dir.mkdirs()
            tried.writeText("")
            tried.setLastModified(now)
            val body = fetch("https://date.nager.at/api/v3/PublicHolidays/${date.year}/$country") ?: return null
            if (runCatching { JSONArray(body) }.isFailure) return null
            file.writeText(body)
        }
        return runCatching { matches(JSONArray(file.readText()), date, region) }.getOrNull()
    }

    suspend fun isHoliday(context: Context, date: LocalDate, region: String): Boolean? = withContext(Dispatchers.IO) {
        isHoliday(File(context.filesDir, "holidays"), date, region, System.currentTimeMillis()) { url ->
            runCatching {
                com.past9.phoneaos.agent.sharedHttp.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.code == 200) r.body?.string() else null }
            }.getOrNull()
        }
    }
}
