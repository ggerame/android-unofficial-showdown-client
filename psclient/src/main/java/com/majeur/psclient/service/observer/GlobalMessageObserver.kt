package com.majeur.psclient.service.observer

import com.majeur.psclient.model.BattleRoomInfo
import com.majeur.psclient.model.ChatRoomInfo
import com.majeur.psclient.model.common.BattleFormat
import com.majeur.psclient.model.common.BattleFormatParser
import com.majeur.psclient.io.BattleFormatCache
import com.majeur.psclient.io.normalizeAvatarId
import com.majeur.psclient.service.ServerMessage
import com.majeur.psclient.service.ShowdownService
import com.majeur.psclient.util.Utils
import com.majeur.psclient.util.toId
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.util.*

internal data class UserDetails(
        val id: String,
        val name: String,
        val online: Boolean,
        val group: String,
        val avatarId: String?,
        val rooms: List<String>,
        val battles: List<String>,
        val friended: Boolean)

internal fun parseUserDetails(jsonObject: JSONObject): UserDetails? {
    val userId = jsonObject.optString("userid").ifEmpty { jsonObject.optString("id") }
    if (userId.isBlank()) return null
    val roomsObject = jsonObject.opt("rooms") as? JSONObject
    val chatRooms = mutableListOf<String>()
    val battles = mutableListOf<String>()
    roomsObject?.keys()?.forEach {
        if (it.startsWith("battle-") || it.drop(1).startsWith("battle-")) battles.add(it)
        else chatRooms.add(it)
    }
    return UserDetails(
            userId,
            jsonObject.optString("name"),
            roomsObject != null || jsonObject.has("status"),
            jsonObject.optString("group"),
            normalizeAvatarId(jsonObject.optString("avatar")),
            chatRooms,
            battles,
            jsonObject.optBoolean("friended", false))
}

