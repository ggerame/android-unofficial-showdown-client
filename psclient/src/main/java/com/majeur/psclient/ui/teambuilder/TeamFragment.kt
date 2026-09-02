package com.majeur.psclient.ui.teambuilder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.majeur.psclient.R
import com.majeur.psclient.databinding.FragmentTbTeamBinding
import com.majeur.psclient.databinding.ListItemPokemonBinding
import com.majeur.psclient.io.GlideHelper
import com.majeur.psclient.model.common.BattleFormat
import com.majeur.psclient.model.common.Nature
import com.majeur.psclient.model.common.TeamDraftValidator
import com.majeur.psclient.model.common.toId
import com.majeur.psclient.model.pokemon.TeamPokemon
import com.majeur.psclient.ui.BaseFragment
import com.majeur.psclient.ui.FormatPickerDialogFragment
import com.majeur.psclient.util.*
import com.majeur.psclient.util.recyclerview.ItemTouchHelperCallbacks
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.*


class TeamFragment : Fragment() {

    lateinit var glideHelper: GlideHelper
    private val fragmentScope = BaseFragment.FragmentScope()

    private val assetLoader by lazy { (requireActivity() as TeamBuilderActivity).assetLoader }

    // See TeamBuilderActivity field declaration comment
    private val team get() = (requireActivity() as TeamBuilderActivity).team
    private var lastRemovedPokemon: TeamPokemon? = null
    private var selectedFormat: BattleFormat = BattleFormat.FORMAT_OTHER

    private var _binding: FragmentTbTeamBinding? = null
    private val binding get() = _binding!!

