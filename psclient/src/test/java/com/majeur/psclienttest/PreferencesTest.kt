package com.majeur.psclienttest

import android.content.Context
import android.content.SharedPreferences
import com.majeur.psclient.util.HomeBackground
import com.majeur.psclient.util.Preferences
import com.majeur.psclient.util.chooseHomeArtwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import kotlin.random.Random

@RunWith(MockitoJUnitRunner::class)
class PreferencesTest {

    @Mock lateinit var context: Context
    @Mock lateinit var sharedPreferences: SharedPreferences
    @Mock lateinit var editor: SharedPreferences.Editor

    @Before fun setUp() {
        Mockito.`when`(context.getSharedPreferences("user-preferences", Context.MODE_PRIVATE))
                .thenReturn(sharedPreferences)
        Mockito.`when`(sharedPreferences.edit()).thenReturn(editor)
        Mockito.`when`(editor.putString(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(editor)
        Mockito.`when`(editor.remove(Mockito.anyString())).thenReturn(editor)
    }

    @Test fun legacyNeutralBackgroundIsPreserved() {
        Mockito.`when`(sharedPreferences.contains("home-background")).thenReturn(false)
        Mockito.`when`(sharedPreferences.getBoolean("showdown-home-background-enabled", true))
                .thenReturn(false)

        assertEquals(HomeBackground.NEUTRAL, Preferences.getHomeBackground(context))
        Mockito.verify(editor).putString("home-background", HomeBackground.NEUTRAL.name)
        Mockito.verify(editor).remove("showdown-home-background-enabled")
    }

    @Test fun unknownBackgroundFallsBackToRandom() {
        Mockito.`when`(sharedPreferences.contains("home-background")).thenReturn(true)
        Mockito.`when`(sharedPreferences.getString("home-background", null))
                .thenReturn("FUTURE_MODE")

        assertEquals(HomeBackground.RANDOM, Preferences.getHomeBackground(context))
    }

    @Test fun randomArtworkDoesNotRepeatPreviousResult() {
        val artworks = listOf(HomeBackground.HORIZON, HomeBackground.OCEAN,
                HomeBackground.SHAYMIN, HomeBackground.CHARIZARDS)
        for (last in artworks) {
            repeat(20) { seed ->
                val selected = chooseHomeArtwork(last, Random(seed))
                assertTrue(selected in artworks)
                assertNotEquals(last, selected)
            }
        }
    }
}
