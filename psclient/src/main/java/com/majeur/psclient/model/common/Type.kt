package com.majeur.psclient.model.common

import com.majeur.psclient.R
import java.util.*


object Type {

    val ALL = arrayOf(
            "Bug",
            "Dark",
            "Dragon",
            "Electric",
            "Fighting",
            "Fire",
            "Flying",
            "Ghost",
            "Grass",
            "Ground",
            "Ice",
            "Poison",
            "Psychic",
            "Rock",
            "Steel",
            "Water",
            "Normal",
            "Fairy"
    )

    val HP_TYPES = arrayOf(
            "Dark",
            "Bug",
            "Dragon",
            "Electric",
            "Fighting",
            "Fire",
            "Flying",
            "Ghost",
            "Grass",
            "Ground",
            "Ice",
            "Poison",
            "Psychic",
            "Rock",
            "Steel",
            "Water")

    fun getResId(rawType: String?) = when (rawType?.trim()?.toLowerCase(Locale.ROOT)) {
        "bug" -> R.drawable.ic_type_bug
        "dark" -> R.drawable.ic_type_dark
        "dragon" -> R.drawable.ic_type_dragon
        "electric" -> R.drawable.ic_type_electric
        "fighting" -> R.drawable.ic_type_fighting
        "fire" -> R.drawable.ic_type_fire
        "flying" -> R.drawable.ic_type_flying
        "ghost" -> R.drawable.ic_type_ghost
        "grass" -> R.drawable.ic_type_grass
        "ground" -> R.drawable.ic_type_ground
        "ice" -> R.drawable.ic_type_ice
        "poison" -> R.drawable.ic_type_poison
        "psychic" -> R.drawable.ic_type_psychic
        "rock" -> R.drawable.ic_type_rock
        "steel" -> R.drawable.ic_type_steel
        "water" -> R.drawable.ic_type_water
        "normal" -> R.drawable.ic_type_normal
        "fairy" -> R.drawable.ic_type_fairy
        else -> R.drawable.ic_type_unknown
    }

    /** Combined damage multiplier of an attacking [attackType] against a Pokémon with [defenderTypes]. */
    fun effectiveness(attackType: String?, defenderTypes: List<String>): Double {
        if (attackType == null) return 1.0
        var multiplier = 1.0
        for (type in defenderTypes) multiplier *= singleEffectiveness(attackType, type)
        return multiplier
    }

    private fun singleEffectiveness(attackType: String, defenderType: String) =
            when (attackType.trim().toLowerCase(Locale.ROOT)) {
                "normal" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "rock", "steel" -> .5; "ghost" -> .0; else -> 1.0 }
                "fire" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "grass", "ice", "bug", "steel" -> 2.0; "fire", "water", "rock", "dragon" -> .5; else -> 1.0 }
                "water" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "fire", "ground", "rock" -> 2.0; "water", "grass", "dragon" -> .5; else -> 1.0 }
                "electric" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "water", "flying" -> 2.0; "electric", "grass", "dragon" -> .5; "ground" -> .0; else -> 1.0 }
                "grass" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "water", "ground", "rock" -> 2.0; "fire", "grass", "poison", "flying", "bug", "dragon", "steel" -> .5; else -> 1.0 }
                "ice" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "grass", "ground", "flying", "dragon" -> 2.0; "fire", "water", "ice", "steel" -> .5; else -> 1.0 }
                "fighting" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "normal", "ice", "rock", "dark", "steel" -> 2.0; "poison", "flying", "psychic", "bug", "fairy" -> .5; "ghost" -> .0; else -> 1.0 }
                "poison" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "grass", "fairy" -> 2.0; "poison", "ground", "rock", "ghost" -> .5; "steel" -> .0; else -> 1.0 }
                "ground" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "fire", "electric", "poison", "rock", "steel" -> 2.0; "grass", "bug" -> .5; "flying" -> .0; else -> 1.0 }
                "flying" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "grass", "fighting", "bug" -> 2.0; "electric", "rock", "steel" -> .5; else -> 1.0 }
                "psychic" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "fighting", "poison" -> 2.0; "psychic", "steel" -> .5; "dark" -> .0; else -> 1.0 }
                "bug" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "grass", "psychic", "dark" -> 2.0; "fire", "fighting", "poison", "flying", "ghost", "steel", "fairy" -> .5; else -> 1.0 }
                "rock" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "fire", "ice", "flying", "bug" -> 2.0; "fighting", "ground", "steel" -> .5; else -> 1.0 }
                "ghost" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "psychic", "ghost" -> 2.0; "dark" -> .5; "normal" -> .0; else -> 1.0 }
                "dragon" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "dragon" -> 2.0; "steel" -> .5; "fairy" -> .0; else -> 1.0 }
                "dark" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "psychic", "ghost" -> 2.0; "fighting", "dark", "fairy" -> .5; else -> 1.0 }
                "steel" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "ice", "rock", "fairy" -> 2.0; "fire", "water", "electric", "steel" -> .5; else -> 1.0 }
                "fairy" -> when (defenderType.trim().toLowerCase(Locale.ROOT)) { "fighting", "dragon", "dark" -> 2.0; "fire", "poison", "steel" -> .5; else -> 1.0 }
                else -> 1.0
            }
}
