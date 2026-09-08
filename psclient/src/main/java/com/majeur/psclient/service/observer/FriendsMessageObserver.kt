package com.majeur.psclient.service.observer

import com.majeur.psclient.model.FriendInfo
import com.majeur.psclient.model.FriendPresence
import com.majeur.psclient.model.FriendSettings
import com.majeur.psclient.model.FriendsPage
import com.majeur.psclient.model.FriendsPageData
import com.majeur.psclient.service.ServerMessage
import com.majeur.psclient.service.ShowdownService
import com.majeur.psclient.util.toId
import org.ccil.cowan.tagsoup.HTMLSchema
import org.ccil.cowan.tagsoup.Parser
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader

class FriendsMessageObserver(service: ShowdownService)
    : AbsMessageObserver<FriendsMessageObserver.UiCallbacks>(service) {

    override val interceptCommandBefore = setOf(
            "init", "pagehtml", "deinit", "noinit", "networkerror", "pm", "updateuser")
    private val cache = mutableMapOf<FriendsPage, FriendsPageData>()

    override fun onUiCallbacksAttached() {
        cache.forEach { (page, data) -> uiCallbacks?.onFriendsPage(page, data) }
    }

    override fun onMessage(message: ServerMessage) {
        if (message.command == "updateuser") {
            cache.clear()
            return
        }
        if (message.command == "networkerror") {
            uiCallbacks?.onFriendsNetworkError()
            return
        }
        if (message.command == "pm") {
            handlePresenceMessage(message)
            return
        }
        val page = FriendsPage.fromRoomId(message.roomId) ?: return
        message.newArgsIteration()
        if (message.command == "noinit") {
            if (message.hasNextArg) message.nextArg
            val error = FriendsPageData.Error(message.remainingArgsRaw.ifBlank {
                "The friends page is not available."
            })
            cache[page] = error
            uiCallbacks?.onFriendsPage(page, error)
            return
        }
        if (message.command != "pagehtml") return
        val data = parseFriendsPage(page, message.remainingArgsRaw)
        cache[page] = data
        if (page == FriendsPage.RECEIVED && data is FriendsPageData.Requests) {
            service.globalMessageObserver.syncPendingFriendRequests(data.users)
        }
        uiCallbacks?.onFriendsPage(page, data)
    }

    private fun handlePresenceMessage(message: ServerMessage) {
        message.newArgsIteration()
        if (!message.hasNextArg || message.nextArg != "~" || !message.hasNextArg) return
        message.nextArg // recipient
        val content = message.remainingArgsRaw
        if (!content.startsWith("/nonotify Your friend ")) return
        val username = USERNAME_TAG.find(content)?.groupValues?.get(1)?.trim().orEmpty()
        if (username.isEmpty()) return
        val current = (cache[FriendsPage.ALL] as? FriendsPageData.Friends)?.entries ?: return
        val updated = current.map {
            if (it.id == username.toId()) it.copy(presence = FriendPresence.ONLINE) else it
        }
        val data = FriendsPageData.Friends(updated)
        cache[FriendsPage.ALL] = data
        uiCallbacks?.onFriendsPage(FriendsPage.ALL, data)
    }

    interface UiCallbacks : AbsMessageObserver.UiCallbacks {
        fun onFriendsPage(page: FriendsPage, data: FriendsPageData)
        fun onFriendsNetworkError()
    }

    companion object {
        private val USERNAME_TAG = Regex("<username[^>]*>([^<]+)</username>", RegexOption.IGNORE_CASE)
    }
}

internal fun parseFriendsPage(page: FriendsPage, html: String): FriendsPageData = runCatching {
    val parsed = FriendHtmlHandler.parse(html)
    val error = parsed.error.ifBlank {
        parsed.text.takeIf { it.contains("Please log in before accessing", ignoreCase = true) }.orEmpty()
    }
    if (error.isNotBlank()) return@runCatching FriendsPageData.Error(error.trim())
    when (page) {
        FriendsPage.ALL -> FriendsPageData.Friends(parsed.boxes.mapNotNull { box ->
            val presence = when (box.heading.substringBefore(" (").trim().lowercase()) {
                "online" -> FriendPresence.ONLINE
                "idle" -> FriendPresence.IDLE
                "busy" -> FriendPresence.BUSY
                "offline" -> FriendPresence.OFFLINE
                else -> return@mapNotNull null
            }
            val name = box.username
                    ?: box.italics.firstOrNull().takeIf { presence == FriendPresence.OFFLINE }
                    ?: return@mapNotNull null
            FriendInfo(
                    name.toId(),
                    name,
                    presence,
                    box.italics.lastOrNull().takeIf { box.text.contains("Status:") },
                    box.time)
        })
        FriendsPage.RECEIVED -> FriendsPageData.Requests(parsed.buttons.mapNotNull {
            it.command.targetAfter("/friends accept ")
        }.distinct())
        FriendsPage.SENT -> FriendsPageData.Requests(parsed.buttons.mapNotNull {
            it.command.targetAfter("/friends undorequest ")
        }.distinct())
        FriendsPage.SETTINGS -> FriendsPageData.Settings(FriendSettings(
                parsed.selected("/friends viewnotifs", "/friends hidenotifs"),
                parsed.selected("/friends toggle on", "/friends toggle off"),
                parsed.selected("/friends listdisplay yes", "/friends listdisplay no"),
                parsed.selected("/friends showlogins", "/friends hidelogins"),
                parsed.selected("/friends sharebattles on", "/friends sharebattles off"),
                parsed.selected("/blockpms friends", "/unblockpms"),
                parsed.selected("/blockchallenges friends", "/unblockchallenges")))
    }
}.getOrElse { FriendsPageData.Error("The friends response could not be read. Please refresh.") }

