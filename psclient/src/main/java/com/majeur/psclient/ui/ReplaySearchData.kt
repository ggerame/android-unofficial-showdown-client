package com.majeur.psclient.ui

import com.majeur.psclient.model.ReplayInfo
import com.majeur.psclient.util.toId
import org.json.JSONArray

internal data class ReplayPage(val items: List<ReplayInfo>, val hasMore: Boolean)

internal fun normalizeReplayUsernames(raw: String): List<String>? {
    val names = raw.split(',').map(String::trim).filter(String::isNotEmpty)
    if (names.size > 2) return null
    return names.map(String::toId).filter(String::isNotEmpty).distinct()
}

internal fun parseReplayList(array: JSONArray): List<ReplayInfo>? = runCatching {
    (0 until array.length()).map { index ->
        val replay = array.getJSONObject(index)
        val id = replay.getString("id").also { require(it.isNotBlank()) }
        val players = replay.optJSONArray("players")
        val p1 = players?.optString(0)?.takeIf(String::isNotBlank)
                ?: replay.optString("p1").takeIf(String::isNotBlank) ?: "Player 1"
        val p2 = players?.optString(1)?.takeIf(String::isNotBlank)
                ?: replay.optString("p2").takeIf(String::isNotBlank) ?: "Player 2"
        val rating = when (val value = replay.opt("rating")) {
            is Number -> value.toString()
            is String -> value.trim().takeIf(String::isNotEmpty)
            else -> null
        }
        ReplayInfo(
                id = id,
                format = replay.optString("format").takeIf(String::isNotBlank) ?: "Unknown",
                p1 = p1,
                p2 = p2,
                uploadTime = replay.optLong("uploadtime", 0L),
                rating = rating)
    }
}.getOrNull()

internal fun replayPage(entries: List<ReplayInfo>) =
        ReplayPage(entries.take(REPLAY_PAGE_SIZE), entries.size > REPLAY_PAGE_SIZE)

internal fun appendUniqueReplays(
        existing: List<ReplayInfo>,
        incoming: List<ReplayInfo>
): List<ReplayInfo> = (existing + incoming).distinctBy(ReplayInfo::id)

private const val REPLAY_PAGE_SIZE = 50
