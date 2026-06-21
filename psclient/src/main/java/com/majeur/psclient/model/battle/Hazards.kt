package com.majeur.psclient.model.battle

import com.majeur.psclient.util.toId

/**
 * Describes the graphical entry-hazard effects (spikes, toxic spikes, stealth rock,
 * sticky web, G-Max Steelsurge) the way the official web client does in
 * `battle-animations.ts` (`addSideCondition`).
 *
 * Sprites are loaded at runtime from `https://play.pokemonshowdown.com/fx/`.
 * Offsets are expressed in the web client reference pixels (x: right is positive,
 * y: up is positive) and are scaled to the battle field by [com.majeur.psclient.widget.BattleLayout].
 */
object Hazards {

    /**
     * A single sprite instance of a hazard.
     * @param file fx sprite file name (loaded from the Showdown `/fx/` folder)
     * @param refW reference width in web pixels (the size the web client declares for this sprite
     *             in `BattleEffects`, which may differ from the PNG's natural size)
     * @param refH reference height in web pixels (see [refW])
     * @param dx horizontal offset from the side anchor, in web reference pixels (right positive)
     * @param dy vertical offset from the side anchor, in web reference pixels (up positive)
     * @param scale multiplier applied to the reference size
     * @param alpha opacity in [0, 1]
     */
    class FxSprite(val file: String, val refW: Float, val refH: Float,
                   val dx: Float, val dy: Float, val scale: Float, val alpha: Float)

    fun isGraphical(rawSide: String?): Boolean = specOf(rawSide) != null

    /**
     * Returns the sprites to draw for the given side condition with [layers] active stacks,
     * or null if the side condition has no graphical representation.
     */
    fun spritesFor(rawSide: String?, layers: Int): List<FxSprite>? =
            specOf(rawSide)?.invoke(layers)

    private fun specOf(rawSide: String?): ((Int) -> List<FxSprite>)? = when (rawSide?.toId()) {
        "spikes" -> ::spikes
        "toxicspikes" -> ::toxicSpikes
        "stealthrock" -> ::stealthRock
        "stickyweb" -> ::stickyWeb
        "gmaxsteelsurge" -> ::steelSurge
        else -> null
    }

    private fun spikes(layers: Int): List<FxSprite> {
        val pos = arrayOf(
                floatArrayOf(-25f, -40f),
                floatArrayOf(30f, -45f),
                floatArrayOf(50f, -40f))
        val n = layers.coerceIn(1, 3)
        return (0 until n).map { FxSprite("caltrop.png", 80f, 80f, pos[it][0], pos[it][1], 0.3f, 1f) }
    }

    private fun toxicSpikes(layers: Int): List<FxSprite> {
        val pos = arrayOf(
                floatArrayOf(5f, -40f),
                floatArrayOf(-15f, -35f))
        val n = layers.coerceIn(1, 2)
        return (0 until n).map { FxSprite("poisoncaltrop.png", 80f, 80f, pos[it][0], pos[it][1], 0.3f, 1f) }
    }

    private fun stealthRock(layers: Int): List<FxSprite> = listOf(
            FxSprite("rock1.png", 64f, 80f, -40f, -10f, 0.2f, 0.5f),
            FxSprite("rock2.png", 66f, 72f, -20f, -40f, 0.2f, 0.5f),
            FxSprite("rock1.png", 64f, 80f, 30f, -20f, 0.2f, 0.5f),
            FxSprite("rock2.png", 66f, 72f, 10f, -30f, 0.2f, 0.5f))

    private fun stickyWeb(layers: Int): List<FxSprite> = listOf(
            FxSprite("web.png", 120f, 122f, 15f, -35f, 0.7f, 0.4f))

    private fun steelSurge(layers: Int): List<FxSprite> = listOf(
            FxSprite("greenmetal1.png", 45f, 45f, -30f, -20f, 0.8f, 0.5f),
            FxSprite("greenmetal2.png", 45f, 45f, 35f, -15f, 0.8f, 0.5f),
            FxSprite("greenmetal1.png", 45f, 45f, 50f, -10f, 0.8f, 0.5f))
}
