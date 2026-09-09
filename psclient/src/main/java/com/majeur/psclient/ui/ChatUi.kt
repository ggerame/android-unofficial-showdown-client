package com.majeur.psclient.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import com.majeur.psclient.databinding.ViewChatContentBinding

internal fun ViewChatContentBinding.onSendMessage(send: (String) -> Unit) {
    fun submit(): Boolean {
        val message = messageInput.text?.toString().orEmpty()
        if (message.isEmpty()) return false
        send(message)
        messageInput.text?.clear()
        return true
    }
    sendButton.setOnClickListener { submit() }
    messageInput.setOnEditorActionListener { _, actionId, _ ->
        if (actionId != EditorInfo.IME_ACTION_SEND) return@setOnEditorActionListener false
        submit()
        true
    }
}

internal fun ViewChatContentBinding.appendMessage(message: CharSequence) {
    if (chatLog.length() > 0) chatLog.append("\n")
    chatLog.append(message)
}

internal fun ViewChatContentBinding.scrollToBottom() {
    chatLogContainer.post { chatLogContainer.fullScroll(View.FOCUS_DOWN) }
}
