package com.majeur.psclienttest

import com.majeur.psclient.model.common.BattleFormatParser
import com.majeur.psclient.ui.filterFormatCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatSelectorTest {
    @Test fun filtersByFormatNameIdAndSection() {
        val categories = BattleFormatParser.parse(listOf(
                ",1", "S/V Singles", "[Gen 9] OU,e", "[Gen 9] Monotype,e",
                ",2", "S/V Doubles", "[Gen 9] VGC 2026 Reg F,e"))
                .map { it to it.formats.toList() }

        assertEquals("gen9monotype", filterFormatCategories(categories, " Monotype ").single().second.single().id)
        assertEquals("gen9ou", filterFormatCategories(categories, "gen9ou").single().second.single().id)
        assertEquals("gen9ou", filterFormatCategories(categories, "OU").single().second.single().id)
        assertEquals(3, filterFormatCategories(categories, "Gen").sumOf { it.second.size })
        assertEquals("gen9vgc2026regf", filterFormatCategories(categories, "regf").single().second.single().id)
        assertEquals(1, filterFormatCategories(categories, "doubles").single().second.size)
        assertTrue(filterFormatCategories(categories, "missing").isEmpty())
    }
}
