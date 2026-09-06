package com.majeur.psclienttest

import com.majeur.psclient.io.normalizeAvatarId
import com.majeur.psclient.service.observer.parseUserDetails
import com.majeur.psclient.ui.consumeUserDetailsResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AvatarContractsTest {

    @Test fun numericAvatarsArePaddedWithoutCorruptingCustomNames() {
        assertEquals("007", normalizeAvatarId("7"))
        assertEquals("123", normalizeAvatarId("123"))
        assertEquals("staff-avatar.png", normalizeAvatarId("staff-avatar.png"))
        assertEquals("#Staff Avatar", normalizeAvatarId("#Staff Avatar"))
        assertNull(normalizeAvatarId(""))
        assertNull(normalizeAvatarId(null))
    }

    @Test fun userDetailsIncludesAvatarAndSeparatesRoomsFromBattles() {
        val json = mock(JSONObject::class.java)
        val rooms = mock(JSONObject::class.java)
        `when`(json.optString("userid")).thenReturn("alice")
        `when`(json.optString("name")).thenReturn("Alice")
        `when`(json.optString("group")).thenReturn(" ")
        `when`(json.optString("avatar")).thenReturn("7")
        `when`(json.opt("rooms")).thenReturn(rooms)
        `when`(rooms.keys()).thenReturn(
                mutableListOf("lobby", "battle-gen9ou-1").iterator())

        val details = parseUserDetails(json)!!

        assertEquals("007", details.avatarId)
        assertTrue(details.online)
        assertEquals(listOf("lobby"), details.rooms)
        assertEquals(listOf("battle-gen9ou-1"), details.battles)
    }

    @Test fun offlineUserWithoutAvatarUsesFallback() {
        val json = mock(JSONObject::class.java)
        `when`(json.optString("userid")).thenReturn("alice")
        `when`(json.optString("name")).thenReturn("alice")
        `when`(json.optString("group")).thenReturn("")
        `when`(json.optString("avatar")).thenReturn("")
        `when`(json.opt("rooms")).thenReturn(false)

        val details = parseUserDetails(json)!!

        assertFalse(details.online)
        assertNull(details.avatarId)
    }

    @Test fun automaticLookupsStaySilentAndInteractiveLookupsRemainVisible() {
        val interactive = mutableSetOf("bob")
        val automatic = mutableSetOf("alice", "bob")

        assertFalse(consumeUserDetailsResponse("alice", interactive, automatic))
        assertTrue(consumeUserDetailsResponse("bob", interactive, automatic))
        assertFalse(consumeUserDetailsResponse("bob", interactive, automatic))
        assertTrue(consumeUserDetailsResponse("carol", interactive, automatic))
    }
}
