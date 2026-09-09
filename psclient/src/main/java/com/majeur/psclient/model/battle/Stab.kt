package com.majeur.psclient.model.battle

import com.majeur.psclient.util.toId

internal sealed class StabBonus {
    data class Multiplier(val value: Double) : StabBonus()
    object StellarStab : StabBonus()
    object Stellar : StabBonus()
    object Unknown : StabBonus()
}

internal fun effectiveMoveType(moveId: String, declaredType: String?, teraType: String?, teraActive: Boolean): String? =
        if (moveId.toId() == "terablast" && teraActive) teraType else declaredType

internal fun calculateStab(
        moveId: String,
        moveType: String?,
        category: String?,
        originalTypes: List<String>?,
        teraType: String?,
        teraActive: Boolean,
        ability: String?
): StabBonus? {
    if (category.orEmpty().toId() == "status" || moveId.toId() in NO_STAB_MOVES) return null
    if (originalTypes == null || moveType.isNullOrBlank()) return null
    if (moveId.toId() in VARIABLE_TYPE_MOVES) return StabBonus.Unknown

    val typeId = moveType.toId()
    val originalStab = originalTypes.any { it.toId() == typeId }
    if (!teraActive) {
        if (!originalStab) return null
        return StabBonus.Multiplier(if (ability.orEmpty().toId() == "adaptability") 2.0 else 1.5)
    }

    if (teraType.orEmpty().toId() == "stellar") {
        return if (originalStab) StabBonus.StellarStab else StabBonus.Stellar
    }

    val teraStab = teraType.orEmpty().toId() == typeId
    if (!originalStab && !teraStab) return null
    val multiplier = when {
        teraStab && originalStab && ability.orEmpty().toId() == "adaptability" -> 2.25
        teraStab && originalStab -> 2.0
        teraStab && ability.orEmpty().toId() == "adaptability" -> 2.0
        else -> 1.5
    }
    return StabBonus.Multiplier(multiplier)
}

private val VARIABLE_TYPE_MOVES = setOf(
        "aurawheel", "ivycudgel", "judgment", "multiattack", "naturalgift",
        "ragingbull", "revelationdance", "technoblast", "terrainpulse", "weatherball")

private val NO_STAB_MOVES = setOf(
        "bide", "counter", "dragonrage", "endeavor", "finalgambit", "fissure", "guardianofalola",
        "guillotine", "horndrill", "metalburst", "mirrorcoat", "naturesmadness", "nightshade",
        "psywave", "ruination", "seismictoss", "sheercold", "sonicboom", "struggle", "superfang")
