package com.majeur.psclient.model

import com.majeur.psclient.util.toId

enum class FriendPresence { ONLINE, IDLE, BUSY, OFFLINE }

enum class FriendAction(val command: String) {
    ADD("add"),
    ACCEPT("accept"),
    REJECT("reject"),
    UNDO_REQUEST("undorequest"),
    REMOVE("remove")
}

internal fun friendCommand(action: FriendAction, username: String): String? =
        username.toId().takeIf(String::isNotEmpty)?.let { "/friends ${action.command} $it" }

data class FriendInfo(
        val id: String,
        val name: String,
        val presence: FriendPresence,
        val status: String? = null,
        val lastSeen: String? = null)

data class FriendSettings(
        val loginNotifications: Boolean,
        val receiveRequests: Boolean,
        val publicList: Boolean,
        val shareLastSeen: Boolean,
        val shareBattles: Boolean,
        val friendsOnlyPms: Boolean,
        val friendsOnlyChallenges: Boolean)

enum class FriendsPage(val roomId: String) {
    ALL("view-friends-all"),
    RECEIVED("view-friends-received"),
    SENT("view-friends-sent"),
    SETTINGS("view-friends-settings");

    companion object {
        fun fromRoomId(roomId: String) = entries.firstOrNull { it.roomId == roomId }
    }
}

sealed class FriendsPageData {
    data class Friends(val entries: List<FriendInfo>) : FriendsPageData()
    data class Requests(val users: List<String>) : FriendsPageData()
    data class Settings(val value: FriendSettings) : FriendsPageData()
    data class Error(val message: String) : FriendsPageData()
}
