package com.majeur.psclienttest

import com.majeur.psclient.widget.containmentOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class BattleLayoutGeometryTest {

    @Test fun containmentOffsetKeepsIntervalsInsideTheVisibleArea() {
        assertEquals(0, containmentOffset(20, 80, 100))
        assertEquals(10, containmentOffset(-10, 50, 100))
        assertEquals(-10, containmentOffset(50, 110, 100))
        assertEquals(0, containmentOffset(0, 100, 100))
    }
}