class GlobalMessageObserver(service: ShowdownService)
    : AbsMessageObserver<GlobalMessageObserver.UiCallbacks>(service) {

    override var observedRoomId: String? = "lobby"
    override val interceptCommandBefore = setOf("init", "noinit")
    override val interceptCommandAfter = setOf("deinit")

    val myUsername get() = service.getSharedData<String>("myusername")?.drop(1)
    var isUserGuest: Boolean = true
        private set

    private var requestServerCountsOnly = false
    private var pendingPrivateMessageTo: String? = null
    private val privateMessages = mutableMapOf<String, MutableList<String>>()
    private val pendingFriendRequests = mutableSetOf<String>()
    private var pendingFriendRequestCount = 0

    override fun onUiCallbacksAttached() {
        // If we did not stored at least username, we will not have anything else
        val username = service.getSharedData<String>("myusername") ?: return

        onUserChanged(username.drop(1), isUserGuest,
                service.getSharedData<String>("avatar") ?: "000")
        onUpdateCounts(service.getSharedData("users") ?: -1,
                service.getSharedData("battles") ?: -1)

        onBattleFormatsChanged(service.getSharedData("formats") ?: emptyList())

        onSearchBattlesChanged(service.getSharedData("searching") ?: emptyList(),
                service.getSharedData("games") ?: emptyMap())

        onChallengesChange(service.getSharedData("challenge_to"),
                service.getSharedData("challenge_to_format"),
                service.getSharedData("challenge_from") ?: emptyMap())
        onFriendRequestsChanged(pendingFriendRequests, pendingFriendRequestCount)
    }

    public override fun onMessage(message: ServerMessage) {
        message.newArgsIteration()
        when (message.command) {
            "connected" -> onConnectedToServer()
            "challstr" -> processChallengeString(message)
            "updateuser" -> processUpdateUser(message)
            "queryresponse" -> processQueryResponse(message)
            "formats" -> processAvailableFormats(message)
            "popup" -> handlePopup(message)
            "error" -> onShowPopup(message.remainingArgsRaw)
            "updatesearch" -> handleUpdateSearch(message)
            "pm" -> handlePm(message)
            "updatechallenges" -> handleChallenges(message)
            "networkerror" -> onNetworkError()
            "init" -> onRoomInit(message.roomId, message.nextArg)
            "deinit" -> onRoomDeinit(message.roomId)
            "noinit" -> {
                if (message.hasNextArg && "nonexistent" == message.nextArg && message.hasNextArg)
                    onShowPopup(message.nextArg)
            }
            "nametaken" -> {
                message.nextArg // Skipping name
                onShowPopup(message.nextArg)
            }
            "usercount" -> {
            }
        }
    }

    private fun processChallengeString(msg: ServerMessage) {
        service.putSharedData("challenge", msg.remainingArgsRaw)
        service.tryCookieSignIn()
    }

    private fun processUpdateUser(msg: ServerMessage) {
        var username = msg.nextArg
        service.putSharedData("myusername", username)
        val userType = username.substring(0, 1)
        username = username.substring(1)
        val isGuest = "0" == msg.nextArg
        val avatar = normalizeAvatarId(msg.nextArg) ?: "000"
        service.putSharedData("avatar", avatar)
        isUserGuest = isGuest
        pendingFriendRequests.clear()
        pendingFriendRequestCount = 0
        onFriendRequestsChanged(pendingFriendRequests, pendingFriendRequestCount)
        if (isGuest) service.markCurrentUserAsGuest()
        onUserChanged(username, isGuest, avatar)

        // Update server counts (active battle and active users)
        requestServerCountsOnly = true
        service.sendGlobalCommand("cmd", "rooms")

        // onSearchBattlesChanged(new String[0], new String[0], new String[0]); TODO Wtf was this call ?
    }

    private fun processQueryResponse(msg: ServerMessage) {
        val query = msg.nextArg
        val queryResponse = msg.remainingArgsRaw
        if (service.consumeTeamCommand(query, queryResponse)) return
        when (query) {
            "rooms" -> processRoomsQueryResponse(queryResponse)
            "roomlist" -> processRoomListQueryResponse(queryResponse)
            "savereplay" -> processSaveReplayQueryResponse(queryResponse)
            "userdetails" -> processUserDetailsQueryResponse(queryResponse)
            else -> Timber.w("Command |queryresponse| not handled, type=$query")
        }
    }

    private fun processRoomsQueryResponse(response: String) {
        if (response == "null") return
        try {
            val jsonObject = JSONObject(response)
            val userCount = jsonObject.optInt("userCount", -1)
            service.putSharedData("users", userCount)
            val battleCount = jsonObject.optInt("battleCount", -1)
            service.putSharedData("battles", battleCount)
            onUpdateCounts(userCount, battleCount)
            if (requestServerCountsOnly) {
                requestServerCountsOnly = false
                return
            }
            var jsonArray = jsonObject.optJSONArray("official") ?: JSONArray()
            val officialRooms = mutableListOf<ChatRoomInfo>()
            for (i in 0 until jsonArray.length()) {
                val roomJson = jsonArray.optJSONObject(i) ?: continue
                officialRooms.add(
                        ChatRoomInfo(roomJson.optString("title"),
                                roomJson.optString("desc"),
                                roomJson.optInt("userCount", 0)))
            }
            jsonArray = jsonObject.optJSONArray("chat") ?: JSONArray()
            val chatRooms = mutableListOf<ChatRoomInfo>()
            for (i in 0 until jsonArray.length()) {
                val roomJson = jsonArray.optJSONObject(i) ?: continue
                chatRooms.add(
                        ChatRoomInfo(roomJson.optString("title"),
                                roomJson.optString("desc"),
                                roomJson.optInt("userCount", 0)))
            }
            onAvailableRoomsChanged(officialRooms, chatRooms)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    private fun processRoomListQueryResponse(response: String) =
            onAvailableBattleRoomsChanged(parseBattleRoomList(response))

    private fun processSaveReplayQueryResponse(response: String) {
        try {
            val jsonObject = JSONObject(response)
            val replayId = jsonObject.optString("id")
            // val battleLog = jsonObject.optString("log")
            onReplaySaved(replayId, "https://replay.pokemonshowdown.com/$replayId")
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    private fun processUserDetailsQueryResponse(response: String) {
        try {
            parseUserDetails(JSONObject(response))?.let {
                onUserDetails(it.id, it.name, it.online, it.group, it.avatarId,
                        it.rooms, it.battles, it.friended)
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    private fun processAvailableFormats(msg: ServerMessage) {
        val categories = BattleFormatParser.parse(msg.args)
        BattleFormatCache(service).store(categories)
        service.putSharedData("formats", categories)
        onBattleFormatsChanged(categories)
    }

    private fun handlePopup(msg: ServerMessage) {
        val message = msg.args.joinToString("\n")
        if (!service.consumeValidationPopup(message) && !service.consumeTeamCommandPopup(message)) onShowPopup(message)
    }

    private fun handleUpdateSearch(msg: ServerMessage) {
        val jsonObject = Utils.jsonObject(msg.remainingArgsRaw) ?: return

        val searching = mutableListOf<String>()
        val searchingJson = jsonObject.optJSONArray("searching")
        searchingJson?.let {
            for (i in 0 until searchingJson.length())
                searching.add(searchingJson.optString(i))
        }

        val games = mutableMapOf<String, String>()
        val gamesJson = jsonObject.optJSONObject("games")
        gamesJson?.let {
            gamesJson.keys().forEach { key -> games[key] = gamesJson.optString(key) }
        }
        service.putSharedData("searching", searching)
        service.putSharedData("games", games)
        onSearchBattlesChanged(searching, games)
    }

    private fun handlePm(msg: ServerMessage) {
        val rawFrom = msg.nextArgSafe ?: return
        val from = if (rawFrom == "~") "~" else rawFrom.drop(1)
        val rawTo = msg.nextArgSafe ?: return
        val isSystemMessage = rawTo == "~"
        val isServerMessage = rawFrom == "~"
        val to = rawTo.drop(1)
        val myUsername = service.getSharedData<String>("myusername")?.drop(1)
        var content = msg.remainingArgsRaw.takeIf(String::isNotEmpty) ?: return

        // Modern PS delivers battle challenges as PMs containing a "/challenge"
        // command instead of an |updatechallenges| message. Route these into the
        // challenge system so the accept/decline button is shown (and cleared).
        if (content.startsWith("/challenge")) {
            handlePmChallenge(from, to, myUsername, content)
            return
        }
        if (handleFriendPm(from, content, isSystemMessage || isServerMessage)) return
        // Other "/log" and "/nonotify" PMs are system notifications that only duplicate
        // challenge/battle state; don't show them as chat messages.
        if (content.startsWith("/log") || content.startsWith("/nonotify")) return

        val isError = content.startsWith("/error")
        val with = resolvePrivateMessagePeer(
                from, to, myUsername, pendingPrivateMessageTo, isSystemMessage, isError)
        val sentByMe = myUsername != null && from.toId() == myUsername.toId()
        if (sentByMe && (!isSystemMessage || with != null)) pendingPrivateMessageTo = null
        if (isError) {
            onPrivateMessageError(with, content.removePrefix("/error").trim())
            return
        }
        if (with == null) {
            Timber.w("Ignoring private message without a resolvable user")
            return
        }

        content = privateMessageDisplayText(content)
        val message = "$from: $content"
        val messages = privateMessages.getOrPut(with, { mutableListOf<String>() })
        messages.add(message)
        onNewPrivateMessage(with, message)
    }

    private fun handleFriendPm(from: String, content: String, isSystemMessage: Boolean): Boolean {
        when (val event = parseFriendPm(from, content, isSystemMessage)) {
            is FriendPmEvent.RequestCount -> {
                pendingFriendRequestCount = event.count
                onFriendRequestsChanged(pendingFriendRequests, pendingFriendRequestCount)
                return true
            }
            is FriendPmEvent.Incoming -> {
                setFriendRequest(event.user, true)
                if (event.showInChat) {
                    val message = "${event.user}: sent you a friend request."
                    privateMessages.getOrPut(event.user) { mutableListOf() }.add(message)
                    onNewPrivateMessage(event.user, message)
                }
                return true
            }
            is FriendPmEvent.Resolved -> {
                setFriendRequest(event.user, false)
                return true
            }
            FriendPmEvent.Ignore -> return true
            null -> return false
        }
    }

    private fun setFriendRequest(user: String, pending: Boolean) {
        if (user.isBlank()) return
        val matching = pendingFriendRequests.firstOrNull { it.toId() == user.toId() }
        if (matching != null) pendingFriendRequests.remove(matching)
        if (pending) pendingFriendRequests.add(user)
        pendingFriendRequestCount = if (pendingFriendRequestCount > pendingFriendRequests.size) {
            if (!pending && matching != null) pendingFriendRequestCount - 1 else pendingFriendRequestCount
        } else {
            pendingFriendRequests.size
        }
        onFriendRequestsChanged(pendingFriendRequests, pendingFriendRequestCount)
    }

    internal fun syncPendingFriendRequests(users: Collection<String>) {
        pendingFriendRequests.clear()
        pendingFriendRequests.addAll(users)
        pendingFriendRequestCount = users.size
        onFriendRequestsChanged(pendingFriendRequests, pendingFriendRequestCount)
    }

    fun hasPendingFriendRequest(user: String) = pendingFriendRequests.any { it.toId() == user.toId() }

    private fun handlePmChallenge(from: String, to: String, myUsername: String?, content: String) {
        // "/challenge <format>" opens a challenge; a bare "/challenge" clears it
        // (after it is accepted, cancelled or rejected). The server echoes the
        // same command to both players, so figure out who the other party is.
        val format = content.removePrefix("/challenge").trim().substringBefore('|').ifEmpty { null }
        val mine = myUsername != null && from.toId() == myUsername.toId()
        val otherUser = if (mine) to else from

        @Suppress("UNCHECKED_CAST")
        val fromMap = (service.getSharedData<Map<String, String>>("challenge_from")?.toMutableMap())
                ?: mutableMapOf()
        var challengeTo = service.getSharedData<String>("challenge_to")
        var toFormat = service.getSharedData<String>("challenge_to_format")

        if (format != null) {
            // A new challenge is being opened; the sender is always the challenger.
            if (mine) {
                challengeTo = otherUser
                toFormat = format
            } else {
                fromMap[otherUser.toId()] = format
            }
        } else {
            // Challenge resolved (accepted, cancelled or rejected). The closing PM can be
            // sent by *either* side (e.g. the other user accepting/rejecting our outgoing
            // challenge), so don't trust `mine` here: clear whichever slot actually
            // concerns this user instead of assuming the original challenger sent it.
            if (challengeTo != null && challengeTo.toId() == otherUser.toId()) {
                challengeTo = null
                toFormat = null
            }
            fromMap.remove(otherUser.toId())
        }

        service.putSharedData("challenge_to", challengeTo)
        service.putSharedData("challenge_to_format", toFormat)
        service.putSharedData("challenge_from", fromMap)
        onChallengesChange(challengeTo, toFormat, fromMap)
    }

    private fun handleChallenges(message: ServerMessage) {
        val rawJson: String = message.remainingArgsRaw
        var to: String? = null
        var format: String? = null
        val from = mutableMapOf<String, String>()

        val jsonObject = Utils.jsonObject(rawJson) ?: return
        val challengeTo = jsonObject.optJSONObject("challengeTo")
        challengeTo?.let {
            to = it.optString("to").ifEmpty { null }
            format = it.optString("format").ifEmpty { null }
        }
        val challengesFrom = jsonObject.optJSONObject("challengesFrom")
        challengesFrom?.let {
            it.keys().forEach { key ->
                from[key] = challengesFrom.optString(key)
            }
        }
        service.putSharedData("challenge_to", to)
        service.putSharedData("challenge_to_format", format)
        service.putSharedData("challenge_from", from)
        onChallengesChange(to, format, from)
    }

    fun getPrivateMessages(with: String): List<String>? {
        return privateMessages[with]
    }

    internal fun onPrivateMessageSent(to: String) {
        pendingPrivateMessageTo = to
    }

    fun onConnectedToServer() = uiCallbacks?.onConnectedToServer()
    fun onUserChanged(userName: String, isGuest: Boolean, avatarId: String) = uiCallbacks?.onUserChanged(userName, isGuest, avatarId)
    fun onUpdateCounts(userCount: Int, battleCount: Int) = uiCallbacks?.onUpdateCounts(userCount, battleCount)
    fun onBattleFormatsChanged(battleFormats: List<BattleFormat.Category>) = uiCallbacks?.onBattleFormatsChanged(battleFormats)
    fun onSearchBattlesChanged(searching: List<String>, games: Map<String, String>) = uiCallbacks?.onSearchBattlesChanged(searching, games)
    fun onReplaySaved(replayId: String, url: String) = uiCallbacks?.onReplaySaved(replayId, url)
    fun onUserDetails(id: String, name: String, online: Boolean, group: String,
                      avatarId: String?, rooms: List<String>, battles: List<String>, friended: Boolean) =
            uiCallbacks?.onUserDetails(id, name, online, group, avatarId, rooms, battles, friended)
    fun onShowPopup(message: String) = uiCallbacks?.onShowPopup(message)
    fun onAvailableRoomsChanged(officialRooms: List<ChatRoomInfo>, chatRooms: List<ChatRoomInfo>) = uiCallbacks?.onAvailableRoomsChanged(officialRooms, chatRooms)
    fun onAvailableBattleRoomsChanged(battleRooms: List<BattleRoomInfo>?) = uiCallbacks?.onAvailableBattleRoomsChanged(battleRooms)
    fun onNewPrivateMessage(with: String, message: String) = uiCallbacks?.onNewPrivateMessage(with, message)
    fun onPrivateMessageError(with: String?, message: String) = uiCallbacks?.onPrivateMessageError(with, message)
    fun onChallengesChange(to: String?, format: String?, from: Map<String, String>) = uiCallbacks?.onChallengesChange(to, format, from)
    fun onRoomInit(roomId: String, type: String) = uiCallbacks?.onRoomInit(roomId, type)
    fun onRoomDeinit(roomId: String) = uiCallbacks?.onRoomDeinit(roomId)
    fun onNetworkError() = uiCallbacks?.onNetworkError()
    fun onFriendRequestsChanged(users: Collection<String>, count: Int) =
            uiCallbacks?.onFriendRequestsChanged(users.toSet(), count)

    interface UiCallbacks : AbsMessageObserver.UiCallbacks {
        fun onConnectedToServer()
        fun onUserChanged(userName: String, isGuest: Boolean, avatarId: String)
        fun onUpdateCounts(userCount: Int, battleCount: Int)
        fun onBattleFormatsChanged(battleFormats: List<@JvmSuppressWildcards BattleFormat.Category>)
        fun onSearchBattlesChanged(searching: List<String>, games: Map<String, String>)
        fun onReplaySaved(replayId: String, url: String)
        fun onUserDetails(id: String, name: String, online: Boolean, group: String,
                          avatarId: String?, rooms: List<String>, battles: List<String>, friended: Boolean)
        fun onShowPopup(message: String)
        fun onAvailableRoomsChanged(officialRooms: List<ChatRoomInfo>, chatRooms: List<ChatRoomInfo>)
        fun onAvailableBattleRoomsChanged(battleRooms: List<BattleRoomInfo>?)
        fun onNewPrivateMessage(with: String, message: String)
        fun onPrivateMessageError(with: String?, message: String)
        fun onChallengesChange(to: String?, format: String?, from: Map<String, String>)
        fun onRoomInit(roomId: String, type: String)
        fun onRoomDeinit(roomId: String)
        fun onNetworkError()
        fun onFriendRequestsChanged(users: Set<String>, count: Int)
    }

}

internal sealed class FriendPmEvent {
    data class RequestCount(val count: Int) : FriendPmEvent()
    data class Incoming(val user: String, val showInChat: Boolean) : FriendPmEvent()
    data class Resolved(val user: String) : FriendPmEvent()
    data object Ignore : FriendPmEvent()
}

private val FRIEND_REQUEST_COUNT = Regex(
        "^/nonotify You have (\\d+) friend requests? pending!$", RegexOption.IGNORE_CASE)

internal fun parseFriendPm(from: String, content: String, isSystemMessage: Boolean): FriendPmEvent? {
    if (isSystemMessage) return FRIEND_REQUEST_COUNT.matchEntire(content)?.groupValues?.get(1)
            ?.toIntOrNull()?.let(FriendPmEvent::RequestCount)
            ?: content.takeIf {
                it.startsWith("/raw ") && it.contains("/j view-friends-received")
            }?.let { FriendPmEvent.Ignore }
    val fromId = from.toId()
    if (fromId.isEmpty()) return null
    return when {
        FRIEND_ACTION_CONFIRMATION.matches(content) -> FriendPmEvent.Ignore
        content.startsWith("/raw ") && content.contains("sent you a friend request!", true) ->
            FriendPmEvent.Incoming(from, showInChat = true)
        content.startsWith("/raw ") && content.contains("If this request is accepted", true) ->
            FriendPmEvent.Ignore
        content.startsWith("/uhtml sent-$fromId,") &&
                content.contains("/friends accept $fromId") &&
                content.contains("/friends reject $fromId") ->
            FriendPmEvent.Incoming(from, showInChat = false)
        content.startsWith("/uhtmlchange sent-") -> content
                .substringAfter("/uhtmlchange sent-").substringBefore(',').toId()
                .takeIf { it.isNotEmpty() && it == fromId }?.let(FriendPmEvent::Resolved)
        content.startsWith("/uhtml undo-") || content.startsWith("/uhtmlchange undo-") ->
            FriendPmEvent.Ignore
        else -> null
    }
}

private val FRIEND_ACTION_CONFIRMATION = Regex(
        "^/text You (?:(?:accepted|denied) a friend request from|removed your friend request to) " +
                "['\"][a-z0-9]+['\"]\\.$",
        RegexOption.IGNORE_CASE)

internal fun privateMessageDisplayText(content: String): String = when {
    content.startsWith("/text ") -> content.removePrefix("/text ")
    content.startsWith("/raw") || content.startsWith("/html") || content.startsWith("/uhtml") ->
        "Html messages not supported in pm."
    else -> content
}

internal fun resolvePrivateMessagePeer(
        from: String,
        to: String,
        myUsername: String?,
        pendingTarget: String?,
        isSystemMessage: Boolean = false,
        isError: Boolean = false
): String? {
    if (isSystemMessage) return pendingTarget.takeIf { isError }
    val sentByMe = myUsername != null && from.toId() == myUsername.toId()
    val peer = if (sentByMe) to.ifBlank { pendingTarget ?: "" } else from
    return peer.takeIf(String::isNotBlank)
}

internal fun parseBattleRoomList(response: String): List<BattleRoomInfo>? = runCatching {
    parseBattleRooms(JSONObject(response).getJSONObject("rooms"))
}.onFailure { Timber.e(it, "Malformed room list response") }.getOrNull()

internal fun parseBattleRooms(rooms: JSONObject): List<BattleRoomInfo> =
    rooms.keys().asSequence().map { roomId ->
        val room = rooms.getJSONObject(roomId)
        val players = room.optJSONArray("players")
        val p1 = players?.optString(0)?.takeIf(String::isNotBlank)
                ?: room.optString("p1").takeIf(String::isNotBlank) ?: "Player 1"
        val p2 = players?.optString(1)?.takeIf(String::isNotBlank)
                ?: room.optString("p2").takeIf(String::isNotBlank) ?: "Player 2"
        val rating = when (val value = room.opt("minElo")) {
            is Number -> value.toInt().takeIf { it > 0 }?.toString()
            is String -> value.trim().takeIf { it.isNotEmpty() && it != "0" }
            else -> null
        }
        BattleRoomInfo(roomId, p1, p2, rating)
    }.toList()
