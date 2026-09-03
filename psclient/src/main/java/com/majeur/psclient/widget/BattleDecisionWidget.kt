package com.majeur.psclient.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.util.Property
import android.view.LayoutInflater
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.majeur.psclient.R
import com.majeur.psclient.model.battle.BattleDecision
import com.majeur.psclient.model.battle.BattleDecisionRequest
import com.majeur.psclient.model.battle.Move
import com.majeur.psclient.model.battle.Player
import com.majeur.psclient.model.battle.PokemonId
import com.majeur.psclient.model.battle.Move.Target.Companion.computeTargetAvailabilities
import com.majeur.psclient.model.common.Colors
import com.majeur.psclient.model.common.Type
import com.majeur.psclient.model.pokemon.BattlingPokemon
import com.majeur.psclient.model.pokemon.SidePokemon
import com.majeur.psclient.service.observer.BattleRoomMessageObserver
import com.majeur.psclient.util.SimpleAnimatorListener
import com.majeur.psclient.util.concat
import com.majeur.psclient.util.small
import com.majeur.psclient.util.toId
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min

class BattleDecisionWidget @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), View.OnClickListener {

    var onChoosingChangedListener: ((Boolean) -> Unit)? = null

    private val contentRoot: View
    private val titleView: TextView
    private val subtitleView: TextView
    private val choiceTabs: MaterialButtonToggleGroup
    private val movesSection: View
    private val teamGrid: View
    private val targetContainer: View
    private val farTargetLabel: TextView
    private val nearTargetLabel: TextView
    private val decisionScroll: NestedScrollView
    private val moveButtons: List<MaterialButton>
    private val teamButtons: List<SwitchButton>
    private val farTargetButtons: List<SwitchButton>
    private val nearTargetButtons: List<SwitchButton>
    private val gimmickButton: MaterialButton
    private val backButton: MaterialButton

    private var contentAlpha = 1f
        set(value) {
            field = value
            contentRoot.alpha = value
        }
    private val alphaAnimator: ObjectAnimator
    private var revealAnimator: Animator? = null
    private var revealingIn = false
    private var revealingOut = false
    private var isAnimatingContentAlpha = false

    private var promptStage = 0
    private var targetToChoose: Move.Target? = null
    private var targetMoveName: String? = null
    private var foeTypes: List<String>? = null
    private var battleViewFlipped = false
    private var movesAvailable = false
    private var teamAvailable = false
    private var updatingTabs = false
    private var updatingGimmick = false
    private var gimmick = Gimmick.NONE
    private val switchTabs = mutableSetOf<Int>()
    private val gimmicksByStage = mutableMapOf<Int, Gimmick>()

    private var trainerTargets: List<BattlingPokemon?> = emptyList()
    private var foeTargets: List<BattlingPokemon?> = emptyList()
    private var targetAvailabilities: Array<BooleanArray>? = null

    private var _observer: BattleRoomMessageObserver? = null
    private val observer get() = _observer!!
    private var _battleTipPopup: BattleTipPopup? = null
    private val battleTipPopup get() = _battleTipPopup!!
    private var _request: BattleDecisionRequest? = null
    private val request get() = _request!!
    private var _decision: BattleDecision? = null
    private val decision get() = _decision!!
    private var _onDecisionListener: ((BattleDecision) -> Unit)? = null
    private val onDecisionListener get() = _onDecisionListener!!
    private var comingToPreviousStage = false

