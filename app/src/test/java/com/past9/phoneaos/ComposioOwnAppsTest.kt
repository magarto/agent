package com.past9.phoneaos

import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.tools.AppCatalog
import com.past9.phoneaos.tools.ComposioConnect
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
    private val search = """{"successful":true,"data":{"results":[{"index":1,"primary_tool_slugs":["CUSTOM_NOTAS_X_LIST_NOTES"],"toolkits":["custom_notas"]}],
        "toolkit_connection_statuses":[{"toolkit":"custom_notas","description":"custom_notas toolkit","has_active_connection":true,"accounts":[]},
        {"toolkit":"discord","description":"Chat for communities","has_active_connection":false}]},"error":null}"""
    private val list = """{"successful":true,"data":{"message":"All connections are active","results":{"linear":{"toolkit":"linear","status":"active",
        "accounts":[{"id":"ca_1","status":"active","is_default":true}]},"trello":{"toolkit":"trello","status":"not_connected","accounts":[]}}},"error":null}"""

    @Test fun activeAppsComeFromSearchesAndLists() {
        assertEquals(mapOf("custom_notas" to "custom_notas toolkit"), ComposioConnect.activeApps(search))
        assertEquals(mapOf("linear" to ""), ComposioConnect.activeApps(list))
        assertTrue(ComposioConnect.activeApps("Error: timeout").isEmpty())
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
}
