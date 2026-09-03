package com.majeur.psclienttest

import com.majeur.psclient.R
import com.majeur.psclient.model.ReplayInfo
import com.majeur.psclient.service.buildReplaySearchUrl
import com.majeur.psclient.service.observer.parseBattleRoomList
import com.majeur.psclient.service.observer.parseBattleRooms
import com.majeur.psclient.ui.appendUniqueReplays
import com.majeur.psclient.ui.normalizeReplayUsernames
import com.majeur.psclient.ui.parseReplayList
import com.majeur.psclient.ui.replayPage
import com.majeur.psclient.ui.relativeTimeParts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SearchContractsTest {

    @Test fun roomListSupportsPlayersFallbackNumericEloAndTournaments() {
        val roomsJson = mock(JSONObject::class.java)
        val ratedJson = mock(JSONObject::class.java)
        val tourJson = mock(JSONObject::class.java)
        val players = mock(JSONArray::class.java)
        `when`(roomsJson.keys()).thenReturn(
                mutableListOf("battle-gen9ou-1", "battle-gen9uu-2").iterator())
        `when`(roomsJson.getJSONObject("battle-gen9ou-1")).thenReturn(ratedJson)
        `when`(roomsJson.getJSONObject("battle-gen9uu-2")).thenReturn(tourJson)
        `when`(ratedJson.optJSONArray("players")).thenReturn(players)
        `when`(players.optString(0)).thenReturn("Alice")
        `when`(players.optString(1)).thenReturn("Bob")
        `when`(ratedJson.opt("minElo")).thenReturn(1450)
        `when`(tourJson.optString("p1")).thenReturn("Carol")
        `when`(tourJson.optString("p2")).thenReturn("Dave")
        `when`(tourJson.opt("minElo")).thenReturn("tour")
        val rooms = parseBattleRooms(roomsJson)

        val rated = rooms.first { it.roomId.endsWith("-1") }
        assertEquals("Alice", rated.p1)
        assertEquals("Bob", rated.p2)
        assertEquals("1450", rated.rating)
        val tournament = rooms.first { it.roomId.endsWith("-2") }
        assertEquals("Carol", tournament.p1)
        assertEquals("Dave", tournament.p2)
        assertEquals("tour", tournament.rating)
    }

    @Test fun malformedRoomListIsAnError() {
        assertNull(parseBattleRoomList("not json"))
        assertNull(parseBattleRoomList("{}"))
    }

    @Test fun replayUsersAreNormalizedSeparatelyAndLimitedToTwo() {
        assertEquals(listOf("alice"), normalizeReplayUsernames(" Alice "))
        assertEquals(listOf("alice", "bob"), normalizeReplayUsernames("Alice, B.o.b"))
        assertNull(normalizeReplayUsernames("Alice, Bob, Carol"))
    }

    @Test fun replayUrlUsesOfficialParametersAndOneBasedPages() {
        val first = buildReplaySearchUrl(listOf("alice", "bob"), "gen9ou", 1)
        assertEquals("alice", first.queryParameter("user"))
        assertEquals("bob", first.queryParameter("user2"))
        assertEquals("gen9ou", first.queryParameter("format"))
        assertEquals("1", first.queryParameter("page"))
        assertEquals("2", buildReplaySearchUrl(emptyList(), "", 2).queryParameter("page"))
    }

    @Test fun replayParserPreservesFormattedLabelRatingAndPlayerFallback() {
        val array = mock(JSONArray::class.java)
        val rated = mock(JSONObject::class.java)
        val fallback = mock(JSONObject::class.java)
        val players = mock(JSONArray::class.java)
        `when`(array.length()).thenReturn(2)
        `when`(array.getJSONObject(0)).thenReturn(rated)
        `when`(array.getJSONObject(1)).thenReturn(fallback)
        `when`(rated.getString("id")).thenReturn("gen9ou-1")
        `when`(rated.optString("format")).thenReturn("[Gen 9] OU")
        `when`(rated.optJSONArray("players")).thenReturn(players)
        `when`(players.optString(0)).thenReturn("Alice")
        `when`(players.optString(1)).thenReturn("Bob")
        `when`(rated.optLong("uploadtime", 0L)).thenReturn(10L)
        `when`(rated.opt("rating")).thenReturn(1530)
        `when`(fallback.getString("id")).thenReturn("gen9uu-2")
        `when`(fallback.optString("format")).thenReturn("[Gen 9] UU")
        `when`(fallback.optString("p1")).thenReturn("Carol")
        `when`(fallback.optString("p2")).thenReturn("Dave")
        `when`(fallback.optLong("uploadtime", 0L)).thenReturn(20L)
        val entries = parseReplayList(array)!!

        assertEquals("[Gen 9] OU", entries[0].format)
        assertEquals("1530", entries[0].rating)
        assertEquals("Carol", entries[1].p1)
        assertEquals("Dave", entries[1].p2)
    }

    @Test fun replayPaginationUsesTheFiftyFirstItemOnlyAsSentinel() {
        val entries = (1..51).map { replay("replay-$it") }
        val page = replayPage(entries)
        assertEquals(50, page.items.size)
        assertTrue(page.hasMore)
        assertFalse(replayPage(entries.take(50)).hasMore)
    }

    @Test fun replayAppendDeduplicatesIds() {
        val merged = appendUniqueReplays(
                listOf(replay("a"), replay("b")),
                listOf(replay("b"), replay("c")))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }

    @Test fun replayAgeUsesMinutesHoursAndDaysAtBoundaries() {
        assertEquals(59L to R.plurals.minutes_ago, age(59L * MINUTE + 59L))
        assertEquals(1L to R.plurals.hours_ago, age(HOUR))
        assertEquals(23L to R.plurals.hours_ago, age(24L * HOUR - 1L))
        assertEquals(1L to R.plurals.days_ago, age(DAY))
        assertEquals(29L to R.plurals.days_ago, age(30L * DAY - 1L))
    }

    @Test fun replayAgeUsesSingularAndPluralMonthsAndYears() {
        assertEquals(1L to R.plurals.months_ago, age(30L * DAY))
        assertEquals(2L to R.plurals.months_ago, age(60L * DAY))
        assertEquals(11L to R.plurals.months_ago, age(364L * DAY))
        assertEquals(1L to R.plurals.years_ago, age(365L * DAY))
        assertEquals(2L to R.plurals.years_ago, age(730L * DAY))
    }

    @Test fun replayAgeHandlesFutureAndMissingTimestamps() {
        assertEquals(1L to R.plurals.minutes_ago, relativeTimeParts(NOW + DAY, NOW))
        assertNull(relativeTimeParts(0L, NOW))
    }

    private fun replay(id: String) = ReplayInfo(id, "[Gen 9] OU", "A", "B", 0L, null)

    private fun age(elapsed: Long) = relativeTimeParts(NOW - elapsed, NOW)

    companion object {
        private const val MINUTE = 60L
        private const val HOUR = 60L * MINUTE
        private const val DAY = 24L * HOUR
        private const val NOW = 2_000_000_000L
    }
}
