package com.majeur.psclient.battleanim

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import com.majeur.psclient.model.battle.AnimCall
import com.majeur.psclient.model.battle.AnimCoord
import com.majeur.psclient.model.battle.AnimPos
import com.majeur.psclient.model.battle.AnimSequence
import com.majeur.psclient.model.battle.BackgroundEffect
import com.majeur.psclient.model.battle.OtherAnim
import com.majeur.psclient.model.battle.ShowEffect
import com.majeur.psclient.model.battle.SpriteAnim
import com.majeur.psclient.model.battle.SpriteDelay
import com.majeur.psclient.model.battle.Wait

/**
 * The bridge between the (pure) animation engine and the live battle UI. The
 * fragment implements this to feed participant sprites/positions and to host the
 * spawned particles. "attacker" is the move's user, "defender" its target.
 */
interface BattleAnimScene {
    val fieldWidth: Float
    val fieldHeight: Float

    /** The participant's current Pokémon sprite, for `{attacker}`/`{defender}` effects and spriteAnim. */
    fun participantBitmap(who: String): Bitmap?

    /** The participant's real on-screen sprite rect (layout px), used to anchor sprite tweens. */
    fun participantRect(who: String): RectF?

    /** Asynchronously load an fx particle bitmap (an fx/ basename or a full `https://` URL). */
    fun loadFx(effect: String, callback: (Bitmap) -> Unit)

    /** Register a particle for drawing on the field. */
    fun addParticle(particle: AnimParticle)

    /** Show/hide a participant's real sprite so a moving copy can stand in during a tween. */
    fun setParticipantHidden(who: String, hidden: Boolean)

    /** Flash the battle background. [color] may be a CSS color, a `url(...)`/gradient, or null. */
    fun flashBackground(color: String?, opacity: Float, timeMs: Int)

    /** Schedule [action] after [delayMs] on the UI thread. */
    fun post(delayMs: Long, action: () -> Unit)

    /** Resolve a shared "other" table animation by name (e.g. "contactattack"). */
    suspend fun resolveOther(name: String): AnimSequence?
}

/**
 * Walks an [AnimSequence] and emits [AnimParticle]s onto a [BattleAnimScene],
 * porting the timeline semantics of Pokémon Showdown's `BattleScene`:
 *  - showEffect spawns an fx particle tweened start -> end (with optional fade)
 *  - spriteAnim tweens a participant's own sprite (a copy stands in while it moves)
 *  - spriteDelay / wait advance the timeline cursor
 *  - backgroundEffect flashes the field
 *  - otherAnim delegates inline to a shared "other" table entry
 *
 * NOTE: positions are calibrated (see [BattleAnimProjection]); particle *sizes*
 * (fx default 96px, BattleEffects table not yet extracted) still want on-device
 * tuning. Sprite tweens are anchored to the real sprite rect so they stay aligned.
 */
class BattleAnimController(private val scene: BattleAnimScene) {

    private var clock0 = 0L

    suspend fun play(sequence: AnimSequence) {
        clock0 = SystemClock.uptimeMillis()
        val afterPrepare = renderCalls(sequence.prepareAnim, 0L)
        renderCalls(sequence.anim, afterPrepare)
        // residualAnim (ongoing field effects) is intentionally not played here.
    }

    private suspend fun renderCalls(calls: List<AnimCall>, baseOffset: Long): Long {
        var timeOffset = baseOffset
        val spriteCursor = HashMap<String, Long>()
        val spriteScene = HashMap<String, ScenePos>()
        val hideStart = HashMap<String, Long>()
        val hideEnd = HashMap<String, Long>()

        for (call in calls) {
            when (call) {
                is Wait -> timeOffset += call.time
                is SpriteDelay -> {
                    val cur = spriteCursor[call.sprite] ?: timeOffset
                    spriteCursor[call.sprite] = cur + call.time
                }
                is BackgroundEffect -> {
                    val color = call.color
                    val opacity = call.opacity ?: 1f
                    val time = call.time
                    schedule(timeOffset + call.delay) { scene.flashBackground(color, opacity, time) }
                }
                is OtherAnim -> scene.resolveOther(call.name)?.let {
                    timeOffset = renderCalls(it.anim, timeOffset)
                }
                is ShowEffect -> spawnEffect(call, timeOffset)
                is SpriteAnim -> {
                    val who = call.sprite
                    val start = spriteCursor[who] ?: timeOffset
                    val dur = call.pos.time ?: DEFAULT_TWEEN_MS
                    val fromScene = spriteScene[who] ?: restingScene(who)
                    val toScene = resolveScenePos(call.pos)
                    spawnSpriteAnim(who, fromScene, toScene, call.transition, dur, start)
                    spriteScene[who] = toScene
                    spriteCursor[who] = start + dur
                    if (who !in hideStart) hideStart[who] = start
                    hideEnd[who] = maxOf(hideEnd[who] ?: 0L, start + dur)
                }
            }
        }

        for ((who, start) in hideStart) {
            val end = hideEnd[who] ?: continue
            schedule(start) { scene.setParticipantHidden(who, true) }
            schedule(end) { scene.setParticipantHidden(who, false) }
        }
        return timeOffset
    }

