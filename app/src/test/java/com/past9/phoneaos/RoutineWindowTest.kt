package com.past9.phoneaos

import com.past9.phoneaos.triggers.Holidays
import com.past9.phoneaos.triggers.RoutineOptions
import com.past9.phoneaos.triggers.RoutineOptions.Skip
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RoutineWindowTest {
    private fun t(s: String) = LocalTime.parse(s)
    private fun j(s: String) = JSONObject(s)
    private val wed = LocalDate.of(2026, 11, 11) // a Wednesday

    @Test fun hoursIncludeBothEndsAndCanCrossMidnight() {
        assertTrue(RoutineOptions.inHours(t("08:00"), t("08:00"), t("20:00")))
        assertTrue(RoutineOptions.inHours(t("20:00:40"), t("08:00"), t("20:00")))
        assertFalse(RoutineOptions.inHours(t("20:01"), t("08:00"), t("20:00")))
        assertFalse(RoutineOptions.inHours(t("07:59"), t("08:00"), t("20:00")))
        // 22:00 to 06:00: the night, not the day.
        assertTrue(RoutineOptions.inHours(t("23:30"), t("22:00"), t("06:00")))
        assertTrue(RoutineOptions.inHours(t("00:00"), t("22:00"), t("06:00")))
        assertTrue(RoutineOptions.inHours(t("06:00"), t("22:00"), t("06:00")))
        assertFalse(RoutineOptions.inHours(t("12:00"), t("22:00"), t("06:00")))
    }

    @Test fun skipSaysWhy() {
        val o = RoutineOptions(days = setOf(1, 2, 3, 4, 5), from = t("08:00"), to = t("18:00"), skipDates = setOf(wed.plusDays(1)))
        assertNull(o.skip(wed.atTime(9, 0)))
        assertEquals(Skip.HOURS, o.skip(wed.atTime(19, 0)))
        assertEquals(Skip.DATE, o.skip(wed.plusDays(1).atTime(9, 0)))
        assertEquals(Skip.DAY, o.skip(wed.plusDays(3).atTime(9, 0))) // Saturday
        assertNull(RoutineOptions().skip(LocalDateTime.now()))
    }

    @Test fun daysParse() {
        assertEquals(setOf(1, 3, 5), RoutineOptions.parseDays("mon, WED,Friday"))
        assertEquals(emptySet<Int>(), RoutineOptions.parseDays(""))
        assertNull(RoutineOptions.parseDays("MON,XYZ"))
        assertNull(RoutineOptions.parseDays("M"))
    }

    @Test fun theAgentsParamsAreCheckedAndOnlyWhatItPassesChanges() {
        val base = RoutineOptions(silent = true, days = setOf(6, 7))
        val o = base.withWindow(j("""{"from":"8:00","to":"17:30","skip_holidays":"us-ca","skip_dates":["2026-12-24"]}""")).getOrThrow()
        assertEquals(RoutineOptions(true, setOf(6, 7), t("08:00"), t("17:30"), "US-CA", setOf(LocalDate.of(2026, 12, 24))), o)
        // Clearing.
        assertEquals(RoutineOptions(silent = true), o.withWindow(j("""{"days":"","from":"","to":"","skip_holidays":"","skip_dates":[]}""")).getOrThrow())

        assertTrue(base.withWindow(j("""{"from":"08:00"}""")).exceptionOrNull()!!.message!!.contains("both from and to"))
        assertTrue(base.withWindow(j("""{"from":"08:00","to":"08:00"}""")).isFailure)
        assertTrue(base.withWindow(j("""{"from":"8am","to":"17:00"}""")).exceptionOrNull()!!.message!!.contains("HH:mm"))
        assertTrue(base.withWindow(j("""{"days":"MON,FUNDAY"}""")).isFailure)
        assertTrue(base.withWindow(j("""{"skip_holidays":"Nowhere"}""")).isFailure)
        assertTrue(base.withWindow(j("""{"skip_dates":["24/12/2026"]}""")).exceptionOrNull()!!.message!!.contains("24/12/2026"))
    }

    @Test fun storedAndDescribed() {
        val o = RoutineOptions(false, setOf(5, 1), t("22:00"), t("06:00"), "US", setOf(LocalDate.of(2026, 12, 31)))
        assertEquals(o, RoutineOptions.fromJson(JSONObject(o.toJson().toString())))
        assertEquals(RoutineOptions(), RoutineOptions.fromJson(JSONObject("""{"silent":false}""")))
        assertEquals(" [days MON,FRI; 22:00-06:00; skips public holidays US; skips 2026-12-31]", o.describe())
        assertEquals("", RoutineOptions(silent = true).describe())
    }

    private val list = JSONArray("""[
        {"date":"2026-11-11","name":"National day","global":true,"counties":null},
        {"date":"2026-11-12","name":"Regional day","global":false,"counties":["XX-AA","XX-BB"]}]""")

    @Test fun holidaysMatchTheCountryAndTheSubdivision() {
        assertTrue(Holidays.matches(list, wed, "XX"))
        assertTrue(Holidays.matches(list, wed, "XX-CC"))
        assertTrue(Holidays.matches(list, wed.plusDays(1), "XX-AA"))
        assertFalse(Holidays.matches(list, wed.plusDays(1), "XX"))
        assertFalse(Holidays.matches(list, wed.plusDays(1), "XX-CC"))
        assertFalse(Holidays.matches(list, wed.plusDays(2), "XX-AA"))
        assertTrue(Holidays.validRegion("XX") && Holidays.validRegion("XX-AA") && Holidays.validRegion("XX-1"))
        assertFalse(Holidays.validRegion("xx") || Holidays.validRegion("XXX") || Holidays.validRegion("XX-"))
    }

    @Test fun holidaysAreFetchedOnceAndAFailureIsRetriedNextDay() {
        val dir = Files.createTempDirectory("holidays").toFile()
        val now = 1_800_000_000_000L
        val asked = mutableListOf<String>()
        // Offline: unknown, and not asked again the same day.
        assertNull(Holidays.isHoliday(dir, wed, "XX-AA", now) { asked += it; null })
        assertNull(Holidays.isHoliday(dir, wed, "XX-AA", now + 3_600_000) { asked += it; list.toString() })
        assertEquals(listOf("https://date.nager.at/api/v3/PublicHolidays/2026/XX"), asked)
        // A day later it asks again, keeps the list, and never asks for that year again.
        assertEquals(true, Holidays.isHoliday(dir, wed, "XX-AA", now + 86_400_000) { asked += it; list.toString() })
        assertEquals(false, Holidays.isHoliday(dir, wed.plusDays(2), "XX-AA", now + 2 * 86_400_000) { asked += it; null })
        assertEquals(2, asked.size)
        // Something that isn't a list is not kept.
        assertNull(Holidays.isHoliday(dir, LocalDate.of(2027, 1, 1), "XX", now) { "<html>" })
        dir.deleteRecursively()
    }
}
