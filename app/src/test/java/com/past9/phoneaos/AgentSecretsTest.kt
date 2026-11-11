package com.past9.phoneaos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.SettingsStore
import com.past9.phoneaos.tools.AgentSecrets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentSecretsTest {
    private val values = mapOf("portal_login" to "hunter2-xyz", "api_key" to "sk-test-0001")

    @Test fun placeholdersAreFilledWithTheirValues() {
        assertEquals("user hunter2-xyz and sk-test-0001", AgentSecrets.fill("user {{secret:portal_login}} and {{secret:api_key}}", values))
        assertEquals("no secrets here", AgentSecrets.fill("no secrets here", values))
        // Written loosely, the name still finds the stored one.
        assertEquals("hunter2-xyz", AgentSecrets.fill("{{secret:Portal Login}}", values))
    }

    @Test fun anUnknownNameFailsAndListsTheNamesThereAre() {
        try { AgentSecrets.fill("{{secret:bank_pin}}", values); fail("an unknown secret must fail") } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("bank_pin"))
            assertTrue(e.message!!, e.message!!.contains("api_key, portal_login"))
        }
    }

    @Test fun aToolInputIsFilledOnACopy() {
        val input = JSONObject().put("password", "{{secret:portal_login}}").put("count", 3)
        val filled = AgentSecrets.fill(input, values)
        assertEquals("hunter2-xyz", filled.getString("password"))
        assertEquals(3, filled.getInt("count"))
        assertEquals("{{secret:portal_login}}", input.getString("password"))
    }

    @Test fun valuesInOutputAreRedactedEvenInsideLongerText() {
        val out = AgentSecrets.redact("Exit 0\ntoken=sk-test-0001;user=me\nprefixhunter2-xyzsuffix", values)
        assertEquals("Exit 0\ntoken={{secret:api_key}};user=me\nprefix{{secret:portal_login}}suffix", out)
        assertFalse(out.contains("hunter2"))
        // A value inside another value: the longer one is replaced first, so nothing of it shows.
        val nested = mapOf("short" to "abcd", "long" to "xxabcdxx")
        assertEquals("{{secret:long}} {{secret:short}}", AgentSecrets.redact("xxabcdxx abcd", nested))
    }

    @Test fun namesAreCleaned() {
        assertEquals("school_portal", AgentSecrets.cleanName(" School portal "))
        assertEquals(null, AgentSecrets.cleanName("bad/name"))
        assertEquals("SECRET_API_KEY", AgentSecrets.envName("api_key"))
    }

    @Test fun theStoreKeepsNamesAndNotesApartFromValues() {
        val s = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
        s.setAgentSecret("portal_login", "hunter2-xyz", "Sign-in for the work portal")
        s.setAgentSecret("api_key", "sk-test-0001", "")
        assertEquals(mapOf("portal_login" to "Sign-in for the work portal", "api_key" to ""), s.agentSecrets())
        assertEquals(values, s.agentSecretValues())
        assertFalse("the plain settings must not hold a value", ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("settings", Context.MODE_PRIVATE).all.values.any { it.toString().contains("hunter2") })
        s.removeAgentSecret("portal_login")
        assertEquals(mapOf("api_key" to ""), s.agentSecrets())
        assertEquals(mapOf("api_key" to "sk-test-0001"), s.agentSecretValues())
    }
}
