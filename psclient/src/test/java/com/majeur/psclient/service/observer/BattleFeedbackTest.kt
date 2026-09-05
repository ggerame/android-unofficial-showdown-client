package com.majeur.psclient.service.observer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleFeedbackTest {

    @Test fun moveAnimationSuppressorsWorkIndependently() {
        listOf("miss", "notarget", "still").forEach { tag ->
            assertFalse(shouldAnimateMove(setOf(tag)))
        }
        assertFalse(shouldAnimateMove(setOf("miss", "from")))
        assertTrue(shouldAnimateMove(emptySet()))
        assertTrue(shouldAnimateMove(setOf("from", "of")))
    }

    @Test fun blockedMoveToastsCoverOnlyProtectionMoves() {
        assertEquals("Protected", blockedMoveToast("move: Protect"))
        assertEquals("Quick Guard", blockedMoveToast("Quick Guard"))
        assertEquals("Wide Guard", blockedMoveToast("move: Wide Guard"))
        assertEquals("Crafty Shield", blockedMoveToast("move: Crafty Shield"))
        assertNull(blockedMoveToast("item: Safety Goggles"))
        assertNull(blockedMoveToast("ability: Bulletproof"))
        assertNull(blockedMoveToast(null))
    }
}
