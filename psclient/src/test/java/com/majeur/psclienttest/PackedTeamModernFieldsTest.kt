package com.majeur.psclienttest

import com.majeur.psclient.model.common.Team
import com.majeur.psclient.model.pokemon.TeamPokemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackedTeamModernFieldsTest {
    @Test fun roundTripsOfficialMiscFieldOrder() {
        val pokemon = TeamPokemon("Charizard").apply {
            moves = listOf("flamethrower")
            happiness = 200
            pokeball = "Luxury Ball"
            hpType = "Ice"
            gigantamax = true
            dynamaxLevel = 7
            teraType = "Fire"
        }
        val packed = Team("Modern", listOf(pokemon), "gen9ou").pack()
        val result = Team.unpack("Modern", "gen9ou", packed)!!.pokemons.single()
        assertEquals("luxuryball", result.pokeball)
        assertEquals("Ice", result.hpType)
        assertTrue(result.gigantamax)
        assertEquals(7, result.dynamaxLevel)
        assertEquals("Fire", result.teraType)
    }

    @Test fun migratesLegacyHpTypeAndPokeballOrder() {
        val legacy = "Pikachu||||thunderbolt|||||||255,Ice,luxuryball,,,Electric"
        val result = Team.unpack("Legacy", "gen9ou", legacy, legacyMiscOrder = true)!!.pokemons.single()
        assertEquals("luxuryball", result.pokeball)
        assertEquals("Ice", result.hpType)
    }
}
