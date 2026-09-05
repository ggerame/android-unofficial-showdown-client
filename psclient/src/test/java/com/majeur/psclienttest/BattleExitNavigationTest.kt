package com.majeur.psclienttest

import com.majeur.psclient.ui.BattleExitDestination
import com.majeur.psclient.ui.battleExitDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class BattleExitNavigationTest {

    @Test
    fun `exit destination follows battle mode`() {
        assertEquals(BattleExitDestination.HOME,
                battleExitDestination(isReplay = false, isUserPlaying = true))
        assertEquals(BattleExitDestination.BATTLE_SEARCH,
                battleExitDestination(isReplay = false, isUserPlaying = false))
        assertEquals(BattleExitDestination.REPLAY_SEARCH,
                battleExitDestination(isReplay = true, isUserPlaying = true))
    }
}
