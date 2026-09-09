package com.majeur.psclient.ui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.TextView
import com.majeur.psclient.R
import com.majeur.psclient.databinding.FragmentChatBinding
import com.majeur.psclient.io.AssetLoader
import com.majeur.psclient.io.GlideHelper
import com.majeur.psclient.model.ChatRoomInfo
import com.majeur.psclient.service.ShowdownService
import com.majeur.psclient.service.observer.ChatRoomMessageObserver
import com.majeur.psclient.util.Callback
import com.majeur.psclient.util.Utils
import com.majeur.psclient.util.html.Html
import com.majeur.psclient.util.toId


class ChatFragment : BaseFragment(), ChatRoomMessageObserver.UiCallbacks {

    private val observer get() = service!!.chatMessageObserver

    private lateinit var inputMethodManager: InputMethodManager
    private lateinit var glideHelper: GlideHelper
    private lateinit var assetLoader: AssetLoader

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val chat get() = binding.chatContent

    private var _observedRoomId: String? = null
    var observedRoomId: String?
        get() = _observedRoomId
        set(observedRoomId) {
            _observedRoomId = observedRoomId
            observer.observedRoomId = observedRoomId
        }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        glideHelper = mainActivity.glideHelper
        assetLoader = mainActivity.assetLoader
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        service?.chatMessageObserver?.uiCallbacks = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        chat.chatLog.apply {
            movementMethod = LinkMovementMethod()
            animate().duration = 200
        }
        binding.joinButton.setOnClickListener {
            if (service?.isConnected != true) return@setOnClickListener
            if (observer.roomJoined) service?.sendRoomCommand(observedRoomId, "leave")
            else service?.sendGlobalCommand("cmd", "rooms")
        }
        chat.emptyState.setOnClickListener { binding.joinButton.performClick() }
        binding.usersCount.setOnClickListener { v: View ->
            val adapter = ArrayAdapter(requireActivity(), android.R.layout.simple_list_item_1, observer.users)
            AlertDialog.Builder(requireActivity())
                    .setTitle("Users")
                    .setAdapter(adapter) { dialog: DialogInterface, pos: Int ->
                        service?.sendGlobalCommand("cmd userdetails", adapter.getItem(pos)!!.toId())
                        dialog.dismiss()
                    }
                    .setNegativeButton("Close", null)
                    .show()
        }
        chat.onSendMessage { service?.sendRoomMessage(observedRoomId, it) }
        setUiState(roomJoined = false)
    }

    private fun setUiState(roomJoined: Boolean) {
        if (roomJoined) {
            binding.apply {
                chat.emptyState.visibility = View.GONE
                chat.messageInput.isEnabled = true
                chat.messageInput.requestFocus()
                chat.sendButton.isEnabled = true
                chat.sendButton.drawable.alpha = 255
                joinButton.setImageResource(R.drawable.ic_exit)
                chat.chatLog.gravity = Gravity.START
                chat.chatLog.setText("", TextView.BufferType.EDITABLE)
            }
        } else {
            binding.apply {
                roomTitle.setText(R.string.chat)
                usersCount.text = "-\nusers"
                chat.messageInput.text?.clear()
                chat.messageInput.clearFocus()
                chat.messageInput.isEnabled = false
                chat.sendButton.isEnabled = false
                chat.sendButton.drawable.alpha = 128
                joinButton.setImageResource(R.drawable.ic_enter)
                joinButton.requestFocus() // Remove focus from message input widget
                chat.chatLog.text = ""
                chat.chatLog.gravity = Gravity.START
                chat.emptyState.visibility = View.VISIBLE
            }
            inputMethodManager.hideSoftInputFromWindow(chat.messageInput.windowToken, 0)
        }
    }

    override fun onServiceBound(service: ShowdownService) {
        super.onServiceBound(service)
        service.chatMessageObserver.uiCallbacks = this
    }

    override fun onServiceWillUnbound(service: ShowdownService) {
        super.onServiceWillUnbound(service)
        service.chatMessageObserver.uiCallbacks = null
    }

    fun onAvailableRoomsChanged(officialRooms: List<ChatRoomInfo>, chatRooms: List<ChatRoomInfo>) {
        if (parentFragmentManager.findFragmentByTag(JoinChatRoomDialog.FRAGMENT_TAG) == null)
            JoinChatRoomDialog.newInstance(officialRooms, chatRooms)
                .show(parentFragmentManager, JoinChatRoomDialog.FRAGMENT_TAG)
    }

    private fun notifyNewMessageReceived() {
        mainActivity.showBadge(id)
    }

    private fun postFullScroll() {
        chat.scrollToBottom()
    }


    override fun onRoomInit() {
        setUiState(roomJoined = true)
    }

    override fun onRoomDeInit() {
        setUiState(roomJoined = false)
    }

    override fun onPrintText(text: CharSequence) {
        val fullScrolled = Utils.fullScrolled(chat.chatLogContainer)
        chat.appendMessage(text)
        notifyNewMessageReceived()
        if (fullScrolled) postFullScroll()
    }

    override fun onPrintHtml(html: String) {
        val mark = Any()
        val l = chat.chatLog.length()
        chat.chatLog.append("\u200C")
        chat.chatLog.editableText.setSpan(mark, l, l + 1, Spanned.SPAN_MARK_MARK)
        Html.fromHtml(html,
                Html.FROM_HTML_MODE_COMPACT,
                glideHelper.getHtmlImageGetter(assetLoader, chat.chatLog.width),
                Callback { spanned: Spanned? ->
                    val at = chat.chatLog.editableText.getSpanStart(mark)
                    if (at == -1) return@Callback // Check if text has been cleared
                    val fullScrolled = Utils.fullScrolled(chat.chatLogContainer)
                    chat.chatLog.editableText
                            .insert(at, "\n")
                            .insert(at + 1, spanned)
                    notifyNewMessageReceived()
                    if (fullScrolled) postFullScroll()
                })
    }

    override fun onRoomTitleChanged(title: String) {
        binding.roomTitle.text = title
    }

    override fun onUpdateUsers(users: List<String>) {
        binding.usersCount.text = "${users.size}\nusers"
    }
}
