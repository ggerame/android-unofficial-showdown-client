package com.majeur.psclient.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.majeur.psclient.R
import com.majeur.psclient.databinding.DialogFormatSelectorBinding
import com.majeur.psclient.model.common.BattleFormat
import com.majeur.psclient.util.applySafeDrawingInsets
import com.majeur.psclient.util.configureEdgeToEdge
import com.majeur.psclient.util.resizeForIme
import com.majeur.psclient.widget.CategoryAdapter
import java.io.Serializable

internal fun filterFormatCategories(
        categories: List<Pair<BattleFormat.Category, List<BattleFormat>>>,
        rawQuery: String
): List<Pair<BattleFormat.Category, List<BattleFormat>>> {
    val query = rawQuery.trim()
    if (query.isEmpty()) return categories
    return categories.mapNotNull { (category, formats) ->
        val sectionIndex = category.label.indexOf(query, ignoreCase = true)
        val sectionMatches = sectionIndex >= 0 &&
                (sectionIndex == 0 || !category.label[sectionIndex - 1].isLetterOrDigit())
        val matches = if (sectionMatches) formats else formats.filter {
            it.label.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
        }
        if (matches.isEmpty()) null else category to matches
    }
}

enum class FormatPickerMode { TEAM, SEARCH, ALL }

internal fun formatsForMode(
        categories: List<BattleFormat.Category>,
        mode: FormatPickerMode
): List<Pair<BattleFormat.Category, List<BattleFormat>>> = categories.mapNotNull { category ->
    val formats = category.formats.filter {
        when (mode) {
            FormatPickerMode.TEAM -> it.isTeamNeeded
            FormatPickerMode.SEARCH -> it.isSearchShow
            FormatPickerMode.ALL -> true
        }
    }
    if (formats.isEmpty()) null else category to formats
}

class FormatPickerDialogFragment : DialogFragment() {

    private lateinit var binding: DialogFormatSelectorBinding
    private lateinit var categories: List<Pair<BattleFormat.Category, List<BattleFormat>>>
    private val expanded = mutableSetOf<BattleFormat.Category>()
    private var query = ""

    private val adapter by lazy {
        object : CategoryAdapter(requireContext()) {
            override fun isCategoryItem(position: Int) = getItem(position) is BattleFormat.Category
            override fun isCategoryEnabled(position: Int) = true
            override fun getCategoryLabel(position: Int): String {
                val category = getItem(position) as BattleFormat.Category
                val open = query.isNotBlank() || category in expanded
                return "${if (open) "▾" else "▸"}  ${category.label}"
            }
            override fun getItemLabel(position: Int) = (getItem(position) as BattleFormat).label
        }
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = arguments?.getSerializable(ARG_CATEGORIES) as? List<BattleFormat.Category> ?: emptyList()
        val mode = arguments?.getString(ARG_MODE)?.let(FormatPickerMode::valueOf)
                ?: FormatPickerMode.TEAM
        categories = formatsForMode(source, mode).toMutableList().apply {
            if (requireArguments().getBoolean(ARG_INCLUDE_ALL)) {
                val all = BattleFormat.Category(getString(R.string.format_section_all)).apply {
                    formats += BattleFormat.FORMAT_ALL
                }
                add(0, all to all.formats)
            }
            if (requireArguments().getBoolean(ARG_INCLUDE_OTHER)) {
                val other = BattleFormat.Category(getString(R.string.format_section_other)).apply {
                    formats += BattleFormat.FORMAT_OTHER
                }
                add(other to other.formats)
            }
        }
        query = savedInstanceState?.getString(STATE_QUERY).orEmpty()
        val expandedLabel = savedInstanceState?.getString(STATE_EXPANDED)
        val selectedId = arguments?.getString(ARG_SELECTED_ID)
        val initial = categories.firstOrNull { (category, formats) ->
            category.label == expandedLabel || formats.any { it.id == selectedId }
        }?.first ?: categories.firstOrNull()?.first
        if (initial != null) expanded += initial
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogFormatSelectorBinding.inflate(layoutInflater)
        binding.formatsList.adapter = adapter
        binding.formatsList.emptyView = binding.emptyFormats
        binding.formatsList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            when (val selected = adapter.getItem(position)) {
                is BattleFormat.Category -> {
                    if (query.isNotBlank()) return@OnItemClickListener
                    if (!expanded.remove(selected)) {
                        expanded.clear()
                        expanded += selected
                    }
                    refreshRows()
                }
                is BattleFormat -> {
                    parentFragmentManager.setFragmentResult(
                            RESULT_KEY, bundleOf(RESULT_FORMAT_ID to selected.id))
                    dismiss()
                }
            }
        }
        binding.formatSearch.doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            refreshRows()
            binding.formatsList.setSelection(0)
        }
        binding.formatSearch.setText(query)
        refreshRows()
        val fullScreen = !resources.getBoolean(R.bool.canUseLandscapeLayout)
        binding.toolbar.isVisible = fullScreen
        if (fullScreen) {
            binding.toolbar.setNavigationOnClickListener { dismiss() }
            return Dialog(requireContext(), R.style.Theme_PSClient_FullScreenDialog).apply {
                setContentView(binding.root)
            }
        }
        return MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.choose_format)
                .setView(binding.root)
                .setNegativeButton(R.string.cancel, null)
                .create()
    }

    override fun onStart() {
        super.onStart()
        requireDialog().resizeForIme()
        val fullScreen = !resources.getBoolean(R.bool.canUseLandscapeLayout)
        val width = if (fullScreen) ViewGroup.LayoutParams.MATCH_PARENT
        else resources.getDimensionPixelSize(R.dimen.dialog_max_width)
        dialog?.window?.apply {
            setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT)
            if (fullScreen) {
                configureEdgeToEdge(resources)
                binding.root.applySafeDrawingInsets(includeIme = true)
            }
        }
        binding.formatSearch.clearFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_QUERY, query)
        outState.putString(STATE_EXPANDED, expanded.firstOrNull()?.label)
        super.onSaveInstanceState(outState)
    }

    private fun refreshRows() {
        val rows = mutableListOf<Any>()
        filterFormatCategories(categories, query).forEach { (category, formats) ->
            rows.add(category)
            if (query.isNotBlank() || category in expanded) rows.addAll(formats)
        }
        adapter.replaceItems(rows)
    }

    companion object {
        const val TAG = "format-picker"
        const val RESULT_KEY = "format-picker-result"
        const val RESULT_FORMAT_ID = "format-id"
        private const val ARG_CATEGORIES = "categories"
        private const val ARG_SELECTED_ID = "selected-id"
        private const val ARG_INCLUDE_OTHER = "include-other"
        private const val ARG_INCLUDE_ALL = "include-all"
        private const val ARG_MODE = "mode"
        private const val STATE_QUERY = "query"
        private const val STATE_EXPANDED = "expanded"

        fun newInstance(categories: List<BattleFormat.Category>, selectedId: String? = null,
                        includeOther: Boolean = false, mode: FormatPickerMode = FormatPickerMode.TEAM,
                        includeAll: Boolean = false) =
                FormatPickerDialogFragment().apply {
            arguments = bundleOf(
                    ARG_CATEGORIES to ArrayList(categories) as Serializable,
                    ARG_SELECTED_ID to selectedId,
                    ARG_INCLUDE_OTHER to includeOther,
                    ARG_INCLUDE_ALL to includeAll,
                    ARG_MODE to mode.name)
        }
    }
}
