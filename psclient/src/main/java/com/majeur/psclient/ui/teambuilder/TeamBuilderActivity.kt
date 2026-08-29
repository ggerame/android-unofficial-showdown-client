package com.majeur.psclient.ui.teambuilder

import android.app.Activity
import android.content.DialogInterface
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.majeur.psclient.R
import com.majeur.psclient.databinding.ActivityTeamBuilderBinding
import com.majeur.psclient.io.AssetLoader
import com.majeur.psclient.io.GlideHelper
import com.majeur.psclient.model.common.BattleFormat
import com.majeur.psclient.model.common.Team
import com.majeur.psclient.model.common.toId
import com.majeur.psclient.util.applySafeDrawingInsets
import com.majeur.psclient.util.configureEdgeToEdge

class TeamBuilderActivity : AppCompatActivity() {

    private val viewModel by lazy { ViewModelProvider(this)[TeamBuilderViewModel::class.java] }
    val team get() = viewModel.team
    lateinit var formats: List<BattleFormat.Category>
        private set
    val currentFormat: BattleFormat?
        get() = formats.asSequence().flatMap { it.formats.asSequence() }.firstOrNull { it.id == team.format }

    val glideHelper by lazy { GlideHelper(this) }
    val assetLoader by lazy { AssetLoader(this) }

    private lateinit var binding: ActivityTeamBuilderBinding

    private val onBackPressedCallback = object : OnBackPressedCallback(false) {

        override fun handleOnBackPressed() {
            MaterialAlertDialogBuilder(this@TeamBuilderActivity)
                    .setTitle("Changes will be lost")
                    .setMessage("Are you sure you want to quit without applying changes ?")
                    .setPositiveButton("Yes") { _: DialogInterface?, _: Int ->
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                    .setNegativeButton("No", null)
                    .show()
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        binding = ActivityTeamBuilderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySafeDrawingInsets(includeIme = true)
        setSupportActionBar(binding.toolbar)
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        // TODO Retrieve cached formats if null
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        formats = intent.getSerializableExtra(INTENT_EXTRA_FORMATS) as List<BattleFormat.Category>? ?: emptyList()

        if (!viewModel.isInitialized) {
            @Suppress("DEPRECATION")
            val suppliedTeam = intent.extras?.getSerializable(INTENT_EXTRA_TEAM) as Team?
            val selectedFormat = intent.getStringExtra(INTENT_EXTRA_FORMAT_ID)
            viewModel.team = suppliedTeam
                    ?: Team("Unnamed team", emptyList(), selectedFormat ?: BattleFormat.FORMAT_OTHER.id)
            team.pokemons = team.pokemons.toMutableList()
            team.pokemons.forEach { poke ->
                poke.moves = poke.moves.toMutableList().apply { for (i in size until 4) add("") }
            }
        }

        val navController = findNavController(R.id.nav_host_fragment)
        navController.setGraph(R.navigation.team_builder, bundleOf(
                TeamFragment.ARG_FORMATS to formats
        ))
        navController.addOnDestinationChangedListener { controller, destination, _ ->
            val isStartDestination = controller.graph.startDestinationId == destination.id
            onBackPressedCallback.isEnabled = isStartDestination
        }
        setupActionBarWithNavController(navController)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    companion object {
        const val MAX_TEAM_SIZE = 6
        const val INTENT_REQUEST_CODE = 194
        const val INTENT_EXTRA_TEAM = "intent-extra-team"
        const val INTENT_EXTRA_FORMATS = "intent-extra-formats"
        const val INTENT_EXTRA_FORMAT_ID = "intent-extra-format-id"
    }

}
