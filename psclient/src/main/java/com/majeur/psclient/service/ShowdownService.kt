package com.majeur.psclient.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.majeur.psclient.service.observer.BattleRoomMessageObserver
import com.majeur.psclient.service.observer.ChatRoomMessageObserver
import com.majeur.psclient.service.observer.GlobalMessageObserver
import com.majeur.psclient.model.common.TeamValidationResult
import com.majeur.psclient.model.common.RemoteTeamSummary
import com.majeur.psclient.util.toId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal fun buildReplaySearchUrl(usernames: List<String>, format: String, page: Int): HttpUrl {
    require(page >= 1) { "Replay pages are 1-based" }
    val users = usernames.filter(String::isNotBlank)
    require(users.size <= 2) { "At most two replay usernames are supported" }
    return HttpUrl.Builder().run {
        scheme("https")
        host("replay.pokemonshowdown.com")
        addPathSegment("search.json")
        users.getOrNull(0)?.let { addQueryParameter("user", it) }
        users.getOrNull(1)?.let { addQueryParameter("user2", it) }
        if (format.isNotBlank()) addQueryParameter("format", format)
        addQueryParameter("page", page.toString())
        build()
    }
}

class ShowdownService : Service() {

    companion object {
        private const val WS_CLOSE_NORMAL = 1000
        private const val WS_CLOSE_GOING_AWAY = 1001
        private const val WS_CLOSE_NETWORK_ERROR = 4001
        private const val SHOWDOWN_SOCKET_URL = "wss://sim3.psim.us/showdown/websocket"
        private const val BROWSER_USER_AGENT =
                "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }

    internal lateinit var okHttpClient: OkHttpClient
        private set

    private lateinit var binder: Binder
    private lateinit var uiHandler: Handler

    val globalMessageObserver by lazy { GlobalMessageObserver(this) }
    val chatMessageObserver by lazy { ChatRoomMessageObserver(this) }
    val battleMessageObserver by lazy { BattleRoomMessageObserver(this) }
    private val messageObservers get() = listOf(globalMessageObserver, chatMessageObserver, battleMessageObserver)
    private var previousChatRoomId: String? = null
    private var previousBattleRoomId: String? = null

    val replayManager by lazy { ReplayManager(this) }

    private val sharedData = mutableMapOf<String, Any?>()
    private var webSocket: WebSocket? = null
    private var _connected = AtomicBoolean(false)
    private val registrationInProgress = AtomicBoolean(false)
    @Volatile
    var isCurrentUserRegistered: Boolean? = null
        private set
    @Volatile
    private var pendingRegistrationOfferUsername: String? = null
    private var validationCallback: ((TeamValidationResult) -> Unit)? = null
    private var teamCommandCallback: ((Boolean, String) -> Unit)? = null
    private val validationTimeout = Runnable {
        validationCallback?.also { callback ->
            validationCallback = null
            callback(TeamValidationResult(false, "Team validation timed out"))
        }
    }

    @Suppress("ObjectLiteralToLambda")
    private val stopSelfRunnable = object : Runnable {
        override fun run() = stopSelf()
    }

    var isConnected: Boolean
        private set(value) = _connected.set(value)
        get() = _connected.get()

    override fun onCreate() {
        Timber.d("(${hashCode()}) Lifecycle: onCreate")
        super.onCreate()
        uiHandler = Handler(Looper.getMainLooper())
        binder = Binder()
        okHttpClient = OkHttpClient.Builder()
                .build()
    }

    override fun onBind(intent: Intent): Binder {
        Timber.d("(${hashCode()}) Lifecycle: onBind")
        uiHandler.removeCallbacks(stopSelfRunnable)
        return binder
    }

    override fun onRebind(intent: Intent?) {
        Timber.d("(${hashCode()}) Lifecycle: onRebind")
        super.onRebind(intent)
        // We try to rejoin previously leaved rooms
        if (previousBattleRoomId != null)
            sendGlobalCommand("join", previousBattleRoomId!!)
        if (previousChatRoomId != null)
            sendGlobalCommand("join", previousChatRoomId!!)
        uiHandler.removeCallbacks(stopSelfRunnable)
    }

    override fun onUnbind(intent: Intent): Boolean {
        Timber.d("(${hashCode()}) Lifecycle: onUnbind")
        // If no activity is bound we leave every room we were into and keep their ids to rejoin them on next bind
        previousBattleRoomId = battleMessageObserver.observedRoomId
        if (previousBattleRoomId != null) sendRoomCommand(previousBattleRoomId, "leave")
        previousChatRoomId = chatMessageObserver.observedRoomId
        if (previousChatRoomId != null) sendRoomCommand(previousChatRoomId, "leave")
        // We stop our service (and close our WS connection) after 30 seconds with no activity bound
        uiHandler.postDelayed(stopSelfRunnable, 30000)
        return true
    }

    override fun onDestroy() {
        Timber.d("(${hashCode()}) Lifecycle: onDestroy")
        super.onDestroy()
        if (isConnected) webSocket?.close(WS_CLOSE_GOING_AWAY, null)
    }

    fun connectToServer() {
        if (isConnected) return
        Timber.d("Attempting to open WS connection.")
        val request = Request.Builder().url(SHOWDOWN_SOCKET_URL).build()
        webSocket = okHttpClient.newWebSocket(request, webSocketListener)
    }

    fun reconnectToServer() {
        if (isConnected) return
        connectToServer()
    }

    fun disconnectFromServer() {
        clearCurrentAccountState()
        if (!isConnected) return
        Timber.d("Attempting to close WS connection.")
        webSocket?.close(WS_CLOSE_NORMAL, "Normal closure")
        sharedData.clear()
        finishValidation(TeamValidationResult(false, "Disconnected before team validation completed"))
    }

    fun offerRegistrationFor(username: String) {
        pendingRegistrationOfferUsername = username.toId()
                .takeIf { it.isNotEmpty() && isCurrentUserRegistered == false }
    }

    fun consumeRegistrationOffer(username: String): Boolean {
        val offeredUsername = pendingRegistrationOfferUsername ?: return false
        pendingRegistrationOfferUsername = null
        return isCurrentUserRegistered == false && offeredUsername == username.toId()
    }

    internal fun markCurrentUserAsGuest() = clearCurrentAccountState()

    private fun clearCurrentAccountState() {
        isCurrentUserRegistered = null
        pendingRegistrationOfferUsername = null
    }

    fun sendTrnMessage(userName: String, assertion: String) {
        getSharedPreferences("user", Context.MODE_PRIVATE).edit()
                .putString("username", userName)
                .apply()
        sendGlobalCommand("trn", userName, "0", assertion)
    }

    fun sendPrivateMessage(to: String, content: String) {
        globalMessageObserver.onPrivateMessageSent(to)
        sendGlobalCommand("pm", to, content)
    }

    fun sendGlobalCommand(command: String, vararg args: Any) =
            sendRoomMessage(null, "/$command ${args.joinToString(",")}")

    fun validateTeam(packedTeam: String, formatId: String, callback: (TeamValidationResult) -> Unit) {
        if (!isConnected) {
            callback(TeamValidationResult(false, "Connect to Pokémon Showdown to validate this team"))
            return
        }
        if (validationCallback != null) {
            callback(TeamValidationResult(false, "Another team validation is already running"))
            return
        }
        validationCallback = callback
        uiHandler.postDelayed(validationTimeout, 15_000)
        sendGlobalCommand("utm", packedTeam)
        sendGlobalCommand("vtm", formatId)
    }

    fun consumeValidationPopup(message: String): Boolean {
        if (validationCallback == null) return false
        finishValidation(TeamValidationResult.fromPopup(message))
        return true
    }

    fun loadRemoteTeams(callback: (List<RemoteTeamSummary>?, String?) -> Unit) {
        requestTeamAction(mapOf("act" to "getteams")) { raw, error ->
            if (raw == null) callback(null, error) else try {
                callback(RemoteTeamSummary.parseResponse(raw), null)
            } catch (e: Exception) {
                Timber.e(e, "Invalid getteams response")
                callback(null, "Could not read teams returned by the server")
            }
        }
    }

    fun loadRemoteTeam(teamId: String, callback: (String?, String?) -> Unit) {
        requestTeamAction(mapOf("act" to "getteam", "teamid" to teamId)) { raw, error ->
            if (raw == null) callback(null, error) else try {
                callback(RemoteTeamSummary.parsePackedTeam(raw), null)
            } catch (e: Exception) {
                Timber.e(e, "Invalid getteam response")
                callback(null, "Could not read this server team")
            }
        }
    }

    private fun requestTeamAction(parameters: Map<String, String>, callback: (String?, String?) -> Unit) {
        val cookie = retrieveAuthCookieIfAny()
        if (cookie == null) {
            callback(null, "Sign in to load account teams")
            return
        }
        val body = FormBody.Builder().apply { parameters.forEach { (key, value) -> add(key, value) } }.build()
        val url = HttpUrl.Builder().scheme("https").host("play.pokemonshowdown.com")
                .addPathSegment("~~showdown").addPathSegment("action.php").build()
        okHttpClient.newCall(Request.Builder().url(url).addHeader("cookie", cookie).post(body).build())
                .enqueue(object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        val raw = response.body.string()
                        val responseCode = response.code
                        uiHandler.post {
                            if (response.isSuccessful && raw.isNotBlank()) callback(raw, null)
                            else callback(null, "The Showdown team server returned $responseCode")
                        }
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        Timber.e(e, "Team server call failed")
                        uiHandler.post { callback(null, "Could not reach the Showdown team server") }
                    }
                })
    }

    fun saveRemoteTeam(team: com.majeur.psclient.model.common.Team, callback: (Boolean, String) -> Unit) {
        if (teamCommandCallback != null) return callback(false, "Another account-team operation is running")
        teamCommandCallback = callback
        val name = team.label.replace(',', ' ')
        val privacy = if (team.remotePrivate) 1 else 0
        val command = if (team.remoteTeamId == null)
            "/teams save $name, ${team.format}, $privacy, ${team.pack()}"
        else
            "/teams update ${team.remoteTeamId}, $name, ${team.format}, $privacy, ${team.pack()}"
        sendRoomMessage(null, command)
    }

    fun consumeTeamCommand(query: String, response: String): Boolean {
        if (query != "teamupload" && query != "teamupdate") return false
        val callback = teamCommandCallback ?: return false
        teamCommandCallback = null
        callback(true, response)
        return true
    }

    fun consumeTeamCommandPopup(message: String): Boolean {
        val callback = teamCommandCallback ?: return false
        teamCommandCallback = null
        callback(false, message)
        return true
    }

    fun deleteRemoteTeam(teamId: String) = sendRoomMessage(null, "/teams delete $teamId")
    fun setRemoteTeamPrivacy(teamId: String, isPrivate: Boolean) =
            sendRoomMessage(null, "/teams setprivacy $teamId,${if (isPrivate) "yes" else "no"}")

    private fun finishValidation(result: TeamValidationResult) {
        val callback = validationCallback ?: return
        validationCallback = null
        uiHandler.removeCallbacks(validationTimeout)
        callback(result)
    }

    fun sendRoomCommand(roomId: String?, command: String, vararg args: Any?) =
            sendRoomMessage(roomId, "/$command ${args.joinToString("|")}")

    fun sendRoomMessage(roomId: String?, message: String) = sendMessage("${roomId ?: ""}|$message")

    private fun sendMessage(message: String) {
        val loggedMessage = redactSensitiveMessage(message)
        if (isConnected) {
            Timber.tag("WebSocket[SEND]").i(loggedMessage)
            webSocket?.send(message)
        } else {
            Timber.w("WebSocket not opened. Ignoring message: $loggedMessage")
        }
    }

    fun processServerData(data: String) {
        if (data[0] == '>') dispatchServerData(data.removePrefix(">").substringBefore("\n"),
                data.substringAfter("\n"))
        else dispatchServerData(null, data)
    }

    private fun dispatchServerData(roomId: String?, data: String) {
        data.split("\n")
                .filter { it.isNotBlank() }
                .forEach { dispatchMessage(ServerMessage(roomId ?: "lobby", it)) }
    }

    private fun dispatchMessage(msg: ServerMessage) {
        val observers = messageObservers
        val observersInterceptingBefore = observers
                .filter { it.interceptCommandBefore.contains(msg.command) }
        val observersInterceptingAfter = observers
                .filter { it.interceptCommandAfter.contains(msg.command) }

        observersInterceptingBefore
                .forEach { it.postMessage(msg, forcePost = true) }
        observers
                .minus(observersInterceptingBefore)
                .forEach { it.postMessage(msg) }
        observersInterceptingAfter
                .minus(observersInterceptingBefore)
                .forEach { it.postMessage(msg, forcePost = true) }
    }

    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.tag("WebSocket[OPEN]").i("Host: ${response.request.url.host}")
            isConnected = true
            uiHandler.post {
                dispatchMessage(ServerMessage("lobby", "|connected|"))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Timber.tag("WebSocket[RECEIVE]").i(text)
            uiHandler.post {
                processServerData(text)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.tag("WebSocket[CLOSING]").i(reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.tag("WebSocket[ERR]").w(t)
            isConnected = false
            clearCurrentAccountState()
            this@ShowdownService.webSocket = null
            uiHandler.post {
                dispatchMessage(ServerMessage("lobby", "|networkerror|"))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.tag("WebSocket[CLOSED]").i(reason)
            isConnected = false
            clearCurrentAccountState()
            this@ShowdownService.webSocket = null
        }
    }

    fun tryCookieSignIn() {
        val cookie = retrieveAuthCookieIfAny()
        if (cookie != null) {
            val url = actionServerUrlWithChallenge
                    .addQueryParameter("act", "upkeep")
                    .build()
            val request = Request.Builder()
                    .url(url)
                    .addHeader("cookie", cookie)
                    .build()
            okHttpClient.newCall(request).enqueue(object : Callback {
                @Throws(IOException::class)
                override fun onResponse(call: Call, response: Response) {
                    val rawResponse = response.body.string()
                    if (rawResponse.isEmpty()) {
                        Timber.e("Assertion request responded with an empty body.")
                        tryUsernameSignIn()
                        return
                    }
                    try {
                        val resultJson = JSONObject(rawResponse.removePrefix("]"))
                        if (resultJson.optBoolean("loggedin")) {
                            isCurrentUserRegistered = true
                            sendTrnMessage(resultJson.getString("username"),
                                    resultJson.getString("assertion"))
                        } else
                            tryUsernameSignIn()
                    } catch (e: JSONException) {
                        Timber.e(e, "Error while parsing assertion json.")
                        tryUsernameSignIn()
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    Timber.e(e, "Call failed.")
                    tryUsernameSignIn()
                }
            })
        } else {
            tryUsernameSignIn()
        }
    }

    private fun tryUsernameSignIn() {
        val username = getSharedPreferences("user", Context.MODE_PRIVATE)
                .getString("username", null) ?: return
        attemptSignIn(username, object : AttemptSignInCallback {
            override fun onSuccess(isRegistered: Boolean) {}
            override fun onAuthenticationRequired() {}
            override fun onError(reason: String) {}
        })
    }

    fun attemptSignIn(username: String, callback: AttemptSignInCallback) {
        val url: HttpUrl = actionServerUrlWithChallenge
                .addQueryParameter("act", "getassertion")
                .addQueryParameter("userid", username)
                .build()
        val request: Request = Request.Builder()
                .url(url)
                .build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                var rawResponse = response.body.string()
                if (rawResponse.isBlank()) {
                    uiHandler.post { callback.onError("Something is interfering with our connection to the login server. Most likely, your internet provider needs you to re-log-in, or your internet provider is blocking Pokémon Showdown.") }
                    return
                }
                if (rawResponse.startsWith("<!doctype html", true)) {
                    // some sort of MitM proxy; ignore it
                    rawResponse = rawResponse.substringAfter('>')
                }
                // Strip all line breaks — the assertion is now a multi-line
                // hex-encoded RSA signature that must be flattened before use.
                rawResponse = rawResponse.filter { it != '\r' && it != '\n' }
                if (rawResponse.contains('<')) {
                    uiHandler.post { callback.onError("Something is interfering with our connection to the login server. Most likely, your internet provider needs you to re-log-in, or your internet provider is blocking Pokémon Showdown.") }
                    return
                }
                when {
                    rawResponse == ";" -> {
                        uiHandler.post { callback.onAuthenticationRequired() }
                    }
                    rawResponse == ";;@gmail" -> {
                        uiHandler.post { callback.onError("Google log-in is not supported in this client, please use another account.") }
                    }
                    rawResponse.length >= 2 && rawResponse.substring(0, 2) == ";;" -> {
                        val errorReason: String = rawResponse.substring(2)
                        uiHandler.post { callback.onError(errorReason) }
                    }
                    else -> {
                        isCurrentUserRegistered = false
                        sendTrnMessage(username, rawResponse)
                        storeAuthCookieIfAny(response.headers("Set-Cookie"))
                        uiHandler.post { callback.onSuccess(false) }
                    }
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                uiHandler.post { callback.onError("An error occurred with your internet connection.") }
                Timber.e(e, "Call failed.")
            }
        })
    }

    fun attemptSignIn(username: String, password: String, callback: AttemptSignInCallback) {
        val dummyUrl: HttpUrl = actionServerUrl
                .addQueryParameter("act", "login")
                .addQueryParameter("name", username)
                .addQueryParameter("pass", password)
                .addQueryParameter("challstr", getSharedData<String>("challenge"))
                .build()
        val mediaType = "application/x-www-form-urlencoded".toMediaType()
        val request = Request.Builder()
                .url(actionServerUrl.build())
                .post(dummyUrl.query!!.toRequestBody(mediaType))
                .build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                val rawResponse = response.body.string()
                if (rawResponse.isEmpty()) {
                    uiHandler.post { callback.onError("Something is interfering with our connection to the login server. Most likely, your internet provider needs you to re-log-in, or your internet provider is blocking Pokémon Showdown.") }
                    return
                }
                try {
                    val json = JSONObject(rawResponse.removePrefix("]"))
                    if (json.optJSONObject("curuser")?.optBoolean("loggedin") == true) {
                        // success!
                        isCurrentUserRegistered = true
                        storeAuthCookieIfAny(response.headers("Set-Cookie"))
                        sendTrnMessage(username, json.getString("assertion"))
                        uiHandler.post { callback.onSuccess(true) }
                        return
                    }
                } catch (e: JSONException) {
                    Timber.e(e, "Error while parsing connection result json.")
                }
                uiHandler.post { callback.onError("Wrong password, please try again.") }
            }

            override fun onFailure(call: Call, e: IOException) {
                uiHandler.post { callback.onError("An error occurred with your internet connection.") }
                Timber.e(e, "Call failed.")
            }
        })
    }

    fun attemptRegistration(
            username: String,
            password: String,
            captcha: String,
            callback: AttemptRegistrationCallback
    ) {
        val challenge = getSharedData<String>("challenge")
        val currentUsername = globalMessageObserver.myUsername
        val sessionError = when {
            !isConnected -> "You are no longer connected to Pokémon Showdown."
            challenge.isNullOrBlank() -> "The login challenge is no longer available."
            isCurrentUserRegistered != false -> "This name is no longer available for registration."
            currentUsername == null || currentUsername.toId() != username.toId() ->
                "Your current name has changed. Reopen registration and try again."
            !registrationInProgress.compareAndSet(false, true) -> "Registration is already in progress."
            else -> null
        }
        if (sessionError != null) {
            uiHandler.post { callback.onError(sessionError) }
            return
        }

        val body = FormBody.Builder()
                .add("act", "register")
                .add("username", username)
                .add("password", password)
                .add("cpassword", password)
                .add("captcha", captcha)
                .add("challstr", challenge!!)
                .build()
        val request = Request.Builder()
                .url(showdownActionServerUrl)
                .post(body)
                .build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val rawResponse = response.body.string()
                try {
                    val json = JSONObject(rawResponse.removePrefix("]"))
                    val actionError = json.optString("actionerror")
                    val currentUser = json.optJSONObject("curuser")
                    val assertion = json.optString("assertion")
                    if (actionError.isNotBlank()) {
                        finishRegistration(callback, actionError)
                    } else if (currentUser?.optBoolean("loggedin") == true && assertion.isNotBlank()) {
                        val registeredUsername = currentUser.optString("username", username)
                        storeAuthCookieIfAny(response.headers("Set-Cookie"))
                        val sessionStillMatches = isConnected &&
                                getSharedData<String>("challenge") == challenge &&
                                isCurrentUserRegistered == false &&
                                globalMessageObserver.myUsername?.toId() == username.toId()
                        if (sessionStillMatches) {
                            isCurrentUserRegistered = true
                            pendingRegistrationOfferUsername = null
                            sendTrnMessage(registeredUsername, assertion)
                        }
                        registrationInProgress.set(false)
                        uiHandler.post { callback.onSuccess() }
                    } else {
                        finishRegistration(callback, "The login server could not register this name.")
                    }
                } catch (e: JSONException) {
                    Timber.e(e, "Error while parsing registration result json.")
                    finishRegistration(callback, "The login server returned an invalid response.")
                } catch (e: IOException) {
                    Timber.e(e, "Could not read registration result.")
                    finishRegistration(callback, "An error occurred with your internet connection.")
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                Timber.e(e, "Registration call failed.")
                finishRegistration(callback, "An error occurred with your internet connection.")
            }
        })
    }

    private fun finishRegistration(callback: AttemptRegistrationCallback, error: String) {
        registrationInProgress.set(false)
        uiHandler.post { callback.onError(error) }
    }

    private val actionServerUrl: HttpUrl.Builder
        get() = HttpUrl.Builder()
                .scheme("https")
                .host("play.pokemonshowdown.com")
                .addPathSegment("action.php")

    private val actionServerUrlWithChallenge: HttpUrl.Builder
        get() = actionServerUrl.addQueryParameter("challstr", getSharedData<String>("challenge"))

    private val showdownActionServerUrl: HttpUrl
        get() = HttpUrl.Builder()
                .scheme("https")
                .host("play.pokemonshowdown.com")
                .addPathSegment("~~showdown")
                .addPathSegment("action.php")
                .build()


    private fun storeAuthCookieIfAny(cookies: List<String>) {
        val cookie = cookies.firstOrNull { it.startsWith("sid") } ?: return
        val encodedCookie = Base64.encode(cookie.substringBefore(';').toByteArray(), Base64.DEFAULT)
        getSharedPreferences("user", Context.MODE_PRIVATE).edit()
                .putString("token", String(encodedCookie))
                .apply()
    }

    private fun retrieveAuthCookieIfAny() = getSharedPreferences("user", Context.MODE_PRIVATE)
            .getString("token", null)?.let {
                String(Base64.decode(it, Base64.DEFAULT))
            }

    fun forgetUserLoginInfos() {
        clearCurrentAccountState()
        getSharedPreferences("user", Context.MODE_PRIVATE).edit().clear().apply()
    }

    suspend fun retrieveReplayList(usernames: List<String>, format: String, page: Int = 1): JSONArray? {
        val url = buildReplaySearchUrl(usernames, format, page)
        val rawJson = rawCall(url) ?: ""
        return try {
            JSONArray(rawJson)
        } catch (e: JSONException) {
            Timber.e(e)
            null
        }
    }

    suspend fun retrieveLatestNews(): JSONArray? {
        val url = HttpUrl.Builder().run {
            scheme("https")
            host("pokemonshowdown.com")
            addPathSegment("news.json")
            build()
        }
        val rawJson = rawCall(url) ?: ""
        return try {
            JSONArray(rawJson)
        } catch (e: JSONException) {
            Timber.e(e)
            null
        }
    }

    suspend fun retrieveNews(newsId: Int): JSONObject? {
        val url = HttpUrl.Builder().run {
            scheme("https")
            host("pokemonshowdown.com")
            addPathSegment("news")
            addPathSegment("$newsId.json")
            build()
        }
        val rawJson = rawCall(url) ?: ""
        return try {
            JSONObject(rawJson)
        } catch (e: JSONException) {
            Timber.e(e)
            null
        }
    }

    suspend fun rawCall(url: HttpUrl): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
                .url(url)
                .build()
        return@withContext try {
            okHttpClient.newCall(request).execute().use { it.body.string() }
        } catch (e: IOException) {
            Timber.e(e)
            null
        }
    }

    fun putSharedData(key: String, data: Any?) {
        sharedData[key] = data
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getSharedData(key: String): T? {
        return sharedData[key] as T?
    }

    inner class Binder : android.os.Binder() {
        val service: ShowdownService
            get() = this@ShowdownService
    }

    interface AttemptSignInCallback {
        fun onSuccess(isRegistered: Boolean)
        fun onError(reason: String)
        fun onAuthenticationRequired()
    }

    interface AttemptRegistrationCallback {
        fun onSuccess()
        fun onError(reason: String)
    }
}

internal fun redactSensitiveMessage(message: String): String = when {
    message.startsWith("|/trn ") -> "<redacted authentication command>"
    message.startsWith("|/utm ") || message.startsWith("|/teams save") ||
            message.startsWith("|/teams update") -> "<redacted team command>"
    else -> message
}
