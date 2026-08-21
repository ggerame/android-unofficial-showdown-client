package com.majeur.psclient.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.majeur.psclient.databinding.DialogFormatSelectorBinding
import com.majeur.psclient.databinding.FragmentTeamsBinding
import com.majeur.psclient.databinding.ListCategoryTeamBinding
import com.majeur.psclient.databinding.ListItemTeamBinding
import com.majeur.psclient.io.AssetLoader
import com.majeur.psclient.io.BattleFormatCache
import com.majeur.psclient.io.TeamsStore
import com.majeur.psclient.model.common.BattleFormat
import com.majeur.psclient.model.common.Team
import com.majeur.psclient.model.common.RemoteTeamSummary
import com.majeur.psclient.model.common.TeamDraftValidator
import com.majeur.psclient.model.pokemon.TeamPokemon
import com.majeur.psclient.service.ShowdownService
import com.majeur.psclient.model.common.toId
import com.majeur.psclient.ui.teambuilder.TeamBuilderActivity
import com.majeur.psclient.util.recyclerview.DividerItemDecoration
import com.majeur.psclient.util.recyclerview.ItemTouchHelperCallbacks
import com.majeur.psclient.util.recyclerview.OnItemClickListener
import com.majeur.psclient.util.smogon.SmogonTeamBuilder
import com.majeur.psclient.util.toId
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.Serializable
import java.util.*

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


class TeamsFragment : BaseFragment(), OnItemClickListener {

    val teams: List<Team.Group> get() = groups.toList()

    private lateinit var teamsStore: TeamsStore
    private lateinit var assetLoader: AssetLoader
    private lateinit var listAdapter: TeamListAdapter
    private var cachedBattleFormats = emptyList<BattleFormat.Category>()

    private val groups = mutableListOf<Team.Group>()
    private val fallbackFormat = BattleFormat.FORMAT_OTHER
    private var currentAccountId: String? = null

    private var _binding: FragmentTeamsBinding? = null
    private val binding get() = _binding!!

    private val clipboardManager
        get() = requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val battleFormats
        get() = service?.getSharedData<List<BattleFormat.Category>>("formats") ?: cachedBattleFormats

    override fun onAttach(context: Context) {
        super.onAttach(context)
        teamsStore = TeamsStore(context)
        assetLoader = mainActivity.assetLoader
        cachedBattleFormats = BattleFormatCache(context).get()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fragmentScope.launch {
            val storedGroups = teamsStore.get()
            groups.clear()
            groups.addAll(storedGroups.sortedWith(Comparator<Team.Group> { g1, g2 ->
                BattleFormat.compare(battleFormats, g1.format, g2.format)
            }))
            groups.forEach { g -> g.teams.sort() }
            if (this@TeamsFragment::listAdapter.isInitialized) {
                listAdapter.notifyDataSetChanged()
            }
            syncRemoteTeams()
        }
    }

