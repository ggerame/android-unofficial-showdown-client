package com.majeur.psclienttest

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.majeur.psclient.model.common.Colors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito

class ColorsContrastTest {

    private lateinit var androidColor: MockedStatic<Color>

    @Before fun mockAndroidColorChannels() {
        androidColor = Mockito.mockStatic(Color::class.java)
        androidColor.`when`<Int> { Color.alpha(Mockito.anyInt()) }
                .thenAnswer { (it.getArgument<Int>(0) ushr 24) and 0xff }
        androidColor.`when`<Int> { Color.red(Mockito.anyInt()) }
                .thenAnswer { (it.getArgument<Int>(0) ushr 16) and 0xff }
        androidColor.`when`<Int> { Color.green(Mockito.anyInt()) }
                .thenAnswer { (it.getArgument<Int>(0) ushr 8) and 0xff }
        androidColor.`when`<Int> { Color.blue(Mockito.anyInt()) }
                .thenAnswer { it.getArgument<Int>(0) and 0xff }
    }

    @After fun closeAndroidColorMock() {
        androidColor.close()
    }

    @Test fun battlePaletteMeetsSmallTextContrast() {
        val backgrounds = listOf(
                Colors.GREEN, Colors.YELLOW, Colors.RED, Colors.BLUE, Colors.GRAY,
                Colors.STAT_BOOST, Colors.STAT_UNBOOST,
                Colors.VOLATILE_STATUS, Colors.VOLATILE_GOOD,
                Colors.VOLATILE_NEUTRAL, Colors.VOLATILE_BAD,
                Colors.TYPE_NORMAL, Colors.TYPE_FIRE, Colors.TYPE_WATER,
                Colors.TYPE_ELECTRIC, Colors.TYPE_GRASS, Colors.TYPE_ICE,
                Colors.TYPE_FIGHTING, Colors.TYPE_POISON, Colors.TYPE_GROUND,
                Colors.TYPE_FLYING, Colors.TYPE_PSYCHIC, Colors.TYPE_BUG,
                Colors.TYPE_ROCK, Colors.TYPE_GHOST, Colors.TYPE_DRAGON,
                Colors.TYPE_DARK, Colors.TYPE_STEEL, Colors.TYPE_FAIRY)

        backgrounds.forEach { background ->
            val foreground = Colors.contrastTextColor(background)
            assertTrue("Insufficient contrast for ${background.toUInt().toString(16)}",
                    ColorUtils.calculateContrast(foreground, background) >= 4.5)
        }
    }

    @Test fun lightAndDarkTypeColorsChooseExpectedForeground() {
        listOf(Colors.TYPE_ELECTRIC, Colors.TYPE_ICE, Colors.TYPE_STEEL).forEach {
            assertEquals(Colors.BLACK, Colors.contrastTextColor(it))
        }
        listOf(Colors.TYPE_FIGHTING, Colors.TYPE_POISON, Colors.TYPE_GHOST).forEach {
            assertEquals(Colors.WHITE, Colors.contrastTextColor(it))
        }
    }
}
