package com.majeur.psclienttest

import com.majeur.psclient.model.common.BattleFormatParser
import com.majeur.psclient.model.common.FormatProfile
import org.junit.Assert.*
import org.junit.Test

class BattleFormatParserTest {
    @Test fun parsesSectionsFlagsAndProfiles() {
        val categories = BattleFormatParser.parse(listOf(
                ",1", "S/V Singles", "[Gen 9] OU,e", "[Gen 9] Random Battle,f",
                ",2", "S/V Doubles", "[Gen 9] VGC 2026 Reg F,1e"))
        assertEquals(2, categories.size)
        assertEquals(2, categories[1].column)
        val ou = categories[0].formats[0]
        assertEquals("gen9ou", ou.id)
        assertTrue(ou.isTeamNeeded)
        assertTrue(ou.isSearchShow)
        assertTrue(ou.isChallengeShow)
        assertTrue(ou.isTournamentShow)
        assertFalse(categories[0].formats[1].isTeamNeeded)
        val vgc = categories[1].formats.single()
        assertEquals(9, vgc.profile.generation)
        assertEquals(50, vgc.defaultLevel)
    }

    @Test fun generationControlsAvailableFields() {
        val gen1 = FormatProfile.from("gen1ou")
        assertFalse(gen1.hasItems)
        assertFalse(gen1.hasAbilities)
        assertFalse(gen1.hasNatures)
        val gen8 = FormatProfile.from("gen8ou")
        assertTrue(gen8.hasDynamax)
        assertFalse(gen8.hasTera)
        assertTrue(FormatProfile.from("gen9ou").hasTera)
    }

    @Test fun formatSuffixDigitsAreNotParsedAsPartOfTheGeneration() {
        assertEquals(9, FormatProfile.from("gen912switch").generation)
        assertEquals(8, FormatProfile.from("gen81v1").generation)
        assertEquals(6, FormatProfile.from("customgame").generation)
    }

    @Test fun mirrorsOfficialSpecialModControls() {
        val letsGo = FormatProfile.from("gen7letsgoou")
        assertFalse(letsGo.hasItems)
        assertFalse(letsGo.hasAbilities)
        assertEquals(50, letsGo.defaultLevel)
        assertFalse(FormatProfile.from("gen8bdspou").hasDynamax)
        assertFalse(FormatProfile.from("gen9champions").hasTera)
    }
}