    private fun spawnEffect(call: ShowEffect, timeOffset: Long) {
        val hasStart = call.start != null
        val hasEnd = call.end != null
        val startScene = resolveScenePos(call.start ?: call.end)
        val endScene = resolveScenePos(call.end ?: call.start)
        val fromBox = BattleAnimProjection.project(startScene, FX_SIZE, FX_SIZE)
        val toBox = BattleAnimProjection.project(endScene, FX_SIZE, FX_SIZE)
        val fromState = fxState(fromBox)
        val toState = fxState(toBox)
        val dur = call.end?.time ?: call.start?.time ?: DEFAULT_TWEEN_MS

        val segments = ArrayList<AnimParticle.Segment>()
        if (hasStart && hasEnd) {
            val easings = PropEasings.resolve(call.transition, toBox.top, fromBox.top, endScene.z)
            segments.add(AnimParticle.Segment(dur, toState, easings))
        } else {
            segments.add(AnimParticle.Segment(dur, toState, PropEasings()))
        }
        if (call.after == "fade") {
            segments.add(
                AnimParticle.Segment(
                    FADE_MS,
                    AnimParticle.State(toState.left, toState.top, toState.width, toState.height, 0f),
                    PropEasings(),
                )
            )
        }

        val particle = AnimParticle(clock0 + timeOffset, fromState, segments)
        scene.addParticle(particle)
        loadEffectBitmap(call.effect) { particle.bitmap = it }
    }

    private fun spawnSpriteAnim(
        who: String, fromScene: ScenePos, toScene: ScenePos,
        transition: String?, dur: Int, start: Long,
    ) {
        val fromState = participantState(who, fromScene)
        val toState = participantState(who, toScene)
        val fromBox = BattleAnimProjection.project(fromScene, FX_SIZE, FX_SIZE)
        val toBox = BattleAnimProjection.project(toScene, FX_SIZE, FX_SIZE)
        val easings = PropEasings.resolve(transition, toBox.top, fromBox.top, toScene.z)
        val particle = AnimParticle(
            clock0 + start, fromState,
            listOf(AnimParticle.Segment(dur, toState, easings)),
        )
        scene.participantBitmap(who)?.let { particle.bitmap = it }
        scene.addParticle(particle)
    }

    /**
     * Maps a participant scene pos to a screen state anchored on the real sprite
     * view: at rest the copy coincides with the real sprite, and keyframes shift
     * and scale it by the projected deltas. The 96px projection constant cancels
     * out (only ratios/centers are used), so this needs no size calibration.
     */
    private fun participantState(who: String, scenePos: ScenePos): AnimParticle.State {
        val realRect = scene.participantRect(who)
            ?: return fxState(BattleAnimProjection.project(scenePos, FX_SIZE, FX_SIZE))
        val restBox = BattleAnimProjection.project(restingScene(who), FX_SIZE, FX_SIZE)
        val box = BattleAnimProjection.project(scenePos, FX_SIZE, FX_SIZE)
        val restScreen = BattleAnimProjection.toScreen(restBox, scene.fieldWidth, scene.fieldHeight)
        val boxScreen = BattleAnimProjection.toScreen(box, scene.fieldWidth, scene.fieldHeight)
        val dx = boxScreen.centerX() - restScreen.centerX()
        val dy = boxScreen.centerY() - restScreen.centerY()
        val ratio = if (restBox.width != 0f) box.width / restBox.width else 1f
        val w = realRect.width() * ratio
        val h = realRect.height() * ratio
        val cx = realRect.centerX() + dx
        val cy = realRect.centerY() + dy
        return AnimParticle.State(cx - w / 2f, cy - h / 2f, w, h, box.opacity)
    }

    private fun fxState(box: SceneBox): AnimParticle.State {
        val r = BattleAnimProjection.toScreen(box, scene.fieldWidth, scene.fieldHeight)
        return AnimParticle.State(r.left, r.top, r.width(), r.height(), box.opacity)
    }

    private fun loadEffectBitmap(effect: String, cb: (Bitmap) -> Unit) {
        when (effect) {
            "{attacker}" -> scene.participantBitmap("attacker")?.let(cb)
            "{defender}" -> scene.participantBitmap("defender")?.let(cb)
            else -> scene.loadFx(effect, cb)
        }
    }

    private fun resolveScenePos(pos: AnimPos?): ScenePos {
        if (pos == null) return ScenePos()
        val scale = pos.scale ?: 1f
        return ScenePos(
            x = pos.x?.let { resolveCoord(it) } ?: 0f,
            y = pos.y?.let { resolveCoord(it) } ?: 0f,
            z = pos.z?.let { resolveCoord(it) } ?: 0f,
            scale = scale,
            xscale = pos.xscale ?: scale,
            yscale = pos.yscale ?: scale,
            opacity = pos.opacity ?: 1f,
        )
    }

    private fun resolveCoord(c: AnimCoord): Float =
        if (c.isRelative) baseFor(c.rel, c.axis) + c.value else c.value

    private fun baseFor(who: String?, axis: String?): Float =
        if (axis == "z" && who == "defender") 200f else 0f

    private fun restingScene(who: String): ScenePos =
        if (who == "defender") ScenePos(z = 200f) else ScenePos()

    /** Converts an anim-relative [offset] (ms from play start) to a UI-thread post. */
    private fun schedule(offset: Long, action: () -> Unit) {
        val delay = (clock0 + offset) - SystemClock.uptimeMillis()
        scene.post(delay.coerceAtLeast(0L), action)
    }

    companion object {
        private const val FX_SIZE = 96f
        private const val DEFAULT_TWEEN_MS = 300
        private const val FADE_MS = 300
    }
}
