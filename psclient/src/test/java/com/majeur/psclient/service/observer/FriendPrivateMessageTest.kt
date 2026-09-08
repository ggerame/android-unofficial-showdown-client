package com.majeur.psclient.service.observer

import com.majeur.psclient.service.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendPrivateMessageTest {

    @Test fun preservesPipesInPrivateMessageHtml() {
        val html = "/uhtml sent-misty,<button value=\"/friends accept misty\">Accept</button> || " +
                "<button value=\"/friends reject misty\">Deny</button>"
        val message = ServerMessage("lobby", "|pm|+Misty|@Ash|$html")
        message.newArgsIteration()
        assertEquals("+Misty", message.nextArg)
        assertEquals("@Ash", message.nextArg)
        assertEquals(html, message.remainingArgsRaw)
    }

    @Test fun recognizesIncomingRequestAndResolution() {
        val raw = parseFriendPm("Misty",
                "/raw <span class=\"username\">Misty</span> sent you a friend request!", false)
        val uhtml = parseFriendPm("Misty",
                "/uhtml sent-misty,<button value=\"/friends accept misty\">Accept</button> | " +
                        "<button value=\"/friends reject misty\">Deny</button>", false)
        val resolved = parseFriendPm("Misty", "/uhtmlchange sent-misty,", false)

        assertTrue(raw is FriendPmEvent.Incoming && raw.showInChat)
        assertTrue(uhtml is FriendPmEvent.Incoming && !uhtml.showInChat)
        assertEquals(FriendPmEvent.Resolved("misty"), resolved)
    }

    @Test fun handlesCountTextUnknownHtmlAndMalformedPayloads() {
        assertEquals(FriendPmEvent.RequestCount(2),
                parseFriendPm("~", "/nonotify You have 2 friend requests pending!", true))
        assertEquals("hello | world", privateMessageDisplayText("/text hello | world"))
        assertEquals("Html messages not supported in pm.",
                privateMessageDisplayText("/html <button>Unknown</button>"))
        assertEquals(FriendPmEvent.Ignore, parseFriendPm("~",
                "/raw <button value=\"/j view-friends-received\">View</button>", true))
        assertEquals(FriendPmEvent.Ignore,
                parseFriendPm("Ash", "/uhtml undo-misty,<button>Undo</button>", false))
        assertNull(parseFriendPm("Misty", "/uhtml sent-misty,<button>Unknown</button>", false))
        assertNull(parseFriendPm("Brock", "/uhtmlchange sent-misty,", false))
        assertNull(parseFriendPm("", "/uhtmlchange sent-,", false))
    }

    @Test fun hidesFriendActionConfirmationsAttributedToTemporaryGuests() {
        assertEquals(FriendPmEvent.Ignore, parseFriendPm("Guest 123456",
                "/text You removed your friend request to 'misty'.", false))
        assertEquals(FriendPmEvent.Ignore, parseFriendPm("Guest 123456",
                "/text You accepted a friend request from \"misty\".", false))
        assertEquals(FriendPmEvent.Ignore, parseFriendPm("Guest 123456",
                "/text You denied a friend request from 'misty'.", false))
        assertNull(parseFriendPm("Guest 123456", "/text A normal message", false))
    }
}
