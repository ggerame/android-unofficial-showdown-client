package com.majeur.psclienttest

import com.majeur.psclient.widget.containmentOffset
import com.majeur.psclient.widget.SideTagAnchor
import com.majeur.psclient.widget.SideTagBounds
import com.majeur.psclient.widget.sideTagAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BattleLayoutGeometryTest {

    @Test fun containmentOffsetKeepsIntervalsInsideTheVisibleArea() {
        assertEquals(0, containmentOffset(20, 80, 100))
        assertEquals(10, containmentOffset(-10, 50, 100))
        assertEquals(-10, containmentOffset(50, 110, 100))
        assertEquals(0, containmentOffset(0, 100, 100))
    }

    @Test fun sideTagsAnchorAboveTheirTeamsHealthBars() {
        val bars = listOf(SideTagBounds(30, 60, 150), SideTagBounds(180, 40, 300))

        assertEquals(SideTagAnchor(30, 36), sideTagAnchor(bars, onLeft = true, gap = 4))
        assertEquals(SideTagAnchor(300, 36), sideTagAnchor(bars, onLeft = false, gap = 4))
        assertNull(sideTagAnchor(emptyList(), onLeft = true, gap = 4))
    }
}
