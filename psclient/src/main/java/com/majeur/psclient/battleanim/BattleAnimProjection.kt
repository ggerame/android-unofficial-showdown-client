package com.majeur.psclient.battleanim

import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.floor

/**
 * Faithful port of the Pokémon Showdown `BattleScene` coordinate projection and
 * the custom jQuery easing curves used by move animations.
 *
 * Derived from play.pokemonshowdown.com/src/battle-animations.ts (MIT licensed;
 * see the repo-root NOTICE file). The animation *data* it plays back lives in
 * res/raw/battle_animations.json (see [com.majeur.psclient.model.battle.AnimSequence]).
 */

/** A resolved, absolute scene-space keyframe (PS "ScenePos"). */
class ScenePos(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val scale: Float = 1f,
    val xscale: Float = 1f,
    val yscale: Float = 1f,
    val opacity: Float = 1f,
)

/** A box in PS scene-pixel space (the output of `BattleScene.pos()`). */
class SceneBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val opacity: Float,
) {
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
}

object BattleAnimProjection {

    // PS resting sprite *centers* in scene space, obtained by running pos() on the
    // single-battle resting coordinates (near sprite z=0, far sprite z=200, from
    // `side.z = side.isFar ? 200 : 0`):
    //   near (player) center -> (210, 245);   far (foe) center -> (430, 135)
    // This app renders those active sprites at the REL_BATTLE single-battle anchors
    // (0.225, 0.748) and (0.775, 0.397). Solving fraction = a*scene + b per axis
    // gives the affine map below. (Calibrated against BattleLayout; revisit if the
    // REL_BATTLE anchors change.)
    private const val AX = 0.0025f      // (0.775 - 0.225) / (430 - 210)
    private const val BX = -0.30f       // 0.225 - AX * 210
    private const val AY = 0.0031909f   // (0.748 - 0.397) / (245 - 135)
    private const val BY = -0.033773f   // 0.748 - AY * 245

    /**
     * Port of `BattleScene.pos(loc, obj)`: projects scene coords (x, y, z, scale…)
     * to a [SceneBox]. [w]/[h] are the sprite's intrinsic pixel size, [yOffset] is
     * the sprite's vertical anchor (SpriteData.y, usually 0), and [gen] selects the
     * z→scale curve (gen-5 pixel sprites scale differently).
     */
    fun project(loc: ScenePos, w: Float, h: Float, yOffset: Float = 0f, gen: Int = 6): SceneBox {
        var left = 210f
        var top = 245f
        var scale = if (gen == 5) 2.0f - loc.z / 200f else 1.5f - 0.5f * (loc.z / 200f)
        if (scale < 0.1f) scale = 0.1f

        left += (410f - 190f) * (loc.z / 200f)
        top += (135f - 245f) * (loc.z / 200f)
        left += floor(loc.x * scale)
        top -= floor(loc.y * scale)
        val width = floor(w * scale * loc.xscale)
        val height = floor(h * scale * loc.yscale)
        val hoffset = floor((h - yOffset * 2f) * scale * loc.yscale)
        left -= floor(width / 2f)
        top -= floor(hoffset / 2f)
        return SceneBox(left, top, width, height, loc.opacity)
    }

    /** Maps a PS scene [box] to a screen rect within a field of [fieldW] x [fieldH] px. */
    fun toScreen(box: SceneBox, fieldW: Float, fieldH: Float): RectF {
        val cx = (AX * box.centerX + BX) * fieldW
        val cy = (AY * box.centerY + BY) * fieldH
        val halfW = AX * box.width * fieldW / 2f
        val halfH = AY * box.height * fieldH / 2f
        return RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }
}

/**
 * The PS custom easing curves (`Object.assign($.easing, …)` in battle-animations.ts)
 * plus jQuery's built-in linear/swing. Each maps elapsed fraction `x` in [0,1] to an
 * eased fraction; a tweened value is `start + (end - start) * apply(x)`.
 */
enum class AnimEasing {
    LINEAR, SWING, BALLISTIC_UP, BALLISTIC_DOWN, QUAD_UP, QUAD_DOWN;

    fun apply(x: Float): Float = when (this) {
        LINEAR -> x
        SWING -> (0.5 - cos(x * Math.PI) / 2.0).toFloat()
        BALLISTIC_UP -> -3f * x * x + 4f * x
        BALLISTIC_DOWN -> (1f - x).let { 1f - (-3f * it * it + 4f * it) }
        QUAD_UP -> (1f - x).let { 1f - it * it }
        QUAD_DOWN -> x * x
    }
}

/** Per-property easings, mirroring the transition map built by `BattleScene.posT()`. */
class PropEasings(
    val left: AnimEasing = AnimEasing.LINEAR,
    val top: AnimEasing = AnimEasing.LINEAR,
    val width: AnimEasing = AnimEasing.LINEAR,
    val height: AnimEasing = AnimEasing.LINEAR,
    val opacity: AnimEasing = AnimEasing.LINEAR,
) {
    companion object {
        /**
         * Port of `BattleScene.posT()`'s transition handling. [newTop]/[oldTop] are
         * the destination and source scene `top` values (ballistic curves pick their
         * direction from the sign of the vertical move); [destZ] is the destination z
         * (used by ballistic2Back). Unknown names fall back to all-linear, as in PS.
         */
        fun resolve(name: String?, newTop: Float, oldTop: Float, destZ: Float): PropEasings {
            val goingUp = newTop < oldTop
            return when (name?.lowercase()) {
                "ballistic" ->
                    PropEasings(top = if (goingUp) AnimEasing.BALLISTIC_UP else AnimEasing.BALLISTIC_DOWN)
                "ballisticunder" ->
                    PropEasings(top = if (goingUp) AnimEasing.BALLISTIC_DOWN else AnimEasing.BALLISTIC_UP)
                "ballistic2" ->
                    PropEasings(top = if (goingUp) AnimEasing.QUAD_UP else AnimEasing.QUAD_DOWN)
                "ballistic2back" ->
                    PropEasings(top = if (destZ > 0f) AnimEasing.QUAD_UP else AnimEasing.QUAD_DOWN)
                "ballistic2under" ->
                    PropEasings(top = if (goingUp) AnimEasing.QUAD_DOWN else AnimEasing.QUAD_UP)
                "swing" -> PropEasings(
                    AnimEasing.SWING, AnimEasing.SWING, AnimEasing.SWING, AnimEasing.SWING, AnimEasing.LINEAR
                )
                "accel" -> PropEasings(
                    AnimEasing.QUAD_DOWN, AnimEasing.QUAD_DOWN, AnimEasing.QUAD_DOWN, AnimEasing.QUAD_DOWN, AnimEasing.LINEAR
                )
                "decel" -> PropEasings(
                    AnimEasing.QUAD_UP, AnimEasing.QUAD_UP, AnimEasing.QUAD_UP, AnimEasing.QUAD_UP, AnimEasing.LINEAR
                )
                else -> PropEasings()
            }
        }
    }
}