    override fun onServiceBound(service: ShowdownService) {
        super.onServiceBound(service)
        syncRemoteTeams()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentTeamsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.teamList.apply {
            listAdapter = TeamListAdapter(this@TeamsFragment)
            adapter = listAdapter
            addItemDecoration(object : DividerItemDecoration(view.context) {
                override fun shouldDrawDivider(parent: RecyclerView, child: View) =
                        parent.findContainingViewHolder(child) is TeamsFragment.TeamListAdapter.CategoryHolder
            })
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    binding.apply {
                        if (dy > 0) {
                            importFab.hide()
                            buildFab.hide()
                        } else {
                            importFab.show()
                            buildFab.show()
                        }
                    }
                }
            })
            ItemTouchHelper(object : ItemTouchHelperCallbacks(context, allowDeletion = true) {
                override fun onRemoveItem(position: Int) {
                    val team = listAdapter.getItem(position) as Team
                    removeTeam(team)
                    Snackbar.make(binding.root, "${team.label} removed", Snackbar.LENGTH_LONG)
                            .setAction("Undo") {
                                addOrUpdateTeam(team)
                            }.show()
                }
            }).attachToRecyclerView(this)
        }
        binding.buildFab.setOnClickListener {
            chooseFormat()
        }
        binding.importFab.setOnClickListener {
            if (childFragmentManager.findFragmentByTag(ImportTeamDialog.FRAGMENT_TAG) == null)
                ImportTeamDialog().show(childFragmentManager, ImportTeamDialog.FRAGMENT_TAG)
        }
    }

    fun promptImportFromPokepaste(teamId: String) {
        if (childFragmentManager.findFragmentByTag(ImportTeamDialog.FRAGMENT_TAG) == null) {
            ImportTeamDialog().apply {
                arguments = bundleOf(ImportTeamDialog.ARG_PP_ID to teamId)
            }.show(childFragmentManager, ImportTeamDialog.FRAGMENT_TAG)
        }
    }

    fun makeSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onItemClick(itemView: View, holder: RecyclerView.ViewHolder, position: Int) {
        val team = listAdapter.getItem(position) as Team
        if (team.isRemoteStub) loadAndOpenRemoteTeam(team) else startTeamBuilderActivity(team)
    }

    fun onAccountChanged(userName: String, isGuest: Boolean) {
        currentAccountId = if (isGuest) null else userName.toId()
        if (isGuest) {
            groups.flatMap { it.teams }.filter { it.remoteTeamId != null }.forEach {
                it.remoteState = Team.RemoteState.DISCONNECTED
            }
            if (this::listAdapter.isInitialized) listAdapter.notifyDataSetChanged()
        } else {
            syncRemoteTeams()
        }
    }

    private fun syncRemoteTeams() {
        val owner = currentAccountId ?: return
        val service = service ?: return
        service.loadRemoteTeams { remoteTeams, error ->
            if (remoteTeams == null) {
                if (_binding != null) makeSnackbar(error ?: "Could not load account teams")
                return@loadRemoteTeams
            }
            val knownIds = remoteTeams.map { it.id }.toSet()
            groups.flatMap { it.teams }.filter { it.remoteOwnerId == owner && it.remoteTeamId !in knownIds }
                    .forEach { it.remoteState = Team.RemoteState.DISCONNECTED }
            remoteTeams.forEach { remote -> mergeRemoteSummary(owner, remote) }
            groups.sortWith(Comparator { first, second -> BattleFormat.compare(battleFormats, first.format, second.format) })
            if (this::listAdapter.isInitialized) listAdapter.notifyDataSetChanged()
            homeFragment.onTeamsChanged()
            persistUserTeams()
        }
    }

    private fun mergeRemoteSummary(owner: String, remote: RemoteTeamSummary) {
        val existing = groups.flatMap { it.teams }
                .firstOrNull { it.remoteOwnerId == owner && it.remoteTeamId == remote.id }
        if (existing != null) {
            existing.remotePrivate = remote.isPrivate
            if (existing.remoteState == Team.RemoteState.DISCONNECTED)
                existing.remoteState = if (existing.isRemoteStub) Team.RemoteState.REMOTE_ONLY else Team.RemoteState.REMOTE_CLEAN
            return
        }
        val team = Team(remote.name, remote.species.map { TeamPokemon(it) }, remote.format).apply {
            remoteTeamId = remote.id
            remoteOwnerId = owner
            remotePrivate = remote.isPrivate
            remoteState = Team.RemoteState.REMOTE_ONLY
            isRemoteStub = true
        }
        val group = groups.firstOrNull { it.format == remote.format } ?: Team.Group(remote.format).also(groups::add)
        group.teams.add(team)
    }

    private fun loadAndOpenRemoteTeam(stub: Team) {
        val remoteId = stub.remoteTeamId ?: return
        service?.loadRemoteTeam(remoteId) { packed, error ->
            if (packed == null) {
                makeSnackbar(error ?: "Could not load this server team")
                return@loadRemoteTeam
            }
            val loaded = Team.unpack(stub.label, stub.format, packed, stub.uniqueId)
            if (loaded == null) {
                makeSnackbar("The server returned an invalid packed team")
                return@loadRemoteTeam
            }
            loaded.remoteTeamId = stub.remoteTeamId
            loaded.remoteOwnerId = stub.remoteOwnerId
            loaded.remotePrivate = stub.remotePrivate
            loaded.remoteState = Team.RemoteState.REMOTE_CLEAN
            loaded.isRemoteStub = false
            addOrUpdateTeam(loaded, persistTeams = false)
            loaded.remoteState = Team.RemoteState.REMOTE_CLEAN
            persistUserTeams()
            startTeamBuilderActivity(loaded)
        }
    }

    private fun startTeamBuilderActivity(team: Team? = null) {
        val intent = Intent(context, TeamBuilderActivity::class.java)
        val battleFormats = battleFormats
        intent.putExtra(TeamBuilderActivity.INTENT_EXTRA_FORMATS, battleFormats as Serializable)
        if (team != null)
            intent.putExtra(TeamBuilderActivity.INTENT_EXTRA_TEAM, team)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, TeamBuilderActivity.INTENT_REQUEST_CODE)
    }

    private fun chooseFormat() {
        val categories = battleFormats.mapNotNull { category ->
            val formats = category.formats.filter {
                it.isTeamNeeded && (it.isSearchShow || it.isChallengeShow || it.isTournamentShow)
            }
            if (formats.isEmpty()) null else category to formats
        }
        if (categories.isEmpty()) {
            makeSnackbar("Connect to Pokémon Showdown to load the available formats")
            return
        }
        val expanded = mutableSetOf(categories.first().first)
        var query = ""
        val adapter = object : com.majeur.psclient.widget.CategoryAdapter(requireContext()) {
            override fun isCategoryItem(position: Int) = getItem(position) is BattleFormat.Category
            override fun isCategoryEnabled(position: Int) = true
            override fun getCategoryLabel(position: Int): String {
                val category = getItem(position) as BattleFormat.Category
                val isExpanded = query.isNotBlank() || category in expanded
                return "${if (isExpanded) "▾" else "▸"}  ${category.label}"
            }
            override fun getItemLabel(position: Int) = (getItem(position) as BattleFormat).label
        }

        fun refreshRows() {
            val rows = mutableListOf<Any>()
            filterFormatCategories(categories, query).forEach { (category, formats) ->
                rows.add(category)
                if (query.isNotBlank() || category in expanded) rows.addAll(formats)
            }
            adapter.replaceItems(rows)
        }

        val selector = DialogFormatSelectorBinding.inflate(layoutInflater)
        selector.formatsList.adapter = adapter
        selector.formatsList.emptyView = selector.emptyFormats
        selector.formatSearch.doAfterTextChanged { text ->
            query = text?.toString().orEmpty().trim()
            refreshRows()
            selector.formatsList.setSelection(0)
        }

        refreshRows()
        val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle("Choose a format")
                .setView(selector.root)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        selector.formatsList.setOnItemClickListener { _, _, index, _ ->
            when (val selected = adapter.getItem(index)) {
                is BattleFormat.Category -> {
                    if (query.isNotBlank()) return@setOnItemClickListener
                    if (!expanded.remove(selected)) {
                        expanded.clear()
                        expanded += selected
                    }
                    refreshRows()
                }
                is BattleFormat -> {
                    val intent = Intent(context, TeamBuilderActivity::class.java).apply {
                        putExtra(TeamBuilderActivity.INTENT_EXTRA_FORMATS, battleFormats as Serializable)
                        putExtra(TeamBuilderActivity.INTENT_EXTRA_FORMAT_ID, selected.id)
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, TeamBuilderActivity.INTENT_REQUEST_CODE)
                    dialog.dismiss()
                }
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            selector.formatSearch.clearFocus()
        }
        dialog.show()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == TeamBuilderActivity.INTENT_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val team = data.getSerializableExtra(TeamBuilderActivity.INTENT_EXTRA_TEAM) as Team
            addOrUpdateTeam(team)
        }
    }

    fun onBattleFormatsChanged() {
        groups.sortWith(Comparator<Team.Group> { g1, g2 ->
            BattleFormat.compare(battleFormats, g1.format, g2.format)
        })
        listAdapter.notifyDataSetChanged() // Update and sorts formats labels
    }

    fun onTeamsImported(teams: List<Team>) {
        for (team in teams) addOrUpdateTeam(team, persistTeams = false)
        persistUserTeams()
        makeSnackbar("Successfully imported ${teams.size} team(s)")
    }

    private fun addOrUpdateTeam(newTeam: Team, persistTeams: Boolean = true) {
        if (newTeam.remoteTeamId != null && !newTeam.isRemoteStub && newTeam.remoteState == Team.RemoteState.REMOTE_CLEAN)
            newTeam.remoteState = Team.RemoteState.LOCAL_CHANGES
        if (newTeam.format == null) newTeam.format = fallbackFormat.toId()
        var teamAdded = false
        for (group in groups) {
            val oldTeam = group.teams.firstOrNull { it.uniqueId == newTeam.uniqueId }
            if (oldTeam != null) { // Its an update
                if (oldTeam.format?.toId() == newTeam.format?.toId()) { // Format has not changed so we just replace item
                    val adapterPosition = listAdapter.getItemPosition(oldTeam)
                    val indexInGroup = group.teams.indexOf(oldTeam)
                    group.teams[indexInGroup] = newTeam
                    listAdapter.notifyItemChanged(adapterPosition)
                    teamAdded = true
                    if (oldTeam.label != newTeam.label) { // Label changed, move team to correct position
                        val newIndex = group.teams.sorted().indexOf(newTeam)
                        group.teams.add(newIndex, group.teams.removeAt(indexInGroup))
                        listAdapter.notifyItemMoved(adapterPosition, listAdapter.getItemPosition(newTeam))
                    }
                } else { // Format has changed so we need to remove team from its previous group
                    var adapterPosition = listAdapter.getItemPosition(oldTeam)
                    group.teams.remove(oldTeam)
                    listAdapter.notifyItemRemoved(adapterPosition)
                    if (group.teams.isEmpty()) {
                        adapterPosition = listAdapter.getItemPosition(group)
                        groups.remove(group)
                        listAdapter.notifyItemRemoved(adapterPosition)
                    }
                }
                break
            }
            if (group.format.toId() == newTeam.format?.toId()) {
                val index = group.teams.plus(newTeam).sorted().indexOf(newTeam)
                group.teams.add(index, newTeam)
                val adapterPosition = listAdapter.getItemPosition(newTeam)
                listAdapter.notifyItemInserted(adapterPosition)
                teamAdded = true
            }
        }
        if (!teamAdded) { // No group matched our team format
            val newGroup = Team.Group(newTeam.format!!.toId())
            val index = groups.plus(newGroup).sortedWith(Comparator<Team.Group> { g1, g2 ->
                BattleFormat.compare(battleFormats, g1.format, g2.format)
            }).indexOf(newGroup)
            groups.add(index, newGroup)
            var adapterPosition = listAdapter.getItemPosition(newGroup)
            listAdapter.notifyItemInserted(adapterPosition)
            newGroup.teams.add(newTeam)
            adapterPosition = listAdapter.getItemPosition(newTeam)
            listAdapter.notifyItemInserted(adapterPosition)
        }
        homeFragment.onTeamsChanged()
        if (persistTeams) persistUserTeams()
    }

    private fun removeTeam(team: Team) {
        for (group in groups) {
            val matchingTeam = group.teams.firstOrNull { it.uniqueId == team.uniqueId } ?: continue
            var adapterPosition = listAdapter.getItemPosition(matchingTeam)
            group.teams.remove(matchingTeam)
            listAdapter.notifyItemRemoved(adapterPosition)
            if (group.teams.isEmpty()) {
                adapterPosition = listAdapter.getItemPosition(group)
                groups.remove(group)
                listAdapter.notifyItemRemoved(adapterPosition)
            }
            break
        }
        homeFragment.onTeamsChanged()
        persistUserTeams()
    }

    private fun resolveFormatName(formatId: String): String {
        battleFormats?.let {
            return BattleFormat.resolveName(it, formatId)
        }
        return formatId
    }

    private fun persistUserTeams() {
        fragmentScope.launch {
            val success = teamsStore.store(groups)
            if (!success) Snackbar.make(binding.root, "Error while saving teams", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun exportTeamToClipboard(team: Team) {
        fragmentScope.launch {
            val result = SmogonTeamBuilder.buildTeams(assetLoader, listOf(team))
            clipboardManager.setPrimaryClip(ClipData.newPlainText("Exported Teams", result))
            makeSnackbar("${team.label} copied to clipboard")
        }
    }

    private fun showTeamActions(team: Team) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Copy export" to { exportTeamToClipboard(team) }
        if (currentAccountId != null && !team.isRemoteStub)
            actions += (if (team.remoteTeamId == null) "Save to account (private)" else "Upload changes") to { uploadTeam(team) }
        if (team.remoteTeamId != null && team.remoteOwnerId == currentAccountId) {
            actions += (if (team.remotePrivate) "Make public" else "Make private") to { toggleRemotePrivacy(team) }
            actions += "Remove from server (keep local)" to { removeRemoteCopy(team) }
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle(team.label)
                .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
                .show()
    }

    private fun uploadTeam(team: Team) {
        val format = battleFormats.orEmpty().flatMap { it.formats }.firstOrNull { it.id == team.format }
        val localIssues = TeamDraftValidator.validate(team, format)
        if (localIssues.isNotEmpty()) {
            MaterialAlertDialogBuilder(requireContext()).setTitle("Draft is not ready")
                    .setMessage(localIssues.joinToString("\n") { "• $it" }).setPositiveButton("OK", null).show()
            return
        }
        service?.validateTeam(team.pack(), team.format!!) { validation ->
            if (!validation.valid) {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Team rejected")
                        .setMessage(validation.message).setPositiveButton("OK", null).show()
                return@validateTeam
            }
            service?.saveRemoteTeam(team) { success, response ->
                if (!success) return@saveRemoteTeam makeSnackbar(response)
                if (team.remoteTeamId == null) team.remoteTeamId = RemoteTeamSummary.parseUploadedId(response)
                team.remoteOwnerId = currentAccountId
                team.remoteState = Team.RemoteState.REMOTE_CLEAN
                persistUserTeams()
                listAdapter.notifyDataSetChanged()
                makeSnackbar("Team saved to your Showdown account")
            }
        }
    }

    private fun toggleRemotePrivacy(team: Team) {
        val remoteId = team.remoteTeamId ?: return
        team.remotePrivate = !team.remotePrivate
        service?.setRemoteTeamPrivacy(remoteId, team.remotePrivate)
        persistUserTeams()
        listAdapter.notifyDataSetChanged()
    }

    private fun removeRemoteCopy(team: Team) {
        val remoteId = team.remoteTeamId ?: return
        MaterialAlertDialogBuilder(requireContext()).setTitle("Remove from server?")
                .setMessage("The local copy will be kept on this device.")
                .setPositiveButton("Remove") { _, _ ->
                    service?.deleteRemoteTeam(remoteId)
                    team.remoteTeamId = null
                    team.remoteOwnerId = null
                    team.remoteState = Team.RemoteState.LOCAL_ONLY
                    team.isRemoteStub = false
                    persistUserTeams()
                    listAdapter.notifyDataSetChanged()
                }.setNegativeButton("Cancel", null).show()
    }

    private inner class TeamListAdapter(
            private val itemClickListener: OnItemClickListener
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val VIEW_TYPE_CATEGORY = 0
        private val VIEW_TYPE_ITEM = 1

        inner class CategoryHolder(val binding: ListCategoryTeamBinding) : RecyclerView.ViewHolder(binding.root)
        inner class ItemHolder(val binding: ListItemTeamBinding, var job: Job? = null) : RecyclerView.ViewHolder(binding.root), View.OnClickListener, View.OnLongClickListener {
            val pokemonViews = binding.run {
                listOf(imageViewPokemon1, imageViewPokemon2, imageViewPokemon3,
                        imageViewPokemon4, imageViewPokemon5, imageViewPokemon6)
            }

            init {
                binding.copyButton.setOnClickListener(this)
                binding.root.setOnClickListener(this)
                binding.root.setOnLongClickListener(this)
            }

            override fun onClick(v: View?) {
                if (v == binding.copyButton) {
                    exportTeamToClipboard(getItem(adapterPosition) as Team)
                } else {
                    itemClickListener.onItemClick(itemView, this, adapterPosition)
                }
            }

            override fun onLongClick(v: View?): Boolean {
                showTeamActions(getItem(adapterPosition) as Team)
                return true
            }
        }

        override fun getItemCount(): Int {
            var count = 0
            groups.forEach { g -> count += 1 + g.teams.size }
            return count
        }

        fun getItem(position: Int): Any? {
            var count = -1
            groups.forEach { g -> if (++count == position) return g else g.teams.forEach { if (++count == position) return it } }
            return null
        }

        fun getItemPosition(item: Any): Int {
            var count = -1
            groups.forEach { g -> ++count; if (g == item) return count else g.teams.forEach { ++count; if (it == item) return count } }
            return -1
        }

        override fun getItemViewType(position: Int) = if (getItem(position) is Team) VIEW_TYPE_ITEM else VIEW_TYPE_CATEGORY

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = when (viewType) {
            VIEW_TYPE_CATEGORY -> CategoryHolder(ListCategoryTeamBinding.inflate(layoutInflater, parent, false))
            else -> ItemHolder(ListItemTeamBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is CategoryHolder) {
                val group = getItem(position) as Team.Group
                holder.binding.text1.text = resolveFormatName(group.format)
            } else if (holder is ItemHolder) {
                val team = getItem(position) as Team
                val suffix = when (team.remoteState) {
                    Team.RemoteState.REMOTE_ONLY -> " · Server"
                    Team.RemoteState.REMOTE_CLEAN -> " · Synced"
                    Team.RemoteState.LOCAL_CHANGES -> " · Local changes"
                    Team.RemoteState.DISCONNECTED -> " · Local copy"
                    Team.RemoteState.LOCAL_ONLY -> ""
                }
                val format = battleFormats.flatMap { it.formats }.firstOrNull { it.id == team.format }
                val draft = if (TeamDraftValidator.validate(team, format).isNotEmpty()) " · Draft" else ""
                holder.binding.textViewTitle.text = team.label + suffix + draft
                holder.pokemonViews.forEach { it.setImageDrawable(null) }
                holder.job?.cancel()
                if (team.pokemons.isNotEmpty()) {
                    holder.job = fragmentScope.launch {
                        assetLoader.dexIcons(*team.pokemons.map { it.species.toId() }.toTypedArray()).forEachIndexed { index, bitmap ->
                            val drawable = BitmapDrawable(resources, bitmap)
                            holder.pokemonViews[index].setImageDrawable(drawable)
                        }
                    }
                }

            }
        }
    }


}
