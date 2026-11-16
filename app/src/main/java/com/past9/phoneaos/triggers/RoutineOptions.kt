package com.past9.phoneaos.triggers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Per-routine options kept beside the routine's row, one JSON file per routine id: a new Room column would mean a
 * schema change, and that wipes the whole database (fallbackToDestructiveMigration).
 *
 * @param silent only tell the user when something needs them: a run that finds nothing replies QUIET and posts nothing.
 * @param days the weekdays it may run on (1 = Monday .. 7 = Sunday); empty = every day.
 * @param from with [to], the local hours it may run in, both ends included; from after to crosses midnight.
 * @param holidays skip the public holidays of this region: a country code ("US") or country-subdivision ("US-CA"); blank = don't.
 * @param skipDates days it never runs on.
 */
data class RoutineOptions(
    val silent: Boolean = false,
    val days: Set<Int> = emptySet(),
    val from: LocalTime? = null,
    val to: LocalTime? = null,
    val holidays: String = "",
    val skipDates: Set<LocalDate> = emptySet(),
) {
    enum class Skip { DATE, DAY, HOURS }

    /** Why a run at [now] falls outside this routine's days and hours, or null when it may run. Holidays are checked apart (they need the network). */
    fun skip(now: LocalDateTime): Skip? = when {
        now.toLocalDate() in skipDates -> Skip.DATE
        days.isNotEmpty() && now.dayOfWeek.value !in days -> Skip.DAY
        from != null && to != null && !inHours(now.toLocalTime(), from, to) -> Skip.HOURS
        else -> null
    }

    /** These options with the run-window params the agent passed (only those it passed), or why they are wrong. */
    fun withWindow(input: JSONObject): Result<RoutineOptions> = runCatching {
        var o = this
        if (input.has("days")) o = o.copy(days = parseDays(input.optString("days"))
            ?: throw IllegalArgumentException("days must be weekday names separated by commas, like 'MON,TUE,WED,THU,FRI' (empty for every day)"))
        if (input.has("from")) o = o.copy(from = parseTime(input.optString("from"), "from"))
        if (input.has("to")) o = o.copy(to = parseTime(input.optString("to"), "to"))
        require((o.from == null) == (o.to == null)) { "give both from and to (HH:mm), or both empty to drop the hours" }
        require(o.from == null || o.from != o.to) { "from and to must differ" }
        if (input.has("skip_holidays")) o = o.copy(holidays = input.optString("skip_holidays").trim().uppercase().also {
            require(it.isEmpty() || Holidays.validRegion(it)) { "skip_holidays must be a country code like 'US', or country-subdivision like 'US-CA' (empty to stop skipping them)" }
        })
        if (input.has("skip_dates")) {
            val a = input.optJSONArray("skip_dates") ?: throw IllegalArgumentException("skip_dates must be a list of dates (yyyy-MM-dd)")
            o = o.copy(skipDates = (0 until a.length()).map { i ->
                runCatching { LocalDate.parse(a.optString(i).trim()) }.getOrElse { throw IllegalArgumentException("skip_dates: '${a.optString(i)}' is not a date (yyyy-MM-dd)") }
            }.toSet())
        }
        o
    }

    /** The window as the model reads it in routine_list, or "" when there is none. */
    fun describe(): String = listOfNotNull(
        days.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",", "days ") { DayOfWeek.of(it).name.take(3) },
        from?.let { "$it-$to" },
        holidays.takeIf { it.isNotBlank() }?.let { "skips public holidays $it" },
        skipDates.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",", "skips ")
    ).joinToString("; ").let { if (it.isEmpty()) "" else " [$it]" }

    fun toJson(): JSONObject = JSONObject().put("silent", silent).put("days", JSONArray(days.sorted()))
        .put("from", from?.toString() ?: "").put("to", to?.toString() ?: "").put("holidays", holidays)
        .put("skipDates", JSONArray(skipDates.sorted().map { it.toString() }))

    companion object {
        /** Told to a silent routine's run, after its prompt. */
        val SILENT_NOTE = "\n\n(Only tell the user when something needs them. If nothing does, reply with exactly ${WakePlan.QUIET} and nothing else: they will not be told.)"

        /** Is [t] within from..to (whole minutes, both ends included)? from after to wraps past midnight. */
        fun inHours(t: LocalTime, from: LocalTime, to: LocalTime): Boolean {
            val m = t.truncatedTo(ChronoUnit.MINUTES)
            return if (from <= to) m in from..to else m >= from || m <= to
        }

        /** "MON,WED,FRI" -> {1,3,5}; "" -> every day (empty); null if a name isn't a weekday. */
        fun parseDays(s: String): Set<Int>? = s.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
            .map { x -> DayOfWeek.values().firstOrNull { x.length >= 3 && it.name.startsWith(x) }?.value ?: return null }.toSet()

        private fun parseTime(s: String, name: String): LocalTime? = s.trim().takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrElse { throw IllegalArgumentException("$name must be HH:mm") }
        }

        fun fromJson(j: JSONObject) = RoutineOptions(
            silent = j.optBoolean("silent"),
            days = j.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() }.orEmpty(),
            from = j.optString("from").takeIf { it.isNotBlank() }?.let(LocalTime::parse),
            to = j.optString("to").takeIf { it.isNotBlank() }?.let(LocalTime::parse),
            holidays = j.optString("holidays"),
            skipDates = j.optJSONArray("skipDates")?.let { a -> (0 until a.length()).map { LocalDate.parse(a.optString(it)) }.toSet() }.orEmpty(),
        )

        fun dir(context: Context) = File(context.filesDir, "routine_options")
        fun get(dir: File, id: Long): RoutineOptions =
            runCatching { fromJson(JSONObject(File(dir, "$id.json").readText())) }.getOrDefault(RoutineOptions())
        fun set(dir: File, id: Long, o: RoutineOptions) {
            dir.mkdirs()
            File(dir, "$id.json").writeText(o.toJson().toString())
        }
        fun remove(dir: File, id: Long) { File(dir, "$id.json").delete() }
    }
}
