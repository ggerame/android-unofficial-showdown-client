package com.majeur.psclienttest

import com.majeur.psclient.model.battle.StabBonus
import com.majeur.psclient.model.battle.calculateStab
import com.majeur.psclient.model.battle.effectiveMoveType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StabTest {

    @Test fun `calculates ordinary and tera stab`() {
        assertEquals(StabBonus.Multiplier(1.5), stab("water", "Water"))
        assertNull(stab("fire", "Water"))
        assertEquals(StabBonus.Multiplier(2.0), stab("water", "Water", "Water"))
        assertEquals(StabBonus.Multiplier(1.5), stab("water", "Fire", "Water"))
        assertEquals(StabBonus.Multiplier(1.5), stab("water", "Water", "Fire"))
    }

    @Test fun `applies adaptability to the active type`() {
        assertEquals(StabBonus.Multiplier(2.0), stab("water", "Water", ability = "Adaptability"))
        assertEquals(StabBonus.Multiplier(2.25), stab("water", "Water", "Water", "Adaptability"))
        assertEquals(StabBonus.Multiplier(2.0), stab("fire", "Water", "Fire", "Adaptability"))
        assertEquals(StabBonus.Multiplier(1.5), stab("water", "Water", "Fire", "Adaptability"))
    }

    @Test fun `uses cautious labels for stellar and variable moves`() {
        assertEquals(StabBonus.StellarStab, stab("water", "Water", "Stellar"))
        assertEquals(StabBonus.Stellar, stab("fire", "Water", "Stellar"))
        assertEquals(
                StabBonus.Unknown,
                calculateStab("weatherball", "Normal", "Special", listOf("Normal"), null, false, null))
    }

    @Test fun `omits status and fixed damage moves`() {
        assertNull(calculateStab("recover", "Normal", "Status", listOf("Normal"), null, false, null))
        assertNull(calculateStab("seismictoss", "Fighting", "Physical", listOf("Fighting"), null, false, null))
    }

    @Test fun `tera blast adopts the tera type only while tera is active`() {
        assertEquals("Normal", effectiveMoveType("terablast", "Normal", "Fire", false))
        assertEquals("Fire", effectiveMoveType("terablast", "Normal", "Fire", true))
    }

    private fun stab(
            moveType: String,
            originalType: String,
            teraType: String? = null,
            ability: String? = null
    ) = calculateStab(
            "surf", moveType, "Special", listOf(originalType), teraType,
            teraType != null, ability)
}
