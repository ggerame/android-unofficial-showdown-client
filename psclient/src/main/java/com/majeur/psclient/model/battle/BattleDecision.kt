package com.majeur.psclient.model.battle


class BattleDecision {

    private var _command: String? = null

    val command: String get() {
        if (_command == null) throw NullPointerException("BattleDecision has no command set")
        return _command!!
    }

    private val choices = mutableListOf<Choice>()
    private var teamSize = 0

    private data class Choice(
            val action: String = "",
            val index: Int = 0,
            val extra: String? = null,
            var target: Int = 0,
            var summary: String? = null
    )

    /* 1 based index */
    fun addSwitchChoice(who: Int) = addSwitchChoice(who, null)

    internal fun addSwitchChoice(who: Int, summary: String?) {
        _command = CMD_CHOOSE
        choices.add(Choice(action = ACTION_SWITCH, index = who, summary = summary))
    }

    /* 1 based index */
    fun addMoveChoice(which: Int, mega: Boolean, zmove: Boolean, dynamax: Boolean, tera: Boolean) =
            addMoveChoice(which, mega, zmove, dynamax, tera, null)

    internal fun addMoveChoice(which: Int, mega: Boolean, zmove: Boolean, dynamax: Boolean, tera: Boolean,
                               summary: String?) {
        _command = CMD_CHOOSE
        val extra = when {
            mega -> EXTRA_MEGA
            zmove -> EXTRA_ZMOVE
            dynamax -> EXTRA_DYNAMAX
            tera -> EXTRA_TERA
            else -> null
        }
        choices.add(Choice(action = ACTION_MOVE, index = which, extra = extra, summary = summary))
    }

    /* 1 based index */
    fun setLastMoveTarget(target: Int) = setLastMoveTarget(target, null)

    internal fun setLastMoveTarget(target: Int, summary: String?) {
        choices.last().apply {
            this.target = target
            if (summary != null) this.summary = summary
        }
    }

    fun addPassChoice() {
        _command = CMD_CHOOSE
        choices.add(Choice(action = ACTION_PASS))
    }

    /* 1 based index */
    fun addLeadChoice(first: Int, teamSize: Int) = addLeadChoice(first, teamSize, null)

    internal fun addLeadChoice(first: Int, teamSize: Int, summary: String?) {
        _command = CMD_TEAM
        this.teamSize = teamSize
        choices.add(Choice(index = first, summary = summary))
    }

    internal fun summaryLines() = choices.mapNotNull(Choice::summary)

    fun leadChoicesCount() = choices.count { it.action.isEmpty() }

    internal fun leadChoicePosition(which: Int): Int? = choices
            .indexOfFirst { it.action.isEmpty() && it.index == which }
            .takeIf { it >= 0 }
            ?.plus(1)

    fun switchChoicesCount() = choices.count { it.action == ACTION_SWITCH }

    /* 1 based index */
    fun hasSwitchChoice(which: Int) = choices.any { it.action == ACTION_SWITCH && it.index == which }

    fun hasOnlyPassChoice() = choices.all { it.action == ACTION_PASS }

    fun hasMegaChoices() = choices.any { it.extra == EXTRA_MEGA }

    fun hasZMoveChoices() = choices.any { it.extra == EXTRA_ZMOVE }

    fun hasDynamaxChoices() = choices.any { it.extra == EXTRA_DYNAMAX }

    fun hasTeraChoices() = choices.any { it.extra == EXTRA_TERA }

    fun lastChoiceWasMoveTarget() = choices.lastOrNull()?.let { it.action == ACTION_MOVE && it.target != 0 } ?: false

    /* 1 based index */
    fun lastChoiceMove() = choices.lastOrNull()?.takeIf { it.action == ACTION_MOVE }?.index ?: 0

    fun lastChoiceWasZ() = choices.lastOrNull()?.extra == EXTRA_ZMOVE

    fun lastChoiceWasDynamax() = choices.lastOrNull()?.extra == EXTRA_DYNAMAX

    fun removeLastChoice() {
        choices.lastOrNull()?.let { choices.remove(it) }
    }

    fun build(): String {
        return StringBuilder().run {
            if (_command == CMD_TEAM) {
                choices.forEach { c -> append(c.index) }
                (1..teamSize).forEach { if (!contains(it.toString())) append(it) }
            } else {
                choices.forEach { c ->
                    append(c.action)
                    if (c.index != 0) append(" ").append(c.index)
                    if (c.extra != null) append(" ").append(c.extra)
                    if (c.target != 0) append(" ").append(c.target)
                    append(",")
                }
            }
            toString().removeSuffix(",")
        }
    }

    companion object {
        private const val CMD_CHOOSE = "choose"
        private const val CMD_TEAM = "team"
        private const val ACTION_MOVE = "move"
        private const val ACTION_SWITCH = "switch"
        private const val ACTION_PASS = "pass"
        private const val EXTRA_MEGA = "mega"
        private const val EXTRA_ZMOVE = "zmove"
        private const val EXTRA_DYNAMAX = "dynamax"
        private const val EXTRA_TERA = "terastallize"
    }
}
