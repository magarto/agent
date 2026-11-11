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

    @Test fun theSilentNoteAsksForTheWordTheRunChecks() {
        assertTrue(RoutineOptions.SILENT_NOTE.contains(WakePlan.QUIET))
        assertTrue(WakePlan.isQuiet("QUIET."))
    }
}
