package com.majeur.psclient.io

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationFxTest {

    @Test fun showdownFxAliasesResolveToTheirRealFileNames() {
        assertEquals("energyball.png", animFxFileName("energyball"))
        assertEquals("hitmarker.png", animFxFileName("hitmark"))
        assertEquals("icicle-pink.png", animFxFileName("pinkicicle"))
        assertEquals("z-symbol.png", animFxFileName("zsymbol"))
    }
}
