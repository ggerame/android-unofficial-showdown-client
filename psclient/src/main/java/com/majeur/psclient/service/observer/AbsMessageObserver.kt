package com.majeur.psclient.service.observer

import com.majeur.psclient.service.ServerMessage
import com.majeur.psclient.service.ShowdownService
import timber.log.Timber

abstract class AbsMessageObserver<C : AbsMessageObserver.UiCallbacks>(
        val service: ShowdownService
) {

    var uiCallbacks: C? = null
        set(value) {
            field = value
            if (value != null) onUiCallbacksAttached()
        }

    protected abstract fun onUiCallbacksAttached()

    open var observedRoomId: String? = null

    open val interceptCommandBefore = emptySet<String>()

    open val interceptCommandAfter = emptySet<String>()

    fun postMessage(message: ServerMessage, forcePost: Boolean = false) {
        if (forcePost || observedRoomId == message.roomId) {
            // A single malformed message or handler bug must never crash the whole app.
            // Log it and keep processing the rest of the server stream.
            try {
                onMessage(message)
            } catch (e: Exception) {
                Timber.e(e, "Error while handling message '%s' in room '%s'", message.command, message.roomId)
            }
        }
    }

    protected abstract fun onMessage(message: ServerMessage)

    interface UiCallbacks {

    }
}