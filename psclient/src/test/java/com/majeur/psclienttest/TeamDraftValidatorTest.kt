package com.majeur.psclienttest

import com.majeur.psclient.model.common.BattleFormat
import com.majeur.psclient.model.common.Team
import com.majeur.psclient.model.common.TeamDraftValidator
import com.majeur.psclient.model.pokemon.TeamPokemon
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamDraftValidatorTest {
    @Test fun flagsGenerationFieldsAndDuplicateMoves() {
        val pokemon = TeamPokemon("Mew").apply {
            item = "Leftovers"
            ability = "Synchronize"
            nature = "timid"
            teraType = "Psychic"
            moves = listOf("psychic", "psychic")
        }
        val issues = TeamDraftValidator.validate(
                Team("Draft", listOf(pokemon), "gen1ou"),
                BattleFormat("[Gen 1] OU", 0, "gen1ou"))
        assertTrue(issues.any { "duplicate" in it })
        assertTrue(issues.any { "item" in it })
        assertTrue(issues.any { "ability" in it })
        assertTrue(issues.any { "nature" in it })
        assertTrue(issues.any { "Tera" in it })
    }
}
