package com.majeur.psclienttest

import com.majeur.psclient.model.common.Stats
import com.majeur.psclient.model.common.Type
import com.majeur.psclient.ui.teambuilder.MovesFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HiddenPowerTest {

    @Test fun recognizesCanonicalAndExportedMoveNames() {
        assertEquals("Ice", Type.hiddenPowerType("hiddenpowerice"))
        assertEquals("Fire", Type.hiddenPowerType("Hidden Power [Fire]"))
        assertNull(Type.hiddenPowerType("Hidden Power"))
        assertNull(Type.hiddenPowerType("hiddenpowerfairy"))
    }

    @Test fun expandsOnlyFormatsThatSupportHiddenPower() {
        val moves = listOf("tackle", "hiddenpower")
        val expanded = MovesFragment.withHiddenPowerVariants(moves, true)

        assertEquals(18, expanded.size)
        assertEquals("hiddenpower", expanded[1])
        assertEquals("hiddenpowerice", expanded.first { it == "hiddenpowerice" })
        assertEquals(listOf("tackle"), MovesFragment.withHiddenPowerVariants(moves, false))
    }

    @Test fun usesTheGenerationTwoDvFormula() {
        val stats = Stats(31).apply { def = 27 }

        assertEquals("Ice", stats.hpType(2))
        assertEquals("Dark", stats.hpType(3))
    }
}
