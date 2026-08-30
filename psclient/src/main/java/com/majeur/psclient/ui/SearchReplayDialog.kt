package com.majeur.psclient.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.majeur.psclient.R
import com.majeur.psclient.databinding.DialogSearchReplayBinding
import com.majeur.psclient.io.BattleFormatCache
import com.majeur.psclient.model.ReplayInfo
import com.majeur.psclient.model.common.BattleFormat
import com.majeur.psclient.util.NestedScrollLikeTouchListener
import com.majeur.psclient.util.applySafeDrawingInsets
import com.majeur.psclient.util.bold
import com.majeur.psclient.util.concat
import com.majeur.psclient.util.configureEdgeToEdge
import com.majeur.psclient.util.resizeForIme
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class SearchReplayDialog : DialogFragment(), AdapterView.OnItemClickListener {

    private var _binding: DialogSearchReplayBinding? = null
    private val binding get() = _binding!!
    private val homeFragment get() = parentFragment as HomeFragment

    private lateinit var listAdapter: ReplayListAdapter
    private lateinit var footerView: View
    private lateinit var moreButton: MaterialButton
    private var selectedFormatId = BattleFormat.FORMAT_ALL.id
    private var lastUsernames = emptyList<String>()
    private var lastFormat = ""
    private var hasRequested = false
    private var loading = false
    private var currentPage = 0
    private var cachedFormats = emptyList<BattleFormat.Category>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fullScreen = !resources.getBoolean(R.bool.canUseLandscapeLayout)
        setStyle(STYLE_NO_TITLE, if (fullScreen) R.style.Theme_PSClient_FullScreenDialog else 0)
        selectedFormatId = savedInstanceState?.getString(STATE_FORMAT_ID)
                ?: BattleFormat.FORMAT_ALL.id
        lastUsernames = savedInstanceState?.getStringArrayList(STATE_LAST_USERS).orEmpty()
        lastFormat = savedInstanceState?.getString(STATE_LAST_FORMAT).orEmpty()
        hasRequested = savedInstanceState?.getBoolean(STATE_HAS_REQUESTED) ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogSearchReplayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { dismiss() }
        binding.userInput.setText(savedInstanceState?.getString(STATE_INPUT_USERS).orEmpty())
        binding.userInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else false
        }

        childFragmentManager.setFragmentResultListener(
                FormatPickerDialogFragment.RESULT_KEY, viewLifecycleOwner) { _, result ->
            selectedFormatId = result.getString(FormatPickerDialogFragment.RESULT_FORMAT_ID)
                    ?: return@setFragmentResultListener
            updateFormatLabel()
        }
        binding.formatButton.setOnClickListener { showFormatPicker() }
        binding.searchButton.setOnClickListener { submitSearch() }

        footerView = layoutInflater.inflate(R.layout.list_footer_replays, binding.list, false)
        moreButton = footerView.findViewById(R.id.more_button)
        moreButton.setOnClickListener { requestPage(currentPage + 1, append = true) }
        footerView.isVisible = false
        binding.list.addFooterView(footerView, null, false)
        listAdapter = ReplayListAdapter(layoutInflater)
        binding.list.adapter = listAdapter
        binding.list.onItemClickListener = this
        binding.list.setOnTouchListener(NestedScrollLikeTouchListener())
        updateFormatLabel()

        if (!hasRequested) {
            hasRequested = true
            lastUsernames = emptyList()
            lastFormat = ""
        }
        requestPage(1, append = false)
    }

    override fun onStart() {
        super.onStart()
        requireDialog().resizeForIme()
        val fullScreen = !resources.getBoolean(R.bool.canUseLandscapeLayout)
        val width = if (fullScreen) ViewGroup.LayoutParams.MATCH_PARENT
        else resources.getDimensionPixelSize(R.dimen.dialog_max_width)
        val height = if (fullScreen) ViewGroup.LayoutParams.MATCH_PARENT else min(
                resources.displayMetrics.heightPixels * 85 / 100,
                resources.getDimensionPixelSize(R.dimen.search_dialog_max_height))
        dialog?.window?.apply {
            setLayout(width, height)
            if (fullScreen) {
                configureEdgeToEdge(resources)
                binding.root.applySafeDrawingInsets(includeIme = true)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_FORMAT_ID, selectedFormatId)
        outState.putString(STATE_INPUT_USERS, _binding?.userInput?.text?.toString().orEmpty())
        outState.putStringArrayList(STATE_LAST_USERS, ArrayList(lastUsernames))
        outState.putString(STATE_LAST_FORMAT, lastFormat)
        outState.putBoolean(STATE_HAS_REQUESTED, hasRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    fun onBattleFormatsChanged() {
        if (_binding == null) return
        if (selectedFormatId != BattleFormat.FORMAT_ALL.id &&
                availableFormats().none { category -> category.formats.any { it.id == selectedFormatId } }) {
            selectedFormatId = BattleFormat.FORMAT_ALL.id
        }
        updateFormatLabel()
    }

    private fun submitSearch() {
        if (loading) return
        val usernames = normalizeReplayUsernames(binding.userInput.text?.toString().orEmpty())
        if (usernames == null) {
            binding.userInputContainer.error = getString(R.string.replay_too_many_users)
            return
        }
        binding.userInputContainer.error = null
        lastUsernames = usernames
        lastFormat = selectedFormatId.takeUnless { it == BattleFormat.FORMAT_ALL.id }.orEmpty()
        hideKeyboard()
        requestPage(1, append = false)
    }

    private fun requestPage(page: Int, append: Boolean) {
        if (loading) return
        val service = homeFragment.mainActivity.service
        if (service == null) {
            showError(keepResults = append && listAdapter.count > 0)
            return
        }
        loading = true
        binding.searchButton.isEnabled = false
        binding.searchButton.setText(R.string.searching)
        if (append) {
            moreButton.isEnabled = false
            moreButton.setText(R.string.loading_more)
        } else {
            binding.resultsCount.setText(R.string.replay_search_loading)
            binding.list.isVisible = false
            binding.stateMessage.isVisible = false
            binding.progress.isVisible = true
            footerView.isVisible = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val response = service.retrieveReplayList(lastUsernames, lastFormat, page)
            val entries = response?.let(::parseReplayList)
            if (entries == null) {
                finishLoading()
                showError(keepResults = append && listAdapter.count > 0)
                return@launch
            }

            val result = replayPage(entries)
            if (append) listAdapter.addItems(result.items) else listAdapter.replaceItems(result.items)
            currentPage = page
            val paginationSupported = lastUsernames.isNotEmpty() || lastFormat.isNotBlank()
            footerView.isVisible = paginationSupported && result.hasMore
            finishLoading()
            if (listAdapter.count == 0) showEmpty() else showResults()
        }
    }

    private fun finishLoading() {
        loading = false
        binding.searchButton.isEnabled = true
        binding.searchButton.setText(R.string.search)
        moreButton.isEnabled = true
        moreButton.setText(R.string.more)
        binding.progress.isVisible = false
    }

    private fun showResults() {
        binding.resultsCount.text = resources.getQuantityString(
                R.plurals.replay_results_count, listAdapter.count, listAdapter.count)
        binding.stateMessage.isVisible = false
        binding.list.isVisible = true
    }

    private fun showEmpty() {
        binding.resultsCount.text = resources.getQuantityString(R.plurals.replay_results_count, 0, 0)
        binding.list.isVisible = false
        binding.stateMessage.setText(R.string.replay_search_empty)
        binding.stateMessage.isVisible = true
    }

    private fun showError(keepResults: Boolean) {
        if (loading) finishLoading()
        binding.progress.isVisible = false
        if (keepResults) {
            binding.resultsCount.setText(R.string.replay_search_error)
            binding.list.isVisible = true
            binding.stateMessage.isVisible = false
        } else {
            binding.resultsCount.text = ""
            binding.list.isVisible = false
            binding.stateMessage.setText(R.string.replay_search_error)
            binding.stateMessage.isVisible = true
        }
    }

    private fun showFormatPicker() {
        val formats = availableFormats()
        if (formats.isEmpty()) {
            binding.stateMessage.setText(R.string.no_formats_available)
            binding.stateMessage.isVisible = true
            return
        }
        if (childFragmentManager.findFragmentByTag(FormatPickerDialogFragment.TAG) == null) {
            FormatPickerDialogFragment.newInstance(
                    formats, selectedFormatId, mode = FormatPickerMode.ALL, includeAll = true)
                    .show(childFragmentManager, FormatPickerDialogFragment.TAG)
        }
    }

    private fun availableFormats(): List<BattleFormat.Category> =
            (homeFragment.mainActivity.service
                    ?.getSharedData<List<BattleFormat.Category>>("formats")
                    ?.takeIf(List<*>::isNotEmpty))?.also { cachedFormats = it }
                    ?: cachedFormats.takeIf(List<*>::isNotEmpty)
                    ?: BattleFormatCache(requireContext()).get().also { cachedFormats = it }

    private fun updateFormatLabel() {
        binding.formatButton.text = if (selectedFormatId == BattleFormat.FORMAT_ALL.id)
            getString(R.string.all_formats)
        else BattleFormat.resolveName(availableFormats(), selectedFormatId)
    }

    private fun hideKeyboard() {
        binding.userInput.clearFocus()
        val keyboard = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (position >= listAdapter.count) return
        homeFragment.startReplay(listAdapter.getItem(position).id)
        dismiss()
    }

    private fun relativeTime(uploadTime: Long): String {
        val elapsed = (System.currentTimeMillis() / 1000L - uploadTime).coerceAtLeast(0L)
        val (quantity, plurals) = when {
            elapsed < 3600L -> max(1L, elapsed / 60L) to R.plurals.minutes_ago
            elapsed < 86400L -> elapsed / 3600L to R.plurals.hours_ago
            else -> elapsed / 86400L to R.plurals.days_ago
        }
        return resources.getQuantityString(plurals, quantity.toInt(), quantity)
    }

    private inner class ReplayListAdapter(private val inflater: LayoutInflater) : BaseAdapter() {
        private val items = mutableListOf<ReplayInfo>()

        fun replaceItems(newItems: List<ReplayInfo>) {
            items.clear()
            addItems(newItems)
        }

        fun addItems(newItems: List<ReplayInfo>) {
            val merged = appendUniqueReplays(items, newItems)
            items.clear()
            items.addAll(merged)
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = 0L

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: inflater.inflate(R.layout.list_item_search_result, parent, false)
            val replay = getItem(position)
            row.findViewById<TextView>(android.R.id.text1).text =
                    replay.p1.bold() concat " vs. " concat replay.p2.bold()
            val details = listOfNotNull(
                    replay.format.takeIf(String::isNotBlank),
                    replay.rating?.let { getString(R.string.rating_label, it) },
                    replay.uploadTime.takeIf { it > 0L }?.let(::relativeTime)).joinToString(" · ")
            row.findViewById<TextView>(android.R.id.text2).text = details
            return row
        }
    }

    companion object {
        const val FRAGMENT_TAG = "search-replay-dialog"
        private const val STATE_FORMAT_ID = "format-id"
        private const val STATE_INPUT_USERS = "input-users"
        private const val STATE_LAST_USERS = "last-users"
        private const val STATE_LAST_FORMAT = "last-format"
        private const val STATE_HAS_REQUESTED = "has-requested"
    }
}
