package com.majeur.psclient.ui

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.majeur.psclient.R
import com.majeur.psclient.databinding.DialogPrivateChatBinding
import com.google.android.material.snackbar.Snackbar
import com.majeur.psclient.util.TextTagSpan
import com.majeur.psclient.util.Utils
import com.majeur.psclient.util.applySafeDrawingInsets
import com.majeur.psclient.util.configureEdgeToEdge
import com.majeur.psclient.util.resizeForIme
import com.majeur.psclient.util.toId


class PrivateChatDialog : DialogFragment() {

    private val usernameColorCache = mutableMapOf<String, Int>()
    private var errorSnackbar: Snackbar? = null

    lateinit var chatWith: String
        private set

    private var _binding: DialogPrivateChatBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatWith = requireArguments().getString(ARG_CHAT_WITH)!!
        if (!resources.getBoolean(R.bool.canUseLandscapeLayout))
            setStyle(STYLE_NO_TITLE, R.style.Theme_PSClient_FullScreenDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = DialogPrivateChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        errorSnackbar?.dismiss()
        errorSnackbar = null
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.apply {
            toolbar.title = "Private chat: $chatWith"
            toolbar.setNavigationOnClickListener { dismiss() }
            chatLog.setText("", TextView.BufferType.SPANNABLE)
            messageInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessageIfAny()
                    return@setOnEditorActionListener true
                }
                false
            }
            sendButton.setOnClickListener { sendMessageIfAny() }
        }
        (activity as MainActivity).homeFragment.getPrivateMessages(chatWith)?.forEach {
            onNewMessage(it)
        }
    }

    override fun onStart() {
        super.onStart()
        requireDialog().resizeForIme()
        val fullScreen = !resources.getBoolean(R.bool.canUseLandscapeLayout)
        val width = if (fullScreen) ViewGroup.LayoutParams.MATCH_PARENT
        else resources.getDimensionPixelSize(R.dimen.dialog_max_width)
        requireDialog().window?.apply {
            setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT)
            if (fullScreen) {
                configureEdgeToEdge(resources)
                binding.root.applySafeDrawingInsets(includeIme = true)
            }
        }
    }

    fun onNewMessage(message: String) {
        val sepIndex = message.indexOf(':')
        if (message.substring(sepIndex + 2).startsWith("/error")) {
            onError(message.substring(sepIndex + 9))
            return
        }
        val username = message.substring(0, sepIndex)
        val textColor = obtainUsernameColor(username)
        val spannable = SpannableString(message)
        spannable.setSpan(TextTagSpan(Utils.getTagColor(textColor), textColor), 0, sepIndex + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        printMessage(spannable)
    }

    fun onError(message: String) {
        errorSnackbar?.dismiss()
        errorSnackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_INDEFINITE)
                .setAnchorView(binding.messageInput)
                .setAction("Ok") {}
                .also { snackbar ->
                    snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).maxLines = 5
                    snackbar.show()
                }
    }

    private fun printMessage(message: CharSequence) {
        if (binding.chatLog.length() > 0) binding.chatLog.append("\n")
        binding.chatLog.append(message)
        binding.root.post { binding.chatLogContainer.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendMessageIfAny() {
        val message = binding.messageInput.text.toString()
        if (message.isNotEmpty()) {
            (activity as MainActivity).service?.sendPrivateMessage(chatWith.toId(), message)
            binding.messageInput.text.clear()
        }
    }

    private fun obtainUsernameColor(username: String): Int {

        return usernameColorCache.getOrElse(username.toId()) {
            Utils.hashColor(username.toId()).also { usernameColorCache[username.toId()] = it }
        }
    }

    companion object {
        const val FRAGMENT_TAG = "private-chat-dialog"
        private const val ARG_CHAT_WITH = "chat-with"
        fun newInstance(with: String?): PrivateChatDialog {
            val dialog = PrivateChatDialog()
            val bundle = Bundle()
            bundle.putString(ARG_CHAT_WITH, with)
            dialog.arguments = bundle
            return dialog
        }
    }
}
