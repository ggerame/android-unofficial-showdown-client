package com.majeur.psclient.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.majeur.psclient.R
import com.majeur.psclient.databinding.DialogFriendsBinding
import com.majeur.psclient.model.FriendInfo
import com.majeur.psclient.model.FriendAction
import com.majeur.psclient.model.FriendPresence
import com.majeur.psclient.model.FriendSettings
import com.majeur.psclient.model.FriendsPage
import com.majeur.psclient.model.FriendsPageData
import com.majeur.psclient.model.friendCommand
import com.majeur.psclient.service.observer.FriendsMessageObserver
import com.majeur.psclient.util.applySafeDrawingInsets
import com.majeur.psclient.util.configureEdgeToEdge
import com.majeur.psclient.util.resizeForIme
import com.majeur.psclient.util.toId

class FriendsDialog : androidx.fragment.app.DialogFragment(), FriendsMessageObserver.UiCallbacks {

    private lateinit var binding: DialogFriendsBinding
    private val service get() = (activity as? MainActivity)?.service
    private val rows = mutableListOf<Row>()
    private val adapter = RowsAdapter()
    private val openedPages = mutableSetOf<FriendsPage>()
    private var friends: List<FriendInfo>? = null
    private var received: List<String>? = null
    private var sent: List<String>? = null
    private var settings: FriendSettings? = null
    private val errors = mutableMapOf<FriendsPage, String>()
    private var selectedTab = TAB_FRIENDS

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogFriendsBinding.inflate(layoutInflater)
        selectedTab = savedInstanceState?.getInt(STATE_TAB) ?: TAB_FRIENDS
        binding.list.adapter = adapter
        binding.toolbar.setNavigationOnClickListener { dismiss() }
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_add_friend -> promptAddFriend()
                R.id.action_refresh_friends -> refresh()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        listOf(R.string.friends, R.string.friend_requests, R.string.friend_settings).forEach {
            binding.tabs.addTab(binding.tabs.newTab().setText(it), false)
        }
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.position
                render()
                refresh()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = refresh()
        })
        binding.tabs.getTabAt(selectedTab)?.select()
        binding.retryButton.setOnClickListener { refresh() }

        val fullScreen = !resources.getBoolean(R.bool.canUseLandscapeLayout)
        if (fullScreen) {
            return Dialog(requireContext(), R.style.Theme_PSClient_FullScreenDialog).apply {
                setContentView(binding.root)
            }
        }
        return MaterialAlertDialogBuilder(requireContext())
                .setView(binding.root)
                .create()
    }

    override fun onStart() {
        super.onStart()
        service?.friendsMessageObserver?.uiCallbacks = this
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
        refresh()
    }

    override fun onStop() {
        if (service?.friendsMessageObserver?.uiCallbacks === this) {
            service?.friendsMessageObserver?.uiCallbacks = null
        }
        super.onStop()
    }

    override fun onDismiss(dialog: DialogInterface) {
        openedPages.forEach { service?.sendGlobalCommand("leave", it.roomId) }
        openedPages.clear()
        super.onDismiss(dialog)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, selectedTab)
        super.onSaveInstanceState(outState)
    }

    override fun onFriendsPage(page: FriendsPage, data: FriendsPageData) {
        errors.remove(page)
        when (data) {
            is FriendsPageData.Friends -> friends = data.entries
            is FriendsPageData.Requests -> if (page == FriendsPage.RECEIVED) {
                received = data.users
            } else {
                sent = data.users
            }
            is FriendsPageData.Settings -> settings = data.value
            is FriendsPageData.Error -> errors[page] = data.message
        }
        render()
    }

    override fun onFriendsNetworkError() = showError(getString(R.string.friends_connection_error))

    fun onError(message: String) {
        render()
        Snackbar.make(binding.root, message, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.retry) { refresh() }
                .show()
    }

    fun onAccountChanged() {
        friends = null
        received = null
        sent = null
        settings = null
        errors.clear()
        render()
        refresh()
    }

    private fun refresh() {
        if (service?.isConnected != true) {
            showError(getString(R.string.friends_connection_error))
            return
        }
        when (selectedTab) {
            TAB_FRIENDS -> load(FriendsPage.ALL)
            TAB_REQUESTS -> {
                load(FriendsPage.RECEIVED)
                load(FriendsPage.SENT)
            }
            TAB_SETTINGS -> load(FriendsPage.SETTINGS)
        }
        showLoadingIfEmpty()
    }

    private fun load(page: FriendsPage) {
        openedPages += page
        service?.sendGlobalCommand("join", page.roomId)
    }

    private fun render() {
        if (!this::binding.isInitialized) return
        binding.list.isVisible = selectedTab != TAB_SETTINGS
        binding.settingsScroll.isVisible = selectedTab == TAB_SETTINGS
        binding.settingsContainer.removeAllViews()
        rows.clear()
        when (selectedTab) {
            TAB_FRIENDS -> {
                errors[FriendsPage.ALL]?.let { showError(it); return }
                val data = friends ?: run { showLoading(); return }
                if (data.isEmpty()) showEmpty(R.string.no_friends) else {
                    rows += data.map(::FriendRow)
                    showRows()
                }
            }
            TAB_REQUESTS -> {
                (errors[FriendsPage.RECEIVED] ?: errors[FriendsPage.SENT])?.let {
                    showError(it)
                    return
                }
                val incoming = received ?: run { showLoading(); return }
                val outgoing = sent ?: run { showLoading(); return }
                rows += incoming.map { RequestRow(it, incoming = true) }
                rows += outgoing.map { RequestRow(it, incoming = false) }
                if (rows.isEmpty()) showEmpty(R.string.no_friend_requests) else showRows()
            }
            TAB_SETTINGS -> {
                errors[FriendsPage.SETTINGS]?.let { showError(it); return }
                val data = settings ?: run { showLoading(); return }
                showSettings(data)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun showLoadingIfEmpty() {
        val missing = when (selectedTab) {
            TAB_FRIENDS -> friends == null
            TAB_REQUESTS -> received == null || sent == null
            else -> settings == null
        }
        if (missing) showLoading()
    }

    private fun showLoading() {
        binding.stateContainer.isVisible = true
        binding.progress.isVisible = true
        binding.stateMessage.isVisible = false
        binding.retryButton.isVisible = false
        binding.list.isVisible = false
        binding.settingsScroll.isVisible = false
    }

    private fun showEmpty(message: Int) {
        binding.stateContainer.isVisible = true
        binding.progress.isVisible = false
        binding.stateMessage.setText(message)
        binding.stateMessage.isVisible = true
        binding.retryButton.isVisible = false
        binding.list.isVisible = false
        binding.settingsScroll.isVisible = false
    }

    private fun showError(message: String) {
        if (!this::binding.isInitialized) return
        binding.stateContainer.isVisible = true
        binding.progress.isVisible = false
        binding.stateMessage.text = message
        binding.stateMessage.isVisible = true
        binding.retryButton.isVisible = true
        binding.list.isVisible = false
        binding.settingsScroll.isVisible = false
    }

    private fun showRows() {
        binding.stateContainer.isVisible = false
        binding.list.isVisible = true
        binding.settingsScroll.isVisible = false
    }

    private fun showSettings(value: FriendSettings) {
        binding.stateContainer.isVisible = false
        binding.list.isVisible = false
        binding.settingsScroll.isVisible = true
        addSetting(R.string.friend_receive_requests, value.receiveRequests,
                "/friends toggle on", "/friends toggle off")
        addSetting(R.string.friend_public_list, value.publicList,
                "/friends listdisplay yes", "/friends listdisplay no")
        addSetting(R.string.friend_share_last_seen, value.shareLastSeen,
                "/friends showlogins", "/friends hidelogins")
        addSetting(R.string.friend_share_battles, value.shareBattles,
                "/friends sharebattles on", "/friends sharebattles off")
        addSetting(R.string.friend_only_pms, value.friendsOnlyPms,
                "/blockpms friends\n/j view-friends-settings",
                "/unblockpms\n/j view-friends-settings")
        addSetting(R.string.friend_only_challenges, value.friendsOnlyChallenges,
                "/blockchallenges friends\n/j view-friends-settings",
                "/unblockchallenges\n/j view-friends-settings")
    }

    private fun addSetting(label: Int, checked: Boolean, onCommand: String, offCommand: String) {
        val view = SwitchMaterial(requireContext()).apply {
            text = getString(label)
            isChecked = checked
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnCheckedChangeListener { button, enabled ->
                button.isEnabled = false
                service?.sendRoomMessage(null, if (enabled) onCommand else offCommand)
            }
        }
        binding.settingsContainer.addView(view, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun promptAddFriend() {
        val dialogBinding = com.majeur.psclient.databinding.DialogSimpleInputBinding.inflate(layoutInflater)
        dialogBinding.inputContainer.hint = getString(R.string.username)
        val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.add_friend)
                .setView(dialogBinding.root)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.add_friend, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val user = dialogBinding.input.text?.toString().orEmpty().toId()
                if (user.isEmpty()) {
                    dialogBinding.inputContainer.error = getString(R.string.required_field)
                } else {
                    sendFriendAction(FriendAction.ADD, user)
                    dialog.dismiss()
                }
            }
        }
        dialog.resizeForIme(showKeyboard = true)
        dialog.show()
    }

    private fun presenceLabel(presence: FriendPresence) = getString(when (presence) {
        FriendPresence.ONLINE -> R.string.friend_online
        FriendPresence.IDLE -> R.string.friend_idle
        FriendPresence.BUSY -> R.string.friend_busy
        FriendPresence.OFFLINE -> R.string.friend_offline
    })

    private sealed class Row
    private data class FriendRow(val friend: FriendInfo) : Row()
    private data class RequestRow(val user: String, val incoming: Boolean) : Row()

    private inner class RowsAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val row = getItem(position)) {
                is FriendRow -> friendView(row, convertView, parent)
                is RequestRow -> requestView(row, convertView, parent)
            }
        }

        private fun friendView(row: FriendRow, convertView: View?, parent: ViewGroup): View {
            val view = convertView?.takeIf { it.tag == FRIEND_ROW_TAG }
                    ?: layoutInflater.inflate(R.layout.list_item_friend, parent, false).apply { tag = FRIEND_ROW_TAG }
            view.findViewById<TextView>(R.id.username).text = row.friend.name
            val details = buildList {
                add(presenceLabel(row.friend.presence))
                row.friend.status?.let(::add)
                row.friend.lastSeen?.let { add(getString(R.string.friend_last_seen, it)) }
            }.joinToString(" · ")
            view.findViewById<TextView>(R.id.details).text = details
            view.findViewById<View>(R.id.chat_button).setOnClickListener {
                (parentFragment as? HomeFragment)?.startPrivateChat(row.friend.name)
            }
            view.findViewById<View>(R.id.challenge_button).setOnClickListener {
                (parentFragment as? HomeFragment)?.challengeSomeone(row.friend.name)
            }
            view.setOnClickListener { service?.sendGlobalCommand("cmd userdetails", row.friend.id) }
            return view
        }

        private fun requestView(row: RequestRow, convertView: View?, parent: ViewGroup): View {
            val view = convertView?.takeIf { it.tag == REQUEST_ROW_TAG }
                    ?: layoutInflater.inflate(R.layout.list_item_friend_request, parent, false).apply { tag = REQUEST_ROW_TAG }
            view.findViewById<TextView>(R.id.username).text = row.user
            view.findViewById<TextView>(R.id.request_type).setText(
                    if (row.incoming) R.string.incoming_friend_request else R.string.outgoing_friend_request)
            val primary = view.findViewById<TextView>(R.id.primary_button)
            val secondary = view.findViewById<View>(R.id.secondary_button)
            primary.setText(if (row.incoming) R.string.accept else R.string.undo)
            secondary.isVisible = row.incoming
            primary.setOnClickListener {
                sendFriendAction(if (row.incoming) FriendAction.ACCEPT else FriendAction.UNDO_REQUEST, row.user)
            }
            secondary.setOnClickListener { sendFriendAction(FriendAction.REJECT, row.user) }
            return view
        }
    }

    private fun sendFriendAction(action: FriendAction, username: String) {
        friendCommand(action, username)?.let { service?.sendRoomMessage(null, it) }
    }

    companion object {
        const val TAG = "friends-dialog"
        private const val STATE_TAB = "friends-tab"
        private const val TAB_FRIENDS = 0
        private const val TAB_REQUESTS = 1
        private const val TAB_SETTINGS = 2
        private const val FRIEND_ROW_TAG = "friend"
        private const val REQUEST_ROW_TAG = "request"
    }
}
