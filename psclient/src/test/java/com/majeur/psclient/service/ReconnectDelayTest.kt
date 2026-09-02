package com.majeur.psclient.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectDelayTest {

    @Test
    fun growsExponentiallyThenCapsAtThirtySeconds() {
        assertEquals(
                listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
                (0..6).map(::reconnectDelayMillis))
    }
}
