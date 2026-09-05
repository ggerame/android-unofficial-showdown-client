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
import com.majeur.psclient.R
import com.majeur.psclient.databinding.DialogSearchBattleBinding
import com.majeur.psclient.io.BattleFormatCache
import com.majeur.psclient.model.BattleRoomInfo
import com.majeur.psclient.model.common.BattleFormat
import com.majeur.psclient.util.NestedScrollLikeTouchListener
import com.majeur.psclient.util.applySafeDrawingInsets
import com.majeur.psclient.util.bold
import com.majeur.psclient.util.concat
import com.majeur.psclient.util.configureEdgeToEdge
import com.majeur.psclient.util.resizeForIme
import com.majeur.psclient.util.toId
import kotlin.math.min

internal data class BattleSearchFilters(
        val formatId: String,
        val username: String,
        val minElo: Int)

class SearchBattleDialog : DialogFragment(), AdapterView.OnItemClickListener {

    private var _binding: DialogSearchBattleBinding? = null
    private val binding get() = _binding!!
    private val homeFragment get() = parentFragment as HomeFragment

    private lateinit var listAdapter: BattleListAdapter
    private var selectedFormatId = BattleFormat.FORMAT_ALL.id
    private var selectedMinEloIndex = 0
    private var lastFormat = ""
    private var lastUsername = ""
    private var lastMinElo = 0
    private var hasRequested = false
    private var loading = false
    private var requestTimeout: Runnable? = null
    private var cachedFormats = emptyList<BattleFormat.Category>()