    private val defaultMoveTint by lazy {
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)
    }

    init {
        visibility = View.GONE
        LayoutInflater.from(context).inflate(R.layout.layout_battle_decision, this, true)
        contentRoot = findViewById(R.id.decision_panel_content)
        titleView = findViewById(R.id.decision_title)
        subtitleView = findViewById(R.id.decision_subtitle)
        choiceTabs = findViewById(R.id.choice_tabs)
        movesSection = findViewById(R.id.moves_section)
        teamGrid = findViewById(R.id.team_grid)
        targetContainer = findViewById(R.id.target_container)
        farTargetLabel = findViewById(R.id.far_target_label)
        nearTargetLabel = findViewById(R.id.near_target_label)
        decisionScroll = findViewById(R.id.decision_scroll)
        moveButtons = listOf(R.id.move_1, R.id.move_2, R.id.move_3, R.id.move_4).map(::findViewById)
        teamButtons = listOf(R.id.team_1, R.id.team_2, R.id.team_3, R.id.team_4, R.id.team_5, R.id.team_6).map(::findViewById)
        farTargetButtons = listOf(R.id.far_target_1, R.id.far_target_2, R.id.far_target_3).map(::findViewById)
        nearTargetButtons = listOf(R.id.near_target_1, R.id.near_target_2, R.id.near_target_3).map(::findViewById)
        gimmickButton = findViewById(R.id.gimmick_button)
        backButton = findViewById(R.id.decision_back)
        gimmickButton.isCheckable = true

        moveButtons.forEach { it.setOnClickListener(this) }
        (teamButtons + farTargetButtons + nearTargetButtons).forEach { it.setOnClickListener(this) }
        (farTargetButtons + nearTargetButtons).forEach { it.setIconVisible(false) }
        backButton.setOnClickListener { promptPrevious() }

        choiceTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingTabs) return@addOnButtonCheckedListener
            if (checkedId == R.id.switch_tab) switchTabs.add(promptStage) else switchTabs.remove(promptStage)
            renderChoiceSection(scrollToTop = true)
        }
        gimmickButton.addOnCheckedChangeListener { _, checked ->
            if (updatingGimmick) return@addOnCheckedChangeListener
            if (checked) gimmicksByStage[promptStage] = gimmick else gimmicksByStage.remove(promptStage)
            when (gimmick) {
                Gimmick.Z_MOVE -> toggleZMoves(checked)
                Gimmick.DYNAMAX -> toggleMaxMoves(checked)
                else -> Unit
            }
        }
        val checkedStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        gimmickButton.backgroundTintList = ColorStateList(
                checkedStates,
                intArrayOf(
                        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer),
                        Color.TRANSPARENT))
        gimmickButton.setTextColor(ColorStateList(
                checkedStates,
                intArrayOf(
                        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer),
                        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))))

        alphaAnimator = ObjectAnimator().apply {
            interpolator = DecelerateInterpolator()
            target = this@BattleDecisionWidget
            setProperty(CONTENT_ALPHA_PROPERTY)
        }
    }

    /** Expands naturally and caps only the scrolling content when the remaining battle area is short. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        if (heightMode == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        decisionScroll.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        val naturalHeight = measuredHeight
        if (heightMode == MeasureSpec.AT_MOST && naturalHeight <= availableHeight) return

        val fixedHeight = naturalHeight - decisionScroll.measuredHeight
        decisionScroll.layoutParams.height = (availableHeight - fixedHeight).coerceAtLeast(0)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                availableHeight,
                if (heightMode == MeasureSpec.EXACTLY) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
    }

    fun promptDecision(
            observer: BattleRoomMessageObserver,
            battleTipPopup: BattleTipPopup,
            request: BattleDecisionRequest,
            listener: (BattleDecision) -> Unit
    ) {
        promptStage = -1
        targetToChoose = null
        targetMoveName = null
        switchTabs.clear()
        gimmicksByStage.clear()
        _observer = observer
        _battleTipPopup = battleTipPopup
        _request = request
        _onDecisionListener = listener
        _decision = BattleDecision()
        onChoosingChangedListener?.invoke(true)
        promptNext()
        revealIn()
    }

    private fun promptNext() {
        when {
            targetToChoose != null -> {
                trainerTargets = (0 until request.count).map {
                    observer.getBattlingPokemon(PokemonId(Player.TRAINER, it))
                }
                foeTargets = (0 until request.count).map {
                    observer.getBattlingPokemon(PokemonId(Player.FOE, it))
                }
                targetAvailabilities = computeTargetAvailabilities(targetToChoose!!, promptStage, request.count)
                showTargetChoice()
            }
            promptStage + 1 >= request.count || (request.teamPreview && promptStage == 0) -> {
                val summary = if (request.teamPreview) {
                    context.getString(
                            R.string.battle_pending_team_order,
                            decision.summaryLines().joinToString(" → "))
                } else {
                    decision.summaryLines().joinToString("\n")
                }
                onDecisionListener(decision)
                showPendingDecision(summary)
                onChoosingChangedListener?.invoke(false)
                clearDecision()
            }
            else -> {
                promptStage += 1
                if (!request.teamPreview) {
                    val activeFainted = request.side[promptStage].condition.health == 0f
                    val unfaintedCount = request.side.drop(request.count).count { it.condition.health != 0f }
                    val pass = activeFainted && unfaintedCount - decision.switchChoicesCount() <= 0
                    if (request.shouldPass(promptStage) || pass) {
                        decision.addPassChoice()
                        promptNext()
                        return
                    }
                }
                val hideMoves = request.forceSwitch(promptStage) || request.teamPreview
                val hideSwitch = request.trapped(promptStage)
                showChoice(
                        if (hideMoves) null else request.getMoves(promptStage),
                        request.canMegaEvo(promptStage),
                        request.canDynamax(promptStage),
                        request.isDynamaxed(promptStage),
                        request.canTerastallize(promptStage),
                        if (hideSwitch) null else request.side,
                        request.teamPreview)
            }
        }
        if (comingToPreviousStage) comingToPreviousStage = false
    }

    private fun clearDecision() {
        promptStage = 0
        targetToChoose = null
        targetMoveName = null
        trainerTargets = emptyList()
        foeTargets = emptyList()
        targetAvailabilities = null
        _observer = null
        _battleTipPopup = null
        _request = null
        _onDecisionListener = null
        _decision = null
    }

    private fun promptPrevious() {
        if (_decision == null) return
        if (request.teamPreview && decision.leadChoicesCount() > 0) {
            decision.removeLastChoice()
            bindTeamChoices(request.side, chooseLead = true)
            updateChoiceHeader(chooseLead = true)
            updateBackVisibility()
            return
        }

        comingToPreviousStage = true
        promptStage -= 1
        if (targetToChoose != null) {
            targetToChoose = null
            targetMoveName = null
            decision.removeLastChoice()
        } else if (decision.lastChoiceWasMoveTarget()) {
            val lastMove = decision.lastChoiceMove()
            val wasZ = decision.lastChoiceWasZ()
            val wasMax = decision.lastChoiceWasDynamax()
            val move = request.getMoves(promptStage)?.getOrNull(lastMove - 1)
            targetToChoose = when {
                wasMax || request.isDynamaxed(promptStage) -> move?.maxMoveTarget
                wasZ -> move?.zDetails?.target ?: move?.target
                else -> move?.target
            } ?: Move.Target.ALL
            targetMoveName = move?.let { displayMoveName(it, wasZ, wasMax || request.isDynamaxed(promptStage)) }
        } else {
            promptStage -= 1
            decision.removeLastChoice()
        }
        promptNext()
    }

    private fun showTargetChoice() = animateContentChange(::setTargetChoiceLayout)

    private fun setTargetChoiceLayout() {
        movesAvailable = false
        teamAvailable = false
        choiceTabs.visibility = View.GONE
        movesSection.visibility = View.GONE
        teamGrid.visibility = View.GONE
        targetContainer.visibility = View.VISIBLE
        gimmickButton.visibility = View.GONE
        titleView.text = context.getString(
                R.string.battle_choose_target,
                targetMoveName ?: context.getString(R.string.battle_move_target_fallback))
        subtitleView.visibility = View.GONE
        bindTargetRows()
        backButton.visibility = View.VISIBLE
        decisionScroll.scrollTo(0, 0)
    }

    private fun showPendingDecision(summary: String) = animateContentChange {
        choiceTabs.visibility = View.GONE
        movesSection.visibility = View.GONE
        teamGrid.visibility = View.GONE
        targetContainer.visibility = View.GONE
        gimmickButton.visibility = View.GONE
        backButton.visibility = View.GONE
        titleView.setText(R.string.battle_waiting_for_opponent)
        subtitleView.text = summary
        subtitleView.visibility = if (summary.isBlank()) View.GONE else View.VISIBLE
        decisionScroll.scrollTo(0, 0)
    }

    private fun bindTargetRows() {
        val availabilities = targetAvailabilities ?: return
        val farIsTrainer = battleViewFlipped
        farTargetLabel.setText(if (farIsTrainer) R.string.battle_your_side else R.string.battle_opponent_side)
        nearTargetLabel.setText(if (farIsTrainer) R.string.battle_opponent_side else R.string.battle_your_side)
        bindTargetButtons(
                farTargetButtons,
                if (farIsTrainer) trainerTargets else foeTargets,
                availabilities[if (farIsTrainer) 1 else 0])
        bindTargetButtons(
                nearTargetButtons,
                if (farIsTrainer) foeTargets else trainerTargets,
                availabilities[if (farIsTrainer) 0 else 1])
    }

    private fun bindTargetButtons(
            buttons: List<SwitchButton>,
            targets: List<BattlingPokemon?>,
            availabilities: BooleanArray
    ) = buttons.forEachIndexed { index, button ->
        val pokemon = targets.getOrNull(index)
        if (pokemon == null) {
            clearPokemonButton(button)
            return@forEachIndexed
        }
        val condition = pokemon.condition
        val fainted = condition?.health == 0f
        val enabled = availabilities.getOrElse(index) { false } && !fainted
        val state = when {
            fainted -> ChoiceState(
                    context.getString(R.string.battle_choice_fainted),
                    context.getString(R.string.battle_choice_fainted))
            !enabled -> ChoiceState(
                    context.getString(R.string.battle_choice_unavailable_short),
                    context.getString(R.string.battle_choice_unavailable))
            else -> ChoiceState()
        }
        button.apply {
            visibility = View.VISIBLE
            setIconVisible(false)
            setDexIcon(null)
            setPokemonName(pokemon.name)
            setCondition(condition)
            setChoiceState(state.label, state.description)
            setTag(R.id.battle_data_tag, pokemon)
            isEnabled = enabled
        }
        battleTipPopup.addTippedView(button)
    }

    private fun showChoice(
            moves: Array<Move>?,
            canMega: Boolean,
            canDynamax: Boolean,
            isDynamaxed: Boolean,
            teraType: String?,
            team: List<SidePokemon>?,
            chooseLead: Boolean
    ) {
        val update = {
            setChoiceLayout(moves, canMega, canDynamax, isDynamaxed, teraType, team, chooseLead)
        }
        if ((promptStage == 0 || decision.hasOnlyPassChoice()) && !comingToPreviousStage) update()
        else animateContentChange(update)
    }

    private fun setChoiceLayout(
            moves: Array<Move>?,
            canMega: Boolean,
            canDynamax: Boolean,
            isDynamaxed: Boolean,
            teraType: String?,
            team: List<SidePokemon>?,
            chooseLead: Boolean
    ) {
        targetContainer.visibility = View.GONE
        movesAvailable = !moves.isNullOrEmpty()
        teamAvailable = !team.isNullOrEmpty()

        moves?.forEach {
            it.zflag = false
            it.maxflag = false
        }
        bindMoves(moves)
        configureGimmick(moves, canMega, canDynamax, isDynamaxed, teraType)
        bindTeamChoices(team, chooseLead)
        updateChoiceHeader(chooseLead)
        renderChoiceSection(scrollToTop = true)
        updateBackVisibility()
    }

    private fun bindMoves(moves: Array<Move>?) = moveButtons.forEachIndexed { index, button ->
        val move = moves?.getOrNull(index)
        if (move == null) {
            battleTipPopup.removeTippedView(button)
            button.visibility = View.GONE
            button.setTag(R.id.battle_data_tag, null)
            return@forEachIndexed
        }
        button.visibility = View.VISIBLE
        button.setTag(R.id.battle_data_tag, move)
        refreshMoveButton(button, move)
        battleTipPopup.addTippedView(button)
    }

    private fun configureGimmick(
            moves: Array<Move>?,
            canMega: Boolean,
            canDynamax: Boolean,
            isDynamaxed: Boolean,
            teraType: String?
    ) {
        gimmick = when {
            canMega && !decision.hasMegaChoices() -> Gimmick.MEGA
            moves?.any(Move::canZMove) == true && !decision.hasZMoveChoices() -> Gimmick.Z_MOVE
            canDynamax && !decision.hasDynamaxChoices() -> Gimmick.DYNAMAX
            teraType != null && !decision.hasTeraChoices() -> Gimmick.TERA
            else -> Gimmick.NONE
        }
        if (isDynamaxed) gimmick = Gimmick.NONE
        if (gimmicksByStage[promptStage] != gimmick) gimmicksByStage.remove(promptStage)

        updatingGimmick = true
        gimmickButton.apply {
            visibility = if (gimmick == Gimmick.NONE) View.GONE else View.VISIBLE
            text = when (gimmick) {
                Gimmick.MEGA -> context.getString(R.string.battle_mega_evolution)
                Gimmick.Z_MOVE -> context.getString(R.string.battle_z_move)
                Gimmick.DYNAMAX -> context.getString(R.string.battle_dynamax)
                Gimmick.TERA -> context.getString(
                        R.string.battle_terastallize, teraType?.uppercase(Locale.ROOT).orEmpty())
                Gimmick.NONE -> null
            }
            isChecked = gimmicksByStage[promptStage] == gimmick && gimmick != Gimmick.NONE
        }
        updatingGimmick = false

        when {
            isDynamaxed -> toggleMaxMoves(true)
            gimmickButton.isChecked && gimmick == Gimmick.Z_MOVE -> toggleZMoves(true)
            gimmickButton.isChecked && gimmick == Gimmick.DYNAMAX -> toggleMaxMoves(true)
        }
    }

    private fun bindTeamChoices(team: List<SidePokemon>?, chooseLead: Boolean) =
            teamButtons.forEachIndexed { index, button ->
                val pokemon = team?.getOrNull(index)
                if (pokemon == null) {
                    clearPokemonButton(button)
                    return@forEachIndexed
                }
                val who = pokemon.index + 1
                val order = if (chooseLead) decision.leadChoicePosition(who) else null
                val previouslyChosenAsSwitch = !chooseLead && promptStage > 0 && request.count > 1 &&
                        decision.hasSwitchChoice(who)
                val availableSwitch = index >= request.count && pokemon.condition.health != 0f &&
                        !previouslyChosenAsSwitch
                val enabled = if (chooseLead) order == null else availableSwitch
                val state = when {
                    order != null -> ChoiceState(
                            order.toString(),
                            context.getString(R.string.battle_choice_order, order),
                            selected = true)
                    pokemon.condition.health == 0f -> ChoiceState(
                            context.getString(R.string.battle_choice_fainted),
                            context.getString(R.string.battle_choice_fainted))
                    pokemon.active || index < request.count -> ChoiceState(
                            context.getString(R.string.battle_choice_active),
                            context.getString(R.string.battle_choice_active))
                    previouslyChosenAsSwitch -> ChoiceState(
                            context.getString(R.string.battle_choice_selected),
                            context.getString(R.string.battle_choice_selected),
                            selected = true)
                    !enabled -> ChoiceState(
                            context.getString(R.string.battle_choice_unavailable_short),
                            context.getString(R.string.battle_choice_unavailable))
                    else -> ChoiceState()
                }
                button.apply {
                    visibility = View.VISIBLE
                    setIconVisible(true)
                    setDexIcon(pokemon.icon?.toDrawable(resources))
                    setPokemonName(pokemon.name)
                    setCondition(pokemon.condition)
                    setChoiceState(state.label, state.description, state.selected)
                    setTag(R.id.battle_data_tag, pokemon)
                    isEnabled = enabled
                }
                battleTipPopup.addTippedView(button)
            }

    private fun clearPokemonButton(button: SwitchButton) {
        if (_battleTipPopup != null) battleTipPopup.removeTippedView(button)
        button.apply {
            visibility = View.GONE
            setPokemonName(null)
            setCondition(null)
            setChoiceState(null)
            setDexIcon(null)
            setTag(R.id.battle_data_tag, null)
        }
    }

    private fun updateChoiceHeader(chooseLead: Boolean) {
        val pokemonName = request.side.getOrNull(promptStage)?.name?.trim().orEmpty()
        titleView.text = when {
            chooseLead -> context.getString(R.string.battle_choose_team_order)
            request.forceSwitch(promptStage) -> context.getString(R.string.battle_choose_replacement, pokemonName)
            else -> context.getString(R.string.battle_choose_for, pokemonName)
        }
        if (chooseLead) {
            subtitleView.text = context.getString(
                    R.string.battle_team_choice_progress,
                    decision.leadChoicesCount(),
                    requiredLeadChoices())
            subtitleView.visibility = View.VISIBLE
        } else if (request.count > 1) {
            subtitleView.text = context.getString(
                    R.string.battle_choice_progress, promptStage + 1, request.count)
            subtitleView.visibility = View.VISIBLE
        } else {
            subtitleView.visibility = View.GONE
        }
    }

    private fun renderChoiceSection(scrollToTop: Boolean) {
        val hasTabs = movesAvailable && teamAvailable
        choiceTabs.visibility = if (hasTabs) View.VISIBLE else View.GONE
        val showSwitch = teamAvailable && (!movesAvailable || promptStage in switchTabs)
        if (hasTabs) {
            updatingTabs = true
            choiceTabs.check(if (showSwitch) R.id.switch_tab else R.id.moves_tab)
            updatingTabs = false
        }
        movesSection.visibility = if (movesAvailable && !showSwitch) View.VISIBLE else View.GONE
        teamGrid.visibility = if (showSwitch) View.VISIBLE else View.GONE
        targetContainer.visibility = View.GONE
        if (scrollToTop) decisionScroll.scrollTo(0, 0)
    }

    private fun updateBackVisibility() {
        backButton.visibility = if (
                (request.teamPreview && decision.leadChoicesCount() > 0) ||
                (promptStage > 0 && !decision.hasOnlyPassChoice())) View.VISIBLE else View.GONE
    }

    private fun requiredLeadChoices(): Int {
        val fullTeamOrder = request.maxTeamSize < request.side.size ||
                request.side.any { it.baseAbility.toId() == "illusion" }
        return if (fullTeamOrder) min(request.maxTeamSize, request.side.size) else request.count
    }

    private fun toggleZMoves(toggle: Boolean) = moveButtons.forEach { button ->
        val move = button.getTag(R.id.battle_data_tag) as? Move ?: return@forEach
        move.zflag = toggle && move.canZMove
        refreshMoveButton(button, move)
    }

    private fun toggleMaxMoves(toggle: Boolean) = moveButtons.forEach { button ->
        val move = button.getTag(R.id.battle_data_tag) as? Move ?: return@forEach
        move.maxflag = toggle && move.maxMoveId != null
        refreshMoveButton(button, move)
    }

    private fun refreshMoveButton(button: MaterialButton, move: Move) {
        val details = when {
            move.maxflag -> move.maxDetails ?: move.details
            move.zflag -> move.zDetails ?: move.details
            else -> move.details
        }
        val enabled = when {
            move.maxflag -> move.maxMoveId != null
            move.zflag -> move.canZMove
            else -> !move.disabled
        }
        val color = details?.color?.takeIf { it != 0 } ?: defaultMoveTint
        button.apply {
            text = moveText(move, details)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.55f
            backgroundTintList = ColorStateList.valueOf(color)
            setTextColor(Colors.contrastTextColor(color))
        }
    }

    private fun moveText(move: Move, details: Move.Details?): CharSequence {
        val metadata = mutableListOf<String>()
        details?.type?.takeIf(String::isNotBlank)?.let { metadata.add(it.uppercase(Locale.ROOT)) }
        if (move.pp >= 0 && move.ppMax >= 0) {
            metadata.add(context.getString(R.string.battle_move_pp, move.pp, move.ppMax))
        }
        effectivenessLabel(details)?.let(metadata::add)
        val name = displayMoveName(move, move.zflag, move.maxflag)
        return if (metadata.isEmpty()) name else name concat "\n" concat metadata.joinToString(" · ").small()
    }

    private fun displayMoveName(move: Move, zMove: Boolean, maxMove: Boolean): String = when {
        maxMove -> move.maxDetails?.name ?: move.maxMoveName ?: move.name
        zMove -> move.zName ?: move.name
        else -> move.name
    }

    /** Defending types of the opposing active Pokémon, used to show per-move effectiveness. */
    fun setFoeDefendingTypes(types: List<String>?) {
        foeTypes = types
        refreshVisibleMoves()
    }

    private fun effectivenessLabel(details: Move.Details?): String? {
        val types = foeTypes ?: return null
        val type = details?.type ?: return null
        if (details.category.toId() == "status") return null
        val multiplier = Type.effectiveness(type, types)
        if (multiplier == 1.0) return null
        return "${formatMultiplier(multiplier)}×"
    }

    private fun formatMultiplier(multiplier: Double) = when (multiplier) {
        0.25 -> "¼"
        0.5 -> "½"
        else -> if (multiplier == multiplier.toLong().toDouble()) {
            multiplier.toLong().toString()
        } else multiplier.toString()
    }

    fun notifyDexIconsUpdated() = teamButtons.forEach { button ->
        val pokemon = button.getTag(R.id.battle_data_tag) as? SidePokemon ?: return@forEach
        pokemon.icon?.let { button.setDexIcon(it.toDrawable(resources)) }
    }

    fun notifyDetailsUpdated() = refreshVisibleMoves()

    fun notifyMaxDetailsUpdated() = refreshVisibleMoves()

    private fun refreshVisibleMoves() = moveButtons.forEach { button ->
        val move = button.getTag(R.id.battle_data_tag) as? Move ?: return@forEach
        refreshMoveButton(button, move)
    }

    internal fun setBattleViewFlipped(flipped: Boolean) {
        battleViewFlipped = flipped
        if (targetToChoose != null && targetContainer.visibility == View.VISIBLE) bindTargetRows()
    }

    override fun onClick(view: View) {
        if (revealingIn || revealingOut || isAnimatingContentAlpha || _decision == null) return
        when (val data = view.getTag(R.id.battle_data_tag)) {
            is Move -> {
                val selectedGimmick = gimmick.takeIf {
                    gimmickButton.visibility == View.VISIBLE && gimmickButton.isChecked
                } ?: Gimmick.NONE
                val moveName = displayMoveName(data, data.zflag, data.maxflag)
                decision.addMoveChoice(
                        data.index + 1,
                        selectedGimmick == Gimmick.MEGA,
                        selectedGimmick == Gimmick.Z_MOVE,
                        selectedGimmick == Gimmick.DYNAMAX,
                        selectedGimmick == Gimmick.TERA,
                        moveChoiceSummary(moveName, selectedGimmick))
                val target = when {
                    data.maxflag -> data.maxMoveTarget
                    data.zflag -> data.zDetails?.target ?: data.target
                    else -> data.target
                } ?: Move.Target.ALL
                if (request.count > 1 && target.isChoosable) {
                    targetToChoose = target
                    targetMoveName = moveName
                }
            }
            is BattlingPokemon -> {
                var index = data.id.position + 1
                if (!data.id.foe) index *= -1
                decision.setLastMoveTarget(
                        index,
                        moveChoiceSummary(
                                targetMoveName ?: context.getString(R.string.battle_move_target_fallback),
                                gimmicksByStage[promptStage] ?: Gimmick.NONE,
                                data.name))
                targetToChoose = null
                targetMoveName = null
            }
            is SidePokemon -> {
                val who = data.index + 1
                if (request.teamPreview) {
                    decision.addLeadChoice(who, request.side.size, data.name.trim())
                    if (decision.leadChoicesCount() < requiredLeadChoices()) {
                        bindTeamChoices(request.side, chooseLead = true)
                        updateChoiceHeader(chooseLead = true)
                        updateBackVisibility()
                        return
                    }
                } else {
                    val activeName = request.side.getOrNull(promptStage)?.name?.trim().orEmpty()
                    decision.addSwitchChoice(
                            who,
                            context.getString(R.string.battle_pending_switch, activeName, data.name.trim()))
                }
            }
        }
        promptNext()
    }

    private fun moveChoiceSummary(
            moveName: String,
            selectedGimmick: Gimmick,
            targetName: String? = null
    ): String {
        val pokemonName = request.side.getOrNull(promptStage)?.name?.trim().orEmpty()
        val action = when (selectedGimmick) {
            Gimmick.MEGA -> context.getString(R.string.battle_pending_move_mega, pokemonName, moveName)
            Gimmick.Z_MOVE -> context.getString(R.string.battle_pending_move_z, pokemonName, moveName)
            Gimmick.DYNAMAX -> context.getString(R.string.battle_pending_move_dynamax, pokemonName, moveName)
            Gimmick.TERA -> context.getString(
                    R.string.battle_pending_move_tera,
                    pokemonName,
                    request.canTerastallize(promptStage)?.uppercase(Locale.ROOT).orEmpty(),
                    moveName)
            Gimmick.NONE -> context.getString(R.string.battle_pending_move, pokemonName, moveName)
        }
        return action + targetName?.let {
            context.getString(R.string.battle_pending_move_target, it.trim())
        }.orEmpty() + "."
    }

    private fun animateContentChange(update: () -> Unit) {
        alphaAnimator.apply {
            setFloatValues(1f, 0f)
            duration = ANIM_NEXT_CHOICE_FADE_DURATION
            startDelay = 0
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = 1
            addListener(object : SimpleAnimatorListener() {
                override fun onAnimationStart(animator: Animator) {
                    isAnimatingContentAlpha = true
                }

                override fun onAnimationRepeat(animator: Animator) {
                    update()
                }

                override fun onAnimationEnd(animator: Animator) {
                    isAnimatingContentAlpha = false
                    alphaAnimator.removeListener(this)
                }
            })
        }.start()
    }

    private fun revealIn() {
        if (revealingIn || visibility == View.VISIBLE) return
        if (revealingOut) revealAnimator?.cancel()
        val viewDiagonal = hypot(width.toDouble(), height.toDouble()).toInt()
        revealAnimator = ViewAnimationUtils.createCircularReveal(
                this, 0, 0, 0f, viewDiagonal.toFloat()).apply {
            duration = ANIM_REVEAL_DURATION
            interpolator = AccelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    revealingIn = true
                    visibility = View.VISIBLE
                    contentAlpha = 0f
                }

                override fun onAnimationEnd(animation: Animator) {
                    revealingIn = false
                }

                override fun onAnimationCancel(animation: Animator) {
                    revealingIn = false
                }
            })
            start()
        }
        alphaAnimator.apply {
            setFloatValues(0f, 1f)
            duration = ANIM_REVEAL_FADE_DURATION
            startDelay = ANIM_REVEAL_DURATION
            repeatCount = 0
        }.start()
    }

    private fun revealOut() {
        if (revealingOut || visibility == View.GONE) return
        if (revealingIn) revealAnimator?.cancel()
        val viewDiagonal = hypot(width.toDouble(), height.toDouble()).toInt()
        revealAnimator = ViewAnimationUtils.createCircularReveal(
                this, 0, 0, viewDiagonal.toFloat(), 0f).apply {
            startDelay = ANIM_REVEAL_FADE_DURATION
            duration = ANIM_REVEAL_DURATION
            interpolator = AccelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    revealingOut = false
                    visibility = View.GONE
                }

                override fun onAnimationCancel(animation: Animator) {
                    revealingOut = false
                }
            })
            start()
        }
        alphaAnimator.apply {
            setFloatValues(1f, 0f)
            duration = ANIM_REVEAL_FADE_DURATION
            startDelay = 0
            repeatCount = 0
        }.start()
        revealingOut = true
    }

    fun dismiss() = revealOut()

    fun dismissNow() {
        visibility = View.GONE
        contentAlpha = 0f
        revealAnimator?.cancel()
    }

    private enum class Gimmick { NONE, MEGA, Z_MOVE, DYNAMAX, TERA }

    private data class ChoiceState(
            val label: CharSequence? = null,
            val description: String? = null,
            val selected: Boolean = false)

    companion object {
        private val CONTENT_ALPHA_PROPERTY = object : Property<BattleDecisionWidget, Float>(
                Float::class.java, "contentAlpha") {
            override fun get(widget: BattleDecisionWidget) = widget.contentAlpha
            override fun set(widget: BattleDecisionWidget, value: Float) {
                widget.contentAlpha = value
            }
        }

        private const val ANIM_REVEAL_DURATION = 250L
        private const val ANIM_REVEAL_FADE_DURATION = 100L
        private const val ANIM_NEXT_CHOICE_FADE_DURATION = 200L

        const val REVEAL_ANIMATION_DURATION = ANIM_REVEAL_DURATION + ANIM_REVEAL_FADE_DURATION
    }
}
