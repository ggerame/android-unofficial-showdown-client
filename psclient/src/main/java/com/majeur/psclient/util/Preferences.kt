package com.majeur.psclient.util

import android.content.Context
import kotlin.random.Random

enum class HomeBackground {
    RANDOM, HORIZON, OCEAN, SHAYMIN, CHARIZARDS, NEUTRAL
}

internal fun chooseHomeArtwork(
        last: HomeBackground?,
        random: Random = Random.Default
): HomeBackground = HomeBackground.entries
        .filter { it != HomeBackground.RANDOM && it != HomeBackground.NEUTRAL && it != last }
        .random(random)

object Preferences {

    private const val PREF_NAME = "user-preferences"
    private const val KEY_NEWS_BANNER = "news-banner-enabled"
    private const val KEY_BATTLE_SOUND = "battle-sound-enabled"
    private const val KEY_FAVORITE_BATTLE_FORMATS = "favorite-battle-formats"
    private const val KEY_HOME_BACKGROUND = "home-background"
    private const val KEY_LAST_RANDOM_HOME_BACKGROUND = "last-random-home-background"
    private const val KEY_LEGACY_SHOWDOWN_HOME_BACKGROUND = "showdown-home-background-enabled"

    fun isNewsBannerEnabled(c: Context) = readBool(c, KEY_NEWS_BANNER, true)
    fun setNewsBannerEnabled(c: Context, value: Boolean) = writeBool(c, KEY_NEWS_BANNER, value)

    fun isBattleSoundEnabled(c: Context) = readBool(c, KEY_BATTLE_SOUND, true)
    fun setBattleSoundEnabled(c: Context, value: Boolean) = writeBool(c, KEY_BATTLE_SOUND, value)

    fun getFavoriteBattleFormats(c: Context): Set<String> =
            get(c).getStringSet(KEY_FAVORITE_BATTLE_FORMATS, emptySet()).orEmpty().toSet()

    fun toggleFavoriteBattleFormat(c: Context, formatId: String): Boolean {
        val favorites = getFavoriteBattleFormats(c).toMutableSet()
        val isFavorite = if (favorites.remove(formatId)) false else {
            favorites += formatId
            true
        }
        get(c).edit().putStringSet(KEY_FAVORITE_BATTLE_FORMATS, favorites).apply()
        return isFavorite
    }

    fun getHomeBackground(c: Context): HomeBackground {
        val preferences = get(c)
        if (!preferences.contains(KEY_HOME_BACKGROUND)) {
            val migrated = if (preferences.getBoolean(KEY_LEGACY_SHOWDOWN_HOME_BACKGROUND, true))
                HomeBackground.RANDOM else HomeBackground.NEUTRAL
            setHomeBackground(c, migrated)
            return migrated
        }
        return parseHomeBackground(preferences.getString(KEY_HOME_BACKGROUND, null))
                ?: HomeBackground.RANDOM
    }

    fun setHomeBackground(c: Context, value: HomeBackground) = get(c).edit()
            .putString(KEY_HOME_BACKGROUND, value.name)
            .remove(KEY_LEGACY_SHOWDOWN_HOME_BACKGROUND)
            .apply()

    fun getLastRandomHomeBackground(c: Context) =
            parseHomeBackground(get(c).getString(KEY_LAST_RANDOM_HOME_BACKGROUND, null))
                    ?.takeIf { it != HomeBackground.RANDOM && it != HomeBackground.NEUTRAL }

    fun setLastRandomHomeBackground(c: Context, value: HomeBackground) = get(c).edit()
            .putString(KEY_LAST_RANDOM_HOME_BACKGROUND, value.name)
            .apply()

    private fun readBool(c: Context, key: String, def: Boolean) = get(c).getBoolean(key, def)
    private fun writeBool(c: Context, key: String, value: Boolean) = get(c).edit().putBoolean(key, value).apply()

    private fun parseHomeBackground(value: String?) =
            HomeBackground.entries.firstOrNull { it.name == value }

    private fun get(c: Context) = c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

}
