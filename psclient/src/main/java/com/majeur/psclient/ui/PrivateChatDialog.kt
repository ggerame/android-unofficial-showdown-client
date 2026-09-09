package com.majeur.psclient.ui

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.majeur.psclient.R
import com.majeur.psclient.databinding.DialogPrivateChatBinding
import com.google.android.material.snackbar.Snackbar
import com.majeur.psclient.model.FriendAction
import com.majeur.psclient.util.TextTagSpan
import com.majeur.psclient.util.Utils
import com.majeur.psclient.util.applySafeDrawingInsets
import com.majeur.psclient.util.configureEdgeToEdge
import com.majeur.psclient.util.resizeForIme
import com.majeur.psclient.util.toId


class PrivateChatDialog : DialogFragment() {

    private val usernameColorCache = mutableMapOf<String, Int>()
    private var errorSnackbar: Snackbar? = null
    private var friendMenuItem: MenuItem? = null
    private var isFriend = false
    private var pendingRequestAction: FriendAction? = null

    lateinit var chatWith: String
        private set

    private var _binding: DialogPrivateChatBinding? = null
    private val binding get() = _binding!!
    private val chat get() = binding.chatContent

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
            friendMenuItem = toolbar.menu.add(R.string.add_friend).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                isVisible = false
                setOnMenuItemClickListener {
                    if (isFriend) homeFragment.confirmRemoveFriend(chatWith)
                    else homeFragment.sendFriendAction(FriendAction.ADD, chatWith)
                    true
                }
            }
            friendRequestText.text = getString(R.string.friend_request_from, chatWith)
            acceptFriendButton.setOnClickListener { resolveFriendRequest(FriendAction.ACCEPT) }
            denyFriendButton.setOnClickListener { resolveFriendRequest(FriendAction.REJECT) }
        }
        chat.chatLog.setText("", TextView.BufferType.SPANNABLE)
        chat.onSendMessage {
            (activity as MainActivity).service?.sendPrivateMessage(chatWith.toId(), it)
        }
        (activity as MainActivity).homeFragment.getPrivateMessages(chatWith)?.forEach {
            onNewMessage(it)
        }
        onFriendRequestChanged(homeFragment.hasPendingFriendRequest(chatWith))
        homeFragment.requestFriendStateForChat(chatWith)
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
        _binding?.apply {
            acceptFriendButton.isEnabled = true
            denyFriendButton.isEnabled = true
        }
        errorSnackbar?.dismiss()
        errorSnackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_INDEFINITE)
                .setAnchorView(chat.messageInput)
                .setAction(if (pendingRequestAction != null) getString(R.string.retry) else "Ok") {
                    pendingRequestAction?.let(::resolveFriendRequest)
                }
                .also { snackbar ->
                    snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).maxLines = 5
                    snackbar.show()
                }
    }

    private fun printMessage(message: CharSequence) {
        chat.appendMessage(message)
        chat.scrollToBottom()
    }

    fun onFriendshipStatus(friended: Boolean) {
        isFriend = friended
        friendMenuItem?.apply {
            setTitle(if (friended) R.string.remove_friend else R.string.add_friend)
            isVisible = true
        }
    }

    fun onFriendRequestChanged(pending: Boolean) {
        val currentBinding = _binding ?: return
        currentBinding.friendRequestCard.visibility = if (pending) View.VISIBLE else View.GONE
        currentBinding.acceptFriendButton.isEnabled = true
        currentBinding.denyFriendButton.isEnabled = true
        if (!pending) {
            pendingRequestAction = null
            homeFragment.requestFriendStateForChat(chatWith)
        }
    }

    private fun resolveFriendRequest(action: FriendAction) {
        pendingRequestAction = action
        binding.acceptFriendButton.isEnabled = false
        binding.denyFriendButton.isEnabled = false
        homeFragment.sendFriendAction(action, chatWith)
    }

    private val homeFragment get() = (activity as MainActivity).homeFragment

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
