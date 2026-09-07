package com.majeur.psclient.ui.teambuilder

import android.content.Context
import android.os.Bundle
import android.text.Spannable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.majeur.psclient.R
import com.majeur.psclient.databinding.ListItemMoveBinding
import com.majeur.psclient.io.AssetLoader
import com.majeur.psclient.model.common.FormatProfile
import com.majeur.psclient.model.common.Type
import com.majeur.psclient.ui.BaseFragment
import com.majeur.psclient.util.CategoryDrawable
import com.majeur.psclient.util.Utils
import com.majeur.psclient.util.italic
import com.majeur.psclient.util.recyclerview.OnItemClickListener
import com.majeur.psclient.util.toId
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MovesFragment : ListFragment(), OnItemClickListener {

    private val fragmentScope = BaseFragment.FragmentScope()
    private lateinit var assetLoader: AssetLoader
    private lateinit var species: String
    private lateinit var profile: FormatProfile
    private var slot = 0
    private var selectedMove = ""

    override fun onAttach(context: Context) {
        super.onAttach(context)
        assetLoader = (context as TeamBuilderActivity).assetLoader
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        species = requireArguments().getString(ARG_SPECIES)!!
        profile = FormatProfile.from(requireArguments().getString(ARG_FORMAT).orEmpty())
        slot = requireArguments().getInt(ARG_SLOT)
        selectedMove = requireArguments().getString(ARG_SELECTED_MOVE).orEmpty()
        lifecycle.addObserver(fragmentScope)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycle.removeObserver(fragmentScope)
    }

    override fun onQueryTextChange(query: String): Boolean {
        (requireAdapter() as Adapter).filter(query)
        return true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentScope.launch {
            val moves = withHiddenPowerVariants(assetLoader.learnset(species).orEmpty(),
                    profile.supportsHiddenPower(species))
            val adapterItems = listOf("None") + moves
            val textHighlightColor = Utils.alphaColor(ContextCompat.getColor(requireContext(), R.color.secondary), 0.45f)
            setAdapter(Adapter(adapterItems, this@MovesFragment, textHighlightColor))
            val selectedPosition = if (selectedMove.isBlank()) 0
            else adapterItems.indexOfFirst { it.toId() == selectedMove.toId() }
            if (selectedPosition > 0)
                (recyclerView.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(selectedPosition, 0)
        }
    }

    override fun onItemClick(itemView: View, holder: RecyclerView.ViewHolder, position: Int) {
        val moveId = (requireAdapter() as Adapter).getItem(position)
        val bundle = bundleOf(
                RESULT_MOVE to moveId,
                RESULT_SLOT to slot
        )
        setFragmentResult(RESULT_KEY, bundle)
        findNavController().navigateUp()
    }

    inner class Adapter(
            private val baseList: List<String>,
            private val itemClickListener: OnItemClickListener,
            private val highlightColor: Int
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {

        private var adapterList = baseList
        private var filteringConstraint = ""

        inner class ViewHolder(
                val binding: ListItemMoveBinding,
                var job: Job? = null
        ) : RecyclerView.ViewHolder(binding.root), View.OnClickListener {

            init {
                binding.root.setOnClickListener(this)
            }

            override fun onClick(view: View?) {
                itemClickListener.onItemClick(binding.root, this, layoutPosition)
            }

        }

        fun filter(constraint: String) {
            filteringConstraint = constraint
            val queryId = constraint.toId()
            adapterList = baseList.filter { it.toId().contains(queryId) }
            notifyDataSetChanged()
        }

        fun getItem(position: Int) = adapterList[position]

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ListItemMoveBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val moveId = getItem(position)
            val isNoneItem = moveId == "None"

            holder.binding.apply {
                //root.animate().cancel()
                //root.alpha = 0f
                nameView.text = ""
                detailsView.text = ""
                typeView.setImageDrawable(null)
                categoryView.setImageDrawable(null)
            }

            holder.job?.cancel()
            if (isNoneItem) {
                holder.binding.apply {
                    nameView.text = moveId.italic()
                    //root.alpha = 1f
                }
                return
            }
            holder.job = fragmentScope.launch {
                val details = assetLoader.moveDetails(moveId) ?: return@launch
                holder.binding.apply {
                    nameView.setText(details.name, TextView.BufferType.SPANNABLE)
                    highlightMatch(nameView)
                    detailsView.text = buildDetailsText(details.pp, details.basePower, details.accuracy)
                    detailsView.append("\n")
                    detailsView.append(details.desc?.italic() ?: "No description".italic())
                    typeView.setImageResource(Type.getResId(details.type))
                    categoryView.setImageDrawable(CategoryDrawable(details.category))
                    //root.animate().alpha(1f).setDuration(100L).start()
                }
            }
        }

        override fun getItemCount() = adapterList.size

        private fun highlightMatch(textView: TextView) {
            val constraint = filteringConstraint
            if (constraint.isBlank()) return
            val startIndex = textView.text.toString().indexOf(constraint, ignoreCase = true)
            if (startIndex < 0) return
            val endIndex = startIndex + constraint.length
            val spannable = textView.text as Spannable
            spannable.setSpan(BackgroundColorSpan(highlightColor), startIndex, endIndex,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun buildDetailsText(pp: Int, bp: Int, acc: Int): CharSequence {
            return "PP: " + (if (pp >= 0) pp else "–") + ", BP: " + (if (bp > 0) bp else "–") + ", AC: " + if (acc > 0) acc else "–"
        }

    }

    companion object {

        const val ARG_SPECIES = "arg-species"
        const val ARG_FORMAT = "arg-format"
        const val ARG_SLOT = "arg-slot"
        const val ARG_SELECTED_MOVE = "arg-selected-move"

        const val RESULT_KEY = "request-result-move"
        const val RESULT_MOVE = "result-move"
        const val RESULT_SLOT = "result-slot"

        internal fun withHiddenPowerVariants(moves: List<String>, hasHiddenPower: Boolean) =
                moves.flatMap { move ->
                    if (move.toId() != "hiddenpower") listOf(move)
                    else if (!hasHiddenPower) emptyList()
                    else listOf(move) + Type.HP_TYPES.map(Type::hiddenPowerMoveId)
                }

    }

}
