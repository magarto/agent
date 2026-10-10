package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.tools.AppCatalog
import com.past9.phoneaos.tools.ComposioConnect
import com.past9.phoneaos.agent.AgentRuntime
import com.past9.phoneaos.data.AppDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Apps a Composio Connect account has beyond the popular list (its own custom toolkits too) are learned from replies and kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposioOwnAppsTest {
    // Shapes of real Composio Connect replies (ids shortened).
    private val search = """{"successful":true,"data":{"results":[{"index":1,"primary_tool_slugs":["CUSTOM_NOTAS_X_LIST_NOTES","CUSTOM_NOTAS_X_GET_TASK"],"related_tool_slugs":["DISCORD_LIST_MY_CONNECTIONS"],"toolkits":["custom_notas"]}],
        "toolkit_connection_statuses":[{"toolkit":"custom_notas","description":"custom_notas toolkit","has_active_connection":true,"accounts":[]},
        {"toolkit":"discord","description":"Chat for communities","has_active_connection":false}]},"error":null}
No exact fit? Any HTTP or SSE endpoint can be connected as a custom MCP."""
    private val list = """{"successful":true,"data":{"message":"All connections are active","results":{"linear":{"toolkit":"linear","status":"active",
        "accounts":[{"id":"ca_1","status":"active","is_default":true}]},"trello":{"toolkit":"trello","status":"not_connected","accounts":[]}}},"error":null}"""

    @Test fun activeAppsComeFromSearchesAndLists() {
        assertEquals(mapOf("custom_notas" to "custom_notas toolkit"), ComposioConnect.activeApps(search))
        assertEquals(mapOf("linear" to ""), ComposioConnect.activeApps(list))
        assertTrue(ComposioConnect.activeApps("Error: timeout").isEmpty())
        // Tools go under the app they belong to; one whose app isn't in that result is left out.
        assertEquals(mapOf("custom_notas" to listOf("CUSTOM_NOTAS_X_LIST_NOTES", "CUSTOM_NOTAS_X_GET_TASK")), ComposioConnect.appTools(search))
    }

    @Test fun learnedAppsAreKeptAndForgottenWithTheAccount() {
        val s = SettingsStore(ApplicationProvider.getApplicationContext())
        s.setComposioKey("ck_one")
        s.rememberComposioApps(ComposioConnect.activeApps(search))
        s.rememberComposioApps(mapOf("custom_notas" to ""))   // a later list without a description keeps the one we had
        s.rememberComposioApps(ComposioConnect.activeApps(list))
        assertEquals(mapOf("custom_notas" to "custom_notas toolkit", "linear" to ""), s.composioApps())

        val shown = AppCatalog.known(s.composioApps()).associateBy { it.slug }
        assertEquals("Notas", shown.getValue("custom_notas").name)
        assertEquals("", shown.getValue("custom_notas").description)
        assertFalse("gmail" in AppCatalog.known(mapOf("gmail" to "")).map { it.slug })

        s.setComposioKey("ck_one")
        assertEquals(2, s.composioApps().size)
        s.setComposioKey("ck_two")
        assertTrue(s.composioApps().isEmpty())
    }

    @Test fun theAgentIsToldWhichAppsTheUserHas() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val s = SettingsStore(app); s.setComposioKey("ck_one")
        s.rememberComposioApps(mapOf("custom_notas" to "custom_notas toolkit", "gmail" to "", "custom_crm_magar" to ""))
        s.rememberComposioAppTools(ComposioConnect.appTools(search))
        val rt = AgentRuntime(app, AppDb.inMemory(app), s, CoroutineScope(SupervisorJob() + Dispatchers.IO), FakePhone(), { null })
        val sys = rt.systemPrompt("las tareas vencidas")
        assertTrue(sys.contains("connected apps (name and Composio toolkit slug): Crm Magar (custom_crm_magar), Notas (custom_notas), Gmail (gmail)."))
        assertTrue(sys.contains("  - Notas (custom_notas): CUSTOM_NOTAS_X_LIST_NOTES, CUSTOM_NOTAS_X_GET_TASK\n"))
        assertTrue(sys.contains("  - Crm Magar (custom_crm_magar): find its tools with COMPOSIO_SEARCH_TOOLS\n"))
        assertFalse(sys.contains("custom_notes"))
    }
}
