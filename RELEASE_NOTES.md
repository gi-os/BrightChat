## BrightChat v2.53: Beeper sign-in works again

**Every Beeper sign-in since October 2 stopped at the code with "Your client is out of date. Please download the latest version of Beeper."** The email with the code arrived as usual, but typing the code in failed every time. light-reports#601 was the first of about 50 reports like it.

The refusal comes from Beeper's login service, at the step that trades the emailed code for a login. It has nothing to do with the Matrix library, which the earlier triage had pointed to. Beeper changed what it expects at that step. Its own open-source command-line tool, bbctl, added two fields to both login steps in July: which app is signing in (`appType`), and a flag that says to sign in to an existing account only and never create one (`onlyExistingAccounts`). The bbctl release before that sent exactly what BrightChat sent. On October 2 Beeper began refusing requests without the new fields.

Both steps now send the two fields. BrightChat already follows bbctl's login flow, with the same public login token and the same final sign-in on Beeper's Matrix server, so it gives the same app name, `bbctl`. That is the one value known to pass today. The two request bodies now live in `beeper/BeeperLogin.kt`, and a test checks both of them.

Fixes [light-reports#601] -- couldn't sign in to Beeper: "Your client is out of date".
