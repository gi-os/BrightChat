package com.gios.lightchat.beeper

import org.json.JSONObject

/**
 * The request bodies of Beeper's email-code login, kept out of [BeeperEngine] so a test can pin
 * them.
 *
 * From 2026-10-02 Beeper refused the code step from any client that sent only the request id and
 * the code: "Your client is out of date. Please download the latest version of Beeper"
 * (light-reports #601 and every sign-in after it). Beeper's own open-source bbctl added two fields
 * to both steps in v0.15.0 (July 2026): `appType` and `onlyExistingAccounts`. v0.14.0 sent exactly
 * what this app sent until now.
 *
 * This login is already bbctl's flow (the same public API token, then the same JWT login on
 * matrix.beeper.com), so it sends bbctl's `appType`. That is the one value known to pass. If
 * Beeper ever wants a different one, this is the only place to change.
 */
object BeeperLogin {

    const val APP_TYPE = "bbctl"

    /** Step 1: Beeper emails a code to [email] for the login [request]. */
    fun emailBody(request: String, email: String): String = JSONObject()
        .put("request", request)
        .put("email", email)
        .put("appType", APP_TYPE)
        // Sign in only. Without it, an address Beeper doesn't know would start a new account.
        .put("onlyExistingAccounts", true)
        .toString()

    /** Step 2: trades the emailed [code] for a login token. */
    fun codeBody(request: String, code: String): String = JSONObject()
        .put("request", request)
        .put("response", code)
        .put("appType", APP_TYPE)
        .put("onlyExistingAccounts", true)
        .toString()
}