    private val eloValues = intArrayOf(0, 1100, 1300, 1500, 1700, 1900)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fullScreen = !resources.getBoolean(R.bool.canUseLandscapeLayout)
        setStyle(STYLE_NO_TITLE, if (fullScreen) R.style.Theme_PSClient_FullScreenDialog else 0)
        selectedFormatId = savedInstanceState?.getString(STATE_FORMAT_ID)
                ?: arguments?.getString(ARG_FORMAT_ID)
                ?: BattleFormat.FORMAT_ALL.id
        selectedMinEloIndex = savedInstanceState?.getInt(STATE_MIN_ELO_INDEX)
                ?: eloValues.indexOf(arguments?.getInt(ARG_MIN_ELO) ?: 0).coerceAtLeast(0)
        lastFormat = savedInstanceState?.getString(STATE_LAST_FORMAT)
                ?: arguments?.getString(ARG_FORMAT_ID).orEmpty()
                        .takeUnless { it == BattleFormat.FORMAT_ALL.id }.orEmpty()
        lastUsername = savedInstanceState?.getString(STATE_LAST_USERNAME)
                ?: arguments?.getString(ARG_USERNAME).orEmpty()
        lastMinElo = savedInstanceState?.getInt(STATE_LAST_MIN_ELO)
                ?: arguments?.getInt(ARG_MIN_ELO) ?: 0
        hasRequested = savedInstanceState?.getBoolean(STATE_HAS_REQUESTED)
                ?: (arguments != null)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogSearchBattleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { dismiss() }
        binding.userInput.setText(savedInstanceState?.getString(STATE_INPUT_USERNAME)
                ?: arguments?.getString(ARG_USERNAME).orEmpty())
        binding.userInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else false
        }

        val eloLabels = arrayOf(
                getString(R.string.no_minimum_elo), "1100", "1300", "1500", "1700", "1900")
        selectedMinEloIndex = selectedMinEloIndex.coerceIn(eloValues.indices)
        binding.minEloSelector.setSimpleItems(eloLabels)
        binding.minEloSelector.setText(eloLabels[selectedMinEloIndex], false)
        binding.minEloSelector.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            selectedMinEloIndex = position
        }

        childFragmentManager.setFragmentResultListener(
                FormatPickerDialogFragment.RESULT_KEY, viewLifecycleOwner) { _, result ->
            selectedFormatId = result.getString(FormatPickerDialogFragment.RESULT_FORMAT_ID)
                    ?: return@setFragmentResultListener
            updateFormatLabel()
        }
        binding.formatButton.setOnClickListener { showFormatPicker() }
        binding.searchButton.setOnClickListener { submitSearch() }

        listAdapter = BattleListAdapter(layoutInflater, ::formatLabel)
        binding.list.adapter = listAdapter
        binding.list.onItemClickListener = this
        binding.list.setOnTouchListener(NestedScrollLikeTouchListener())
        updateFormatLabel()

        if (!hasRequested) {
            hasRequested = true
            lastFormat = ""
            lastUsername = ""
            lastMinElo = 0
        }
        requestBattles(lastFormat, lastUsername, lastMinElo)
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
        outState.putInt(STATE_MIN_ELO_INDEX, selectedMinEloIndex)
        outState.putString(STATE_INPUT_USERNAME, _binding?.userInput?.text?.toString().orEmpty())
        outState.putString(STATE_LAST_FORMAT, lastFormat)
        outState.putString(STATE_LAST_USERNAME, lastUsername)
        outState.putInt(STATE_LAST_MIN_ELO, lastMinElo)
        outState.putBoolean(STATE_HAS_REQUESTED, hasRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        requestTimeout?.let(binding.root::removeCallbacks)
        requestTimeout = null
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

    fun onSearchBattleResponse(battles: List<BattleRoomInfo>?) {
        if (_binding == null || !loading) return
        finishLoading()
        when {
            battles == null -> showError()
            battles.isEmpty() -> showEmpty()
            else -> {
                listAdapter.replaceItems(battles)
                binding.resultsCount.text = resources.getQuantityString(
                        R.plurals.battle_results_count, battles.size, battles.size)
                binding.progress.isVisible = false
                binding.stateMessage.isVisible = false
                binding.list.isVisible = true
            }
        }
    }

    private fun submitSearch() {
        if (loading) return
        lastFormat = selectedFormatId.takeUnless { it == BattleFormat.FORMAT_ALL.id }.orEmpty()
        lastUsername = binding.userInput.text?.toString().orEmpty().toId()
        lastMinElo = eloValues[selectedMinEloIndex]
        hideKeyboard()
        requestBattles(lastFormat, lastUsername, lastMinElo)
    }

    private fun requestBattles(format: String, username: String, minElo: Int) {
        if (loading) return
        val service = homeFragment.mainActivity.service
        if (service == null || !service.isConnected) {
            showError()
            return
        }
        loading = true
        binding.searchButton.isEnabled = false
        binding.searchButton.setText(R.string.searching)
        binding.resultsCount.setText(R.string.battle_search_loading)
        binding.list.isVisible = false
        binding.stateMessage.isVisible = false
        binding.progress.isVisible = true
        listAdapter.replaceItems(emptyList())
        service.sendGlobalCommand(
                "cmd roomlist", format, minElo.takeIf { it > 0 }?.toString().orEmpty(), username)
        requestTimeout = Runnable {
            if (_binding != null && loading) {
                finishLoading()
                showError()
            }
        }.also { binding.root.postDelayed(it, REQUEST_TIMEOUT_MS) }
    }

    private fun finishLoading() {
        requestTimeout?.let(binding.root::removeCallbacks)
        requestTimeout = null
        loading = false
        binding.searchButton.isEnabled = true
        binding.searchButton.setText(R.string.search)
    }

    private fun showEmpty() {
        binding.resultsCount.text = resources.getQuantityString(R.plurals.battle_results_count, 0, 0)
        binding.progress.isVisible = false
        binding.list.isVisible = false
        binding.stateMessage.setText(R.string.battle_search_empty)
        binding.stateMessage.isVisible = true
    }

    private fun showError() {
        if (loading) finishLoading()
        binding.resultsCount.text = ""
        binding.progress.isVisible = false
        binding.list.isVisible = false
        binding.stateMessage.setText(R.string.battle_search_error)
        binding.stateMessage.isVisible = true
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

    private fun formatLabel(roomId: String): String {
        val formatId = roomId.removePrefix("battle-").substringBeforeLast('-', "")
        return BattleFormat.resolveName(availableFormats(), formatId).ifBlank { formatId }
    }

    private fun hideKeyboard() {
        binding.userInput.clearFocus()
        val keyboard = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (position >= listAdapter.count) return
        homeFragment.joinRoomFromBattleSearch(listAdapter.getItem(position).roomId,
                BattleSearchFilters(
                        lastFormat.ifEmpty { BattleFormat.FORMAT_ALL.id },
                        lastUsername,
                        lastMinElo))
        dismiss()
    }

    private inner class BattleListAdapter(
            private val inflater: LayoutInflater,
            private val resolveFormat: (String) -> String
    ) : BaseAdapter() {
        private val items = mutableListOf<BattleRoomInfo>()

        fun replaceItems(newItems: List<BattleRoomInfo>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = 0L

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: inflater.inflate(R.layout.list_item_search_result, parent, false)
            val battle = getItem(position)
            row.findViewById<TextView>(android.R.id.text1).text =
                    battle.p1.bold() concat " vs. " concat battle.p2.bold()
            val details = listOfNotNull(
                    resolveFormat(battle.roomId).takeIf(String::isNotBlank),
                    battle.rating?.let { rating ->
                        rating.toIntOrNull()?.let { getString(R.string.rating_label, it.toString()) } ?: rating
                    }).joinToString(" · ")
            row.findViewById<TextView>(android.R.id.text2).text = details
            return row
        }
    }

    companion object {
        const val FRAGMENT_TAG = "search-battle-dialog"
        private const val ARG_FORMAT_ID = "format-id"
        private const val ARG_USERNAME = "username"
        private const val ARG_MIN_ELO = "min-elo"
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val STATE_FORMAT_ID = "format-id"
        private const val STATE_MIN_ELO_INDEX = "min-elo-index"
        private const val STATE_INPUT_USERNAME = "input-username"
        private const val STATE_LAST_FORMAT = "last-format"
        private const val STATE_LAST_USERNAME = "last-username"
        private const val STATE_LAST_MIN_ELO = "last-min-elo"
        private const val STATE_HAS_REQUESTED = "has-requested"

        internal fun newInstance(filters: BattleSearchFilters? = null) = SearchBattleDialog().apply {
            if (filters != null) arguments = Bundle().apply {
                putString(ARG_FORMAT_ID, filters.formatId)
                putString(ARG_USERNAME, filters.username)
                putInt(ARG_MIN_ELO, filters.minElo)
            }
        }
    }
}
