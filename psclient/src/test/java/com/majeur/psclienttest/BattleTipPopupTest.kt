package com.majeur.psclienttest

import com.majeur.psclient.widget.TipTouchEndAction
import com.majeur.psclient.widget.tipTouchEndAction
import org.junit.Assert.assertEquals
import org.junit.Test

class BattleTipPopupTest {

    @Test fun `resolves tap cancellation and long press release`() {
        assertEquals(TipTouchEndAction.PERFORM_CLICK, tipTouchEndAction(false, false))
        assertEquals(TipTouchEndAction.IGNORE, tipTouchEndAction(false, true))
        assertEquals(TipTouchEndAction.KEEP_OPEN, tipTouchEndAction(true, false))
        assertEquals(TipTouchEndAction.KEEP_OPEN, tipTouchEndAction(true, true))
    }
}
