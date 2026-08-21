package com.majeur.psclient.model.common

import com.majeur.psclient.util.toId

object TeamDraftValidator {
    fun validate(team: Team, format: BattleFormat?): List<String> {
        val issues = mutableListOf<String>()
        if (format == null || format.id == "other") issues += "Choose a real battle format"
        if (team.pokemons.isEmpty()) issues += "The team has no Pokémon"
        if (team.pokemons.size > 6) issues += "The editor supports at most 6 Pokémon"
        val profile = format?.profile ?: return issues
        team.pokemons.forEachIndexed { index, pokemon ->
            val subject = pokemon.name.ifBlank { pokemon.species }.ifBlank { "Slot ${index + 1}" }
            if (pokemon.species.isBlank()) issues += "$subject has no species"
            val moves = pokemon.moves.filter { it.isNotBlank() }.map { it.toId() }
            if (moves.size != moves.distinct().size) issues += "$subject has duplicate moves"
            if (pokemon.level !in 1..100) issues += "$subject has an invalid level"
            if (pokemon.happiness !in 0..255) issues += "$subject has invalid happiness"
            if (profile.generation >= 3 && pokemon.evs.sum() > 510) issues += "$subject has more than 510 EVs"
            if (!profile.hasItems && pokemon.item.isNotBlank()) issues += "$subject cannot use an item in this generation"
            if (!profile.hasAbilities && pokemon.ability.isNotBlank()) issues += "$subject cannot use an ability in this generation"
            if (!profile.hasNatures && pokemon.nature.isNotBlank()) issues += "$subject cannot use a nature in this generation"
            if (!profile.hasTera && pokemon.teraType.isNotBlank()) issues += "$subject cannot use a Tera Type in this format"
            if (!profile.hasDynamax && (pokemon.gigantamax || pokemon.dynamaxLevel != 10))
                issues += "$subject cannot use Dynamax settings in this format"
        }
        return issues
    }
}
