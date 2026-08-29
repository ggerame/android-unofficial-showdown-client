package com.majeur.psclient.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SensitiveLogRedactionTest {

    @Test
    fun redactsCredentialsAndPackedTeams() {
        assertEquals("<redacted authentication command>", redactSensitiveMessage("|/trn user,0,assertion"))
        assertEquals("<redacted team command>", redactSensitiveMessage("|/utm packed-team"))
        assertEquals("<redacted team command>", redactSensitiveMessage("|/teams save team-data"))
        assertEquals("|hello", redactSensitiveMessage("|hello"))
    }
}
