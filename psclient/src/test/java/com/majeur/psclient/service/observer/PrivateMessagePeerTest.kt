package com.majeur.psclient.service.observer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivateMessagePeerTest {

    @Test
    fun resolvesNormalAndSystemPrivateMessagePeers() {
        assertEquals("Misty", resolvePrivateMessagePeer("Misty", "Ash", "Ash", null))
        assertEquals("Misty", resolvePrivateMessagePeer("Ash", "Misty", "ash", null))
        assertEquals("Misty", resolvePrivateMessagePeer("Ash", "", "Ash", "Misty"))
        assertEquals("MissingNo", resolvePrivateMessagePeer("Ash", "MissingNo", "Ash", "Misty"))
        assertNull(resolvePrivateMessagePeer("Ash", "", "Ash", null))
        assertNull(resolvePrivateMessagePeer("", "Ash", "Ash", "Misty"))
        assertEquals("Misty", resolvePrivateMessagePeer(
                "Ash", "", "Ash", "Misty", isSystemMessage = true, isError = true))
        assertNull(resolvePrivateMessagePeer(
                "Ash", "", "Ash", null, isSystemMessage = true, isError = true))
        assertNull(resolvePrivateMessagePeer(
                "Ash", "", "Ash", "Misty", isSystemMessage = true, isError = false))
    }
}
