package com.gios.lightchat

import com.gios.lightchat.beeper.BeeperLogin
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Beeper refuses the code step with "Your client is out of date" when these fields are missing
 * (light-reports #601 and on). Both steps carry them, the way bbctl v0.15.0 sends them.
 */
class BeeperLoginTest {

    @Test fun codeStepCarriesAppTypeAndExistingAccountsOnly() {
        val body = JSONObject(BeeperLogin.codeBody("req-1", "123456"))
        assertEquals("req-1", body.getString("request"))
        assertEquals("123456", body.getString("response"))
        assertEquals("bbctl", body.getString("appType"))
        assertTrue(body.getBoolean("onlyExistingAccounts"))
        assertEquals(4, body.length())
    }

    @Test fun emailStepCarriesThemToo() {
        val body = JSONObject(BeeperLogin.emailBody("req-1", "gio@example.com"))
        assertEquals("req-1", body.getString("request"))
        assertEquals("gio@example.com", body.getString("email"))
        assertEquals("bbctl", body.getString("appType"))
        assertTrue(body.getBoolean("onlyExistingAccounts"))
        assertEquals(4, body.length())
    }
}
