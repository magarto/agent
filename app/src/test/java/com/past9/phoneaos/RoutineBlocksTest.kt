package com.past9.phoneaos

import com.past9.phoneaos.triggers.RoutineBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Issue #53: shared text blocks, pasted into a routine prompt right before it runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoutineBlocksTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aBlockIsPastedIn() {
        assertEquals("Check mail. Archive newsletters.", RoutineBlocks.expand("Check mail. {{block:inbox_rules}}", mapOf("inbox_rules" to "Archive newsletters.")))
    }

    @Test fun aMissingBlockBecomesANoteNotAFailure() {
        assertEquals("Do it. (block gone not found)", RoutineBlocks.expand("Do it. {{block:gone}}", emptyMap()))
    }

    @Test fun severalBlocksAndRepeats() {
        val out = RoutineBlocks.expand("{{block:a}} / {{block: b }} / {{block:a}}", mapOf("a" to "A", "b" to "B"))
        assertEquals("A / B / A", out)
        assertEquals(listOf("a", "b"), RoutineBlocks.usedIn("{{block:a}} {{block: b }} {{block:a}}"))
    }

    @Test fun oneLevelOnlySoBlocksCannotLoop() {
        val blocks = mapOf("a" to "x {{block:b}}", "b" to "y {{block:a}}")
        assertEquals("x {{block:b}}", RoutineBlocks.expand("{{block:a}}", blocks))
    }

    @Test fun aPromptWithoutBlocksIsUntouched() {
        assertEquals("Plain {braces} stay.", RoutineBlocks.expand("Plain {braces} stay.", mapOf("a" to "A")))
        assertTrue(RoutineBlocks.usedIn("Plain").isEmpty())
    }

    @Test fun theStoreSavesReplacesAndDeletes() {
        val f = tmp.newFolder().resolve("blocks.json")
        val store = RoutineBlocks(f)
        assertTrue(store.all().isEmpty())
        store.save("rules", "one"); store.save("style", "short")
        store.save("rules", "two")
        assertEquals(mapOf("rules" to "two", "style" to "short"), RoutineBlocks(f).all())
        assertTrue(store.delete("rules")); assertFalse(store.delete("rules"))
        assertEquals(mapOf("style" to "short"), RoutineBlocks(f).all())
    }

    @Test fun aBlockInUseIsNotDeleted() = kotlinx.coroutines.runBlocking {
        val db = com.past9.phoneaos.data.AppDb.inMemory(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val store = RoutineBlocks(tmp.newFolder().resolve("blocks.json")).apply { save("rules", "one") }
        db.triggers().upsert(com.past9.phoneaos.data.TriggerRow(name = "Inbox", kind = "daily", spec = "07:00", prompt = "Sort mail. {{block:rules}}"))
        val ctx = quiet
        val del = com.past9.phoneaos.triggers.BlockDeleteTool(store, db.triggers())
        assertTrue(del.run(org.json.JSONObject().put("name", "rules"), ctx).startsWith("Not deleted"))
        assertTrue(com.past9.phoneaos.triggers.BlockListTool(store, db.triggers()).run(org.json.JSONObject(), ctx).contains("used by: #1 Inbox"))
        db.triggers().list().forEach { db.triggers().delete(it.id) }
        assertEquals("Block \"rules\" deleted.", del.run(org.json.JSONObject().put("name", "rules"), ctx))
    }
}
