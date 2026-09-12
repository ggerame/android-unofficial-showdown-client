package com.majeur.psclient.battleanim

import org.junit.Assert.assertEquals
import org.junit.Test

class BattleAnimTimingTest {

    @Test fun effectKeyframesUseAbsoluteTimes() {
        assertEquals(EffectTiming(0, 500), effectTiming(null, null))
        assertEquals(EffectTiming(125, 500), effectTiming(125, null))
        assertEquals(EffectTiming(250, 500), effectTiming(250, 0))
        assertEquals(EffectTiming(0, 400), effectTiming(null, 400))
    }

    @Test fun endScaleControlsAxesUnlessAnAxisWasExplicitlySet() {
        assertEquals(0.6f, effectEndAxisScale(0.1f, null, 0.6f, null))
        assertEquals(0.4f, effectEndAxisScale(0.1f, 0.4f, 0.6f, null))
        assertEquals(0.8f, effectEndAxisScale(0.1f, 0.4f, 0.6f, 0.8f))
    }
}
