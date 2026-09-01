package com.majeur.psclienttest

import com.majeur.psclient.model.battle.BattleDecision
import com.majeur.psclient.model.battle.Move
import com.majeur.psclient.model.battle.resolveMoveDetailsId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BattleDecisionTest {

    @Test
    fun `loads the typed Hidden Power details instead of the Normal base record`() {
        assertEquals("hiddenpowerice", resolveMoveDetailsId("hiddenpower", "Hidden Power Ice"))
        assertEquals("hiddenpower", resolveMoveDetailsId("hiddenpower", "Hidden Power"))
        assertEquals("flamethrower", resolveMoveDetailsId("flamethrower", "Flamethrower"))
    }

    @Test
    fun `builds multi choice commands with extras and signed targets`() {
        val decision = BattleDecision()

        decision.addMoveChoice(2, mega = true, zmove = false, dynamax = false, tera = false)
        decision.setLastMoveTarget(-1)
        decision.addSwitchChoice(4)

        assertEquals("choose", decision.command)
        assertEquals("move 2 mega -1,switch 4", decision.build())
    }

    @Test
    fun `builds every move gimmick without changing the wire format`() {
        val choices = listOf(
                booleanArrayOf(true, false, false, false) to "move 1 mega",
                booleanArrayOf(false, true, false, false) to "move 1 zmove",
                booleanArrayOf(false, false, true, false) to "move 1 dynamax",
                booleanArrayOf(false, false, false, true) to "move 1 terastallize")

        choices.forEach { (flags, expected) ->
            val decision = BattleDecision()
            decision.addMoveChoice(1, flags[0], flags[1], flags[2], flags[3])
            assertEquals(expected, decision.build())
        }
    }

    @Test
    fun `removes the latest staged choice`() {
        val decision = BattleDecision()
        decision.addMoveChoice(1, mega = false, zmove = false, dynamax = false, tera = false)
        decision.addSwitchChoice(3)

        decision.removeLastChoice()

        assertEquals("move 1", decision.build())
        assertEquals(0, decision.switchChoicesCount())
    }

    @Test
    fun `tracks and removes numbered team preview choices`() {
        val decision = BattleDecision()
        decision.addLeadChoice(4, teamSize = 6)
        decision.addLeadChoice(2, teamSize = 6)

        assertEquals("team", decision.command)
        assertEquals(1, decision.leadChoicePosition(4))
        assertEquals(2, decision.leadChoicePosition(2))
        assertEquals("421356", decision.build())

        decision.removeLastChoice()

        assertNull(decision.leadChoicePosition(2))
        assertEquals("412356", decision.build())
    }

    @Test
    fun `keeps triple adjacent targets spatially correct`() {
        val adjacentFoe = Move.Target.computeTargetAvailabilities(
                Move.Target.ADJACENT_FOE, position = 0, pokeCount = 3)
        assertArrayEquals(booleanArrayOf(true, true, false), adjacentFoe[0])
        assertArrayEquals(booleanArrayOf(false, false, false), adjacentFoe[1])

        val adjacentAlly = Move.Target.computeTargetAvailabilities(
                Move.Target.ADJACENT_ALLY, position = 1, pokeCount = 3)
        assertArrayEquals(booleanArrayOf(false, false, false), adjacentAlly[0])
        assertArrayEquals(booleanArrayOf(true, false, true), adjacentAlly[1])
    }

    @Test
    fun `normal targets exclude only the user on its own side`() {
        val targets = Move.Target.computeTargetAvailabilities(
                Move.Target.NORMAL, position = 1, pokeCount = 3)

        assertArrayEquals(booleanArrayOf(true, true, true), targets[0])
        assertArrayEquals(booleanArrayOf(true, false, true), targets[1])
    }
}
