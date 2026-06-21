package com.majeur.psclient.battleanim

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * A transient animated sprite drawn on the battle field. A particle starts at
 * [spawnTimeMs] (an absolute `SystemClock.uptimeMillis()` value) showing [from],
 * then plays through [segments] in order; each segment tweens the box
 * (left/top/width/height) and opacity to a new [Segment.to] state with its own
 * per-property easing.
 *
 * The [bitmap] may arrive asynchronously (web fx fetch); the particle simply
 * doesn't draw until it is set.
 */
class AnimParticle(
    val spawnTimeMs: Long,
    private val from: State,
    private val segments: List<Segment>,
) {
    @Volatile
    var bitmap: Bitmap? = null

    /** Absolute clock time at which this particle has finished and can be discarded. */
    val endTimeMs: Long = spawnTimeMs + segments.sumOf { it.durationMs.toLong() }

    private val dst = RectF()

    fun isFinished(nowMs: Long) = nowMs >= endTimeMs

    /** Draw the particle for absolute clock time [nowMs]. No-op before spawn or with no bitmap. */
    fun draw(canvas: Canvas, nowMs: Long, paint: Paint) {
        val bmp = bitmap ?: return
        // The bitmap may belong to a sprite that was cleared/recycled mid-animation (e.g. a faint);
        // drawing a recycled bitmap would throw and crash the view draw pass, so skip it.
        if (bmp.isRecycled) return
        if (nowMs < spawnTimeMs) return

        var state = from
        var local = nowMs - spawnTimeMs
        for (seg in segments) {
            if (local <= seg.durationMs) {
                state = interpolate(state, seg, local)
                break
            }
            local -= seg.durationMs
            state = seg.to
        }

        if (state.opacity <= 0f) return
        dst.set(state.left, state.top, state.left + state.width, state.top + state.height)
        paint.alpha = (state.opacity.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private fun interpolate(from: State, seg: Segment, local: Long): State {
        val raw = if (seg.durationMs <= 0) 1f else (local.toFloat() / seg.durationMs).coerceIn(0f, 1f)
        val e = seg.easings
        return State(
            lerp(from.left, seg.to.left, e.left.apply(raw)),
            lerp(from.top, seg.to.top, e.top.apply(raw)),
            lerp(from.width, seg.to.width, e.width.apply(raw)),
            lerp(from.height, seg.to.height, e.height.apply(raw)),
            lerp(from.opacity, seg.to.opacity, e.opacity.apply(raw)),
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** A screen-space keyframe: a top-left anchored box plus opacity. */
    class State(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val opacity: Float,
    )

    /** A tween towards [to] over [durationMs], easing each property independently. */
    class Segment(
        val durationMs: Int,
        val to: State,
        val easings: PropEasings,
    )
}
