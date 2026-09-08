package com.majeur.psclient.service.observer

import com.majeur.psclient.model.FriendPresence
import com.majeur.psclient.model.FriendsPage
import com.majeur.psclient.model.FriendsPageData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendsParserTest {

    @Test fun parsesEmptyAndPopulatedFriendLists() {
        assertEquals(emptyList<Any>(),
                (parseFriendsPage(FriendsPage.ALL, "<div>No friends found.</div>") as FriendsPageData.Friends).entries)

        val html = """
            <h4>Online (1)</h4><div class="infobox"><username>Alice</username><br>Status: <i>Ready</i></div>
            <h4>Idle (1)</h4><div class="infobox"><username>Bob</username></div>
            <h4>Busy (1)</h4><div class="infobox"><username>Carol</username></div>
            <h4>Offline (1)</h4><div class="infobox"><username>Dave</username><br>Last seen: <time>yesterday</time></div>
        """.trimIndent()

        val friends = (parseFriendsPage(FriendsPage.ALL, html) as FriendsPageData.Friends).entries
        assertEquals(listOf(FriendPresence.ONLINE, FriendPresence.IDLE,
                FriendPresence.BUSY, FriendPresence.OFFLINE), friends.map { it.presence })
        assertEquals("Ready", friends.first().status)
        assertEquals("yesterday", friends.last().lastSeen)
    }

    @Test fun parsesIncomingAndOutgoingRequests() {
        val incoming = parseFriendsPage(FriendsPage.RECEIVED,
                """<button value="/friends accept alice">Accept</button>
                   <button value="/friends reject alice">Reject</button>""") as FriendsPageData.Requests
        val outgoing = parseFriendsPage(FriendsPage.SENT,
                """<button value="/friends undorequest bob">Undo</button>""") as FriendsPageData.Requests

        assertEquals(listOf("alice"), incoming.users)
        assertEquals(listOf("bob"), outgoing.users)
    }

    @Test fun parsesAllSevenSettings() {
        fun buttons(on: String, off: String, enabled: Boolean) =
                """<button class="${if (enabled) "disabled" else ""}" value="$on">On</button>
                   <button class="${if (enabled) "" else "disabled"}" value="$off">Off</button>"""
        val html = listOf(
                buttons("/friends viewnotifs", "/friends hidenotifs", true),
                buttons("/friends toggle on", "/friends toggle off", false),
                buttons("/friends listdisplay yes", "/friends listdisplay no", true),
                buttons("/friends showlogins", "/friends hidelogins", false),
                buttons("/friends sharebattles on", "/friends sharebattles off", true),
                buttons("/blockpms friends", "/unblockpms", true),
                buttons("/blockchallenges friends", "/unblockchallenges", false)
        ).joinToString("\n")

        val settings = (parseFriendsPage(FriendsPage.SETTINGS, html) as FriendsPageData.Settings).value
        assertEquals(listOf(true, false, true, false, true, true, false), listOf(
                settings.loginNotifications, settings.receiveRequests, settings.publicList,
                settings.shareLastSeen, settings.shareBattles, settings.friendsOnlyPms,
                settings.friendsOnlyChallenges))
    }

    @Test fun reportsServerErrorsAndIncompleteSettings() {
        val serverError = parseFriendsPage(FriendsPage.ALL,
                "<div class=message-error>Please log in before accessing Friends.</div>")
        val incomplete = parseFriendsPage(FriendsPage.SETTINGS,
                "<button value='/friends viewnotifs'>On</button>")

        assertTrue(serverError is FriendsPageData.Error)
        assertTrue(incomplete is FriendsPageData.Error)
        assertEquals(emptyList<Any>(), (parseFriendsPage(FriendsPage.ALL,
                "<h4>Online (1)</h4><div class=infobox>Status: <i>Missing user</i></div>")
                as FriendsPageData.Friends).entries)
    }
}