private fun String.targetAfter(prefix: String) = takeIf { startsWith(prefix) }
        ?.removePrefix(prefix)?.substringBefore('\n')?.trim()?.takeIf(String::isNotEmpty)

private data class ParsedButton(val command: String, val disabled: Boolean)
private data class ParsedBox(
        val heading: String,
        val text: String,
        val username: String?,
        val italics: List<String>,
        val time: String?)

private data class ParsedFriendHtml(
        val text: String,
        val error: String,
        val boxes: List<ParsedBox>,
        val buttons: List<ParsedButton>) {

    fun selected(enabledCommand: String, disabledCommand: String): Boolean {
        val enabled = buttons.firstOrNull { it.command.line() == enabledCommand }?.disabled
        val disabled = buttons.firstOrNull { it.command.line() == disabledCommand }?.disabled
        return when {
            enabled == true -> true
            disabled == true -> false
            else -> throw IllegalArgumentException("Missing setting buttons")
        }
    }

    private fun String.line() = substringBefore('\n').trim()
}

private class FriendHtmlHandler : DefaultHandler() {
    private var depth = 0
    private var heading = ""
    private var headingDepth = -1
    private var boxDepth = -1
    private var usernameDepth = -1
    private var italicDepth = -1
    private var timeDepth = -1
    private var errorDepth = -1
    private val text = StringBuilder()
    private val current = StringBuilder()
    private val currentHeading = StringBuilder()
    private val currentUsername = StringBuilder()
    private val currentItalic = StringBuilder()
    private val currentTime = StringBuilder()
    private val currentError = StringBuilder()
    private val italics = mutableListOf<String>()
    private var username: String? = null
    private var time: String? = null
    private val boxes = mutableListOf<ParsedBox>()
    private val buttons = mutableListOf<ParsedButton>()

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        depth++
        val tag = (localName.takeUnless { it.isNullOrEmpty() } ?: qName).orEmpty().lowercase()
        val classes = attributes.getValue("class").orEmpty()
        when {
            tag == "h4" -> {
                headingDepth = depth
                currentHeading.setLength(0)
            }
            tag == "div" && classes.split(' ').any { it.equals("infobox", true) } && boxDepth == -1 -> {
                boxDepth = depth
                current.setLength(0)
                italics.clear()
                username = null
                time = null
            }
        }
        if (boxDepth != -1 && tag == "username") {
            usernameDepth = depth
            currentUsername.setLength(0)
        }
        if (boxDepth != -1 && tag == "i") {
            italicDepth = depth
            currentItalic.setLength(0)
        }
        if (boxDepth != -1 && tag == "time") {
            timeDepth = depth
            currentTime.setLength(0)
        }
        if (classes.split(' ').any { it.equals("message-error", true) }) errorDepth = depth
        if (tag == "button") {
            val command = attributes.getValue("value")?.trim().orEmpty()
            if (command.isNotEmpty()) buttons += ParsedButton(command, classes.split(' ').any { it.equals("disabled", true) })
        }
        if (tag == "br") append("\n")
    }

    override fun characters(ch: CharArray, start: Int, length: Int) = append(String(ch, start, length))

    private fun append(value: String) {
        text.append(value)
        if (boxDepth != -1) current.append(value)
        if (headingDepth != -1) currentHeading.append(value)
        if (usernameDepth != -1) currentUsername.append(value)
        if (italicDepth != -1) currentItalic.append(value)
        if (timeDepth != -1) currentTime.append(value)
        if (errorDepth != -1) currentError.append(value)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        if (depth == usernameDepth) {
            username = currentUsername.toString().trim().takeIf(String::isNotEmpty)
            usernameDepth = -1
        }
        if (depth == italicDepth) {
            currentItalic.toString().trim().takeIf(String::isNotEmpty)?.let(italics::add)
            italicDepth = -1
        }
        if (depth == timeDepth) {
            time = currentTime.toString().trim().takeIf(String::isNotEmpty)
            timeDepth = -1
        }
        if (depth == headingDepth) {
            heading = currentHeading.toString().trim()
            headingDepth = -1
        }
        if (depth == errorDepth) errorDepth = -1
        if (depth == boxDepth) {
            boxes += ParsedBox(heading, current.toString().trim(), username, italics.toList(), time)
            boxDepth = -1
        }
        depth--
    }

    private fun result() = ParsedFriendHtml(
            text.toString().replace(Regex("\\s+"), " ").trim(),
            currentError.toString().replace(Regex("\\s+"), " ").trim(),
            boxes,
            buttons)

    companion object {
        fun parse(html: String): ParsedFriendHtml {
            val handler = FriendHtmlHandler()
            val parser = Parser()
            parser.setProperty(Parser.schemaProperty, HTMLSchema())
            parser.contentHandler = handler
            parser.parse(InputSource(StringReader(html)))
            return handler.result()
        }
    }
}