    override fun onAttach(context: Context) {
        super.onAttach(context)
        glideHelper = (context as TeamBuilderActivity).glideHelper
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        lifecycle.addObserver(fragmentScope)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycle.removeObserver(fragmentScope)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.options_menu_tb_team, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_done) {
            buildTeamAndFinish()
            return true
        }
        return false
    }

    private fun buildTeamAndFinish() {
        val name = binding.header.teamNameInput.text.toString()
        if (name.isBlank()) {
            Snackbar.make(binding.root, "Team name is empty", Snackbar.LENGTH_LONG)
                    .setAction("Use default") {
                        binding.header.teamNameInput.setText("Unnamed team")
                    }
                    .show()
            return
        }

        val battleFormat = selectedFormat
        team.apply {
            label = name
            pokemons = team.pokemons.filter { it.species.isNotBlank() }
            pokemons.forEach { p -> p.moves = p.moves.filter { it.isNotBlank() } }
            format = battleFormat.toId()
        }

        val issues = TeamDraftValidator.validate(team, battleFormat)
        if (issues.isNotEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Save as draft?")
                    .setMessage(issues.joinToString("\n") { "• $it" })
                    .setPositiveButton("Save draft") { _, _ -> finishWithTeam() }
                    .setNegativeButton("Keep editing", null)
                    .show()
            return
        }
        finishWithTeam()
    }

    private fun finishWithTeam() {
        val data = Intent()
        data.putExtra(TeamBuilderActivity.INTENT_EXTRA_TEAM, team)
        requireActivity().apply {
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        _binding = FragmentTbTeamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = PokemonListAdapter(team.pokemons as MutableList<TeamPokemon>) // We know it's a MutableList (see TeamBuilderActivity)
        binding.list.apply {
            this.adapter = adapter
            checkFabVisibility()
            ItemTouchHelper(object : ItemTouchHelperCallbacks(context, allowReordering = true, allowDeletion = true) {

                override fun onMoveItem(from: Int, to: Int) = adapter.moveItem(from, to)

                override fun onRemoveItem(position: Int) = adapter.removeItem(position)

            }).attachToRecyclerView(this)
            itemAnimator = DefaultItemAnimator()

            postponeEnterTransition()
            viewTreeObserver.addOnPreDrawListener {
                startPostponedEnterTransition()
                true
            }
        }
        binding.fab.apply {
            binding.fab.setOnClickListener {
                adapter.addItem(TeamPokemon().apply {
                    moves = mutableListOf("", "", "", "") // Ensure we have a 4 items mutable list
                    val format = (requireActivity() as TeamBuilderActivity).currentFormat
                    level = format?.defaultLevel ?: 100
                    if ((format?.profile?.generation ?: 9) <= 2) {
                        ivs = com.majeur.psclient.model.common.Stats(15)
                        evs = com.majeur.psclient.model.common.Stats(252)
                    }
                })
                val lastItemPosition = adapter.itemCount - 1
                binding.list.scrollToPosition(lastItemPosition)
                binding.list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) {
                        val position = binding.list.getChildLayoutPosition(view)
                        if (position == lastItemPosition) view.performClick()
                        binding.list.removeOnChildAttachStateChangeListener(this)
                    }
                    override fun onChildViewDetachedFromWindow(view: View) {}
                })
            }
        }
        binding.header.teamNameInput.apply {
            setText(if (team.label.isBlank()) "Unnamed team" else team.label)
        }
        val activity = requireActivity() as TeamBuilderActivity
        selectedFormat = activity.formats.asSequence().flatMap { it.formats.asSequence() }
                .firstOrNull { it.id == team.format } ?: BattleFormat.FORMAT_OTHER
        binding.header.formatInput.setText(selectedFormat.label)
        parentFragmentManager.setFragmentResultListener(
                FormatPickerDialogFragment.RESULT_KEY, viewLifecycleOwner) { _, result ->
            val formatId = result.getString(FormatPickerDialogFragment.RESULT_FORMAT_ID)
                    ?: return@setFragmentResultListener
            val candidate = activity.formats.asSequence().flatMap { it.formats.asSequence() }
                    .firstOrNull { it.id == formatId } ?: BattleFormat.FORMAT_OTHER
            if (candidate.id == selectedFormat.id) return@setFragmentResultListener
            MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Change team format?")
                    .setMessage("Existing sets will be preserved, but incompatible fields will make this team a draft.")
                    .setPositiveButton("Change") { _, _ ->
                        selectedFormat = candidate
                        team.format = candidate.id
                        binding.header.formatInput.setText(candidate.label)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
        }
        val openFormatPicker = View.OnClickListener {
            FormatPickerDialogFragment.newInstance(activity.formats, selectedFormat.id, includeOther = true)
                    .show(parentFragmentManager, FormatPickerDialogFragment.TAG)
        }
        binding.header.formatInput.setOnClickListener(openFormatPicker)
        binding.header.formatInputLayout.setEndIconOnClickListener { openFormatPicker.onClick(it) }
    }

    val onListItemClickListener = View.OnClickListener { view ->
        val pokemon = view.tag as TeamPokemon
        val index = team.pokemons.indexOf(pokemon)
        val bundle = bundleOf(
                PokemonFragment.ARG_SLOT_INDEX to index
        )
        val extras = FragmentNavigatorExtras(view to "content_$index")
        findNavController().navigate(R.id.action_team_frag_to_pokemon_frag, bundle, null, extras)
    }

    private fun checkFabVisibility() {
        val adapter = binding.list.adapter as PokemonListAdapter? ?: return
        if (adapter.itemCount != 0 && (adapter.lastItem().species.isBlank() || adapter.itemCount >= TeamBuilderActivity.MAX_TEAM_SIZE))
            binding.fab.hide()
        else
            binding.fab.show()
    }

    inner class PokemonListAdapter(
            private val adapterList: MutableList<TeamPokemon>)
        : RecyclerView.Adapter<PokemonListAdapter.ItemViewHolder>() {

        fun addItem(poke: TeamPokemon, at: Int = -1) {
            if (at < 0) adapterList.add(poke) else adapterList.add(at, poke)
            notifyItemInserted(adapterList.indexOf(poke))
            checkFabVisibility()
        }

        fun removeItem(at: Int) {
            lastRemovedPokemon = adapterList.removeAt(at)
            notifyItemRemoved(at)
            if (lastRemovedPokemon!!.species.isNotBlank())
                Snackbar.make(binding.root, "${lastRemovedPokemon!!.species} removed", Snackbar.LENGTH_LONG)
                    .setAction("Undo") {
                        addItem(lastRemovedPokemon!!, at = at)
                        lastRemovedPokemon = null
                    }
                    .show()
            checkFabVisibility()
        }

        fun moveItem(from: Int, to: Int) {
            Collections.swap(adapterList, from, to)
            notifyItemMoved(from, to)
        }

        fun lastItem() = adapterList.last()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
            return ItemViewHolder(ListItemPokemonBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
            val p = adapterList[position]
            holder.job?.cancel()
            holder.binding.apply {
                root.tag = p
                root.setOnClickListener(onListItemClickListener)
                name.text = if (p.name.isBlank()) p.species else "${p.name} (${p.species})"
                holder.job = fragmentScope.launch {
                    val dexPokemon = assetLoader.dexPokemon(p.species.toId()) ?: return@launch
                    val gender = when (dexPokemon.gender?.toId() ?: p.gender) {
                        "m" -> "♂"
                        "f" -> "♀"
                        "n" -> "Undefined"
                        else -> "—"
                    }
                    details.text = "Level: ".small() concat "${p.level}" concat ", Gender: ".small() concat gender
                    ability.text = "Ability: ".small() concat dexPokemon.matchingAbility(p.ability.or("None"))
                    val itemObject = if (p.item.isNotBlank()) assetLoader.item(p.item) else null
                    item.text = "Item: ".small() concat (itemObject?.name ?: p.item.or("None"))
                    val moveStrings = (0 until 4).map { i ->
                        val moveId = p.moves[i] // We know we have a 4 items list see TeamBuilderActivity
                        if (moveId.isBlank()) "None" else assetLoader.moveDetails(moveId)?.name ?: moveId
                    }
                    moves.text = "Moves: ".small() concat moveStrings.joinToString(", ")
                    evs.text = "Evs: ".small() concat p.evs.summaryText(Nature.get(p.nature))
                }

                ViewCompat.setTransitionName(root, "content_$position")
                if (p.species.isBlank()) sprite.setImageResource(R.drawable.placeholder_pokeball)
                else glideHelper.loadDexSprite(p, p.shiny, sprite)
            }

        }

        override fun getItemCount() = adapterList.size

        inner class ItemViewHolder(val binding: ListItemPokemonBinding, var job: Job? = null) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val ARG_FORMATS = "arg-formats"
    }

}
