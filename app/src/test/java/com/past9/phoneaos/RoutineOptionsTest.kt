package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.AppDb
import com.past9.phoneaos.tools.ScheduleCreateTool
import com.past9.phoneaos.tools.ScheduleListTool
import com.past9.phoneaos.tools.ScheduleUpdateTool
import com.past9.phoneaos.triggers.RoutineOptions
import com.past9.phoneaos.triggers.WakePlan
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoutineOptionsTest {
    private val app get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun j(s: String) = JSONObject(s)

    @Test fun aRoutineWithNoOptionsIsNotSilent() {
        val dir = RoutineOptions.dir(app)
        assertFalse(RoutineOptions.get(dir, 99).silent)
        RoutineOptions.set(dir, 99, RoutineOptions(silent = true))
        assertTrue(RoutineOptions.get(dir, 99).silent)
        RoutineOptions.remove(dir, 99)
        assertFalse(RoutineOptions.get(dir, 99).silent)
    }

    @Test fun theAgentSetsAndClearsSilent() = runBlocking {
        val dao = AppDb.inMemory(app).triggers()
        val dir = RoutineOptions.dir(app)
        val made = ScheduleCreateTool(dao, dir) {}.run(j("""{"name":"Parcel check","kind":"interval","spec":"60","prompt":"Check the parcel status.","silent":true}"""), quiet)
        val id = Regex("#(\\d+)").find(made)!!.groupValues[1].toLong()
        assertTrue(RoutineOptions.get(dir, id).silent)
        assertTrue(ScheduleListTool(dao, dir).run(j("{}"), quiet).contains("(silent)"))

        // Changing something else leaves it; passing false turns it off.
        ScheduleUpdateTool(dao, dir) {}.run(j("""{"id":$id,"name":"Parcel watch"}"""), quiet)
        assertTrue(RoutineOptions.get(dir, id).silent)
        ScheduleUpdateTool(dao, dir) {}.run(j("""{"id":$id,"silent":false}"""), quiet)
        assertFalse(RoutineOptions.get(dir, id).silent)
        assertFalse(ScheduleListTool(dao, dir).run(j("{}"), quiet).contains("(silent)"))
    }

    @Test fun theAgentSetsARunWindow() = runBlocking {
        val dao = AppDb.inMemory(app).triggers()
        val dir = RoutineOptions.dir(app)
        val create = ScheduleCreateTool(dao, dir) {}
        assertTrue(create.run(j("""{"name":"Desk check","kind":"interval","spec":"60","prompt":"x","from":"09:00"}"""), quiet).startsWith("Not created: give both"))
        assertTrue(dao.list().isEmpty())
        val made = create.run(j("""{"name":"Desk check","kind":"interval","spec":"60","prompt":"x","days":"MON,TUE","from":"09:00","to":"17:00"}"""), quiet)
        val id = Regex("#(\\d+)").find(made)!!.groupValues[1].toLong()
        assertTrue(ScheduleListTool(dao, dir).run(j("{}"), quiet).contains("[days MON,TUE; 09:00-17:00]"))

        val update = ScheduleUpdateTool(dao, dir) {}
        assertTrue(update.run(j("""{"id":$id,"skip_holidays":"somewhere"}"""), quiet).startsWith("Not changed"))
        update.run(j("""{"id":$id,"skip_holidays":"XX-AA","silent":true}"""), quiet)
        val o = RoutineOptions.get(dir, id)
        assertEquals(listOf(setOf(1, 2), "XX-AA", true), listOf(o.days, o.holidays, o.silent))
    }

    @Test fun theSilentNoteAsksForTheWordTheRunChecks() {
        assertTrue(RoutineOptions.SILENT_NOTE.contains(WakePlan.QUIET))
        assertTrue(WakePlan.isQuiet("QUIET."))
    }
}
