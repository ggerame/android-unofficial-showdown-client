package com.majeur.psclient.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.majeur.psclient.R
import com.majeur.psclient.model.pokemon.BasePokemon
import com.majeur.psclient.model.pokemon.BattlingPokemon
import com.majeur.psclient.util.addIfNotIn
import com.majeur.psclient.util.sp
import com.majeur.psclient.util.toId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("RtlHardcoded")
class PlayerInfoView @JvmOverloads constructor(context: Context?, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : AppCompatTextView(context, attrs, defStyleAttr), TipPopupContentProvider {

    private val dexIconSize = sp(24f)
    private val spannableBuilder: SpannableStringBuilder
    private val pokeballDrawable: Drawable
    private val emptyPokeballDrawable: Drawable

    private val pokemonIds = mutableListOf<String>()
    // Parallel to pokemonIds: the latest known data for each slot. A slot holds a BattlingPokemon
    // once that Pokémon has actually appeared on the field, which is what makes its icon tippable.
    private val pokemons = mutableListOf<BasePokemon?>()
    private var lastAnchorCenterX = 0

    private val isGravityRight
        get() = Gravity.getAbsoluteGravity(gravity, layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT

    init {
        spannableBuilder = SpannableStringBuilder(SUFFIX_PATTERN)
        pokeballDrawable = ContextCompat.getDrawable(context!!, R.drawable.ic_team_poke)!!
        pokeballDrawable.setBounds(0, 0, dexIconSize, dexIconSize)
        emptyPokeballDrawable = ContextCompat.getDrawable(context, R.drawable.ic_team_poke_empty)!!
        emptyPokeballDrawable.setBounds(0, 0, dexIconSize, dexIconSize)
    }

    fun clear() {
        pokemonIds.clear()
        pokemons.clear()
        spannableBuilder.clear()
        spannableBuilder.clearSpans()
        spannableBuilder.append(SUFFIX_PATTERN)
        text = null
    }

    fun setUsername(username: String) {
        val k = spannableBuilder.length - SUFFIX_PATTERN.length
        val start: Int
        if (isGravityRight) {
            if (k != 0) {
                start = SUFFIX_PATTERN.length - 1
                spannableBuilder.replace(start, spannableBuilder.length, username)
            } else {
                spannableBuilder.append(username)
                start = spannableBuilder.length - username.length
            }
        } else {
            start = if (k != 0) {
                spannableBuilder.replace(0, k, username)
                0
            } else {
                spannableBuilder.insert(0, username)
                0
            }
        }
        val potentialStyleSpans = spannableBuilder.getSpans(0, spannableBuilder.length, StyleSpan::class.java)
        if (potentialStyleSpans != null && potentialStyleSpans.isNotEmpty()) spannableBuilder.removeSpan(potentialStyleSpans[0])
        spannableBuilder.setSpan(StyleSpan(Typeface.BOLD), start, start + username.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        invalidateText()
    }

    fun setTeamSize(teamSize: Int) {
        pokemonIds.clear()
        pokemons.clear()
        val l = spannableBuilder.length
        for (span in spannableBuilder.getSpans(0, l, ImageSpan::class.java)) spannableBuilder.removeSpan(span)
        val k = spannableBuilder.length - MAX_TEAM_SIZE - SUFFIX_OFFSET
        if (isGravityRight) {
            for (i in SUFFIX_OFFSET until SUFFIX_OFFSET + teamSize) spannableBuilder.setSpan(ImageSpan(pokeballDrawable), i, i + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            for (i in SUFFIX_OFFSET + teamSize until SUFFIX_OFFSET + MAX_TEAM_SIZE) spannableBuilder.setSpan(ImageSpan(emptyPokeballDrawable), i, i + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        } else {
            for (i in k until k + teamSize) spannableBuilder.setSpan(ImageSpan(pokeballDrawable), i, i + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            for (i in k + teamSize until l - SUFFIX_OFFSET) spannableBuilder.setSpan(ImageSpan(emptyPokeballDrawable), i, i + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        invalidateText()
    }

    fun appendPokemon(pokemon: BasePokemon, dexIcon: Drawable) {
        // Team slots are normally set up by setTeamSize(). When joining/spectating a battle whose
        // preview is already underway, that message may be missing, leaving no placeholder spans.
        // Fall back to a full-size team so we still have spans to replace instead of crashing.
        if (spannableBuilder.getSpans(0, spannableBuilder.length, ImageSpan::class.java).isEmpty())
            setTeamSize(MAX_TEAM_SIZE)
        if (!pokemonIds.addIfNotIn(pokemon.baseSpecies.toId())) return
        pokemons.add(pokemon)
        val i = charIndexForPokemonIndex(pokemonIds.size - 1)
        val previousSpan = firstImageSpanAt(i) ?: return
        spannableBuilder.removeSpan(previousSpan)
        val aspectRatio = dexIcon.intrinsicWidth / dexIcon.intrinsicHeight.toFloat()
        dexIcon.setBounds(0, 0, (aspectRatio * dexIconSize).roundToInt(), dexIconSize)
        spannableBuilder.setSpan(ImageSpan(dexIcon), i, i + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        invalidateText()
    }

    fun updatePokemon(pokemon: BasePokemon, dexIcon: Drawable) {
        if (!pokemonIds.contains(pokemon.baseSpecies.toId())) {
            appendPokemon(pokemon, dexIcon)
            return
        }
        val index = pokemonIds.indexOf(pokemon.baseSpecies.toId())
        if (index in pokemons.indices) pokemons[index] = pokemon
        val i = charIndexForPokemonIndex(index)
        val previousSpan = firstImageSpanAt(i) ?: return
        spannableBuilder.removeSpan(previousSpan)
        val aspectRatio = dexIcon.intrinsicWidth / dexIcon.intrinsicHeight.toFloat()
        dexIcon.setBounds(0, 0, (aspectRatio * dexIconSize).roundToInt(), dexIconSize)
        spannableBuilder.setSpan(ImageSpan(dexIcon), i, i + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        invalidateText()
    }

    fun setPokemonFainted(pokemon: BasePokemon?) {
        if (pokemon == null || !pokemonIds.contains(pokemon.baseSpecies.toId())) return
        val index = pokemonIds.indexOf(pokemon.baseSpecies.toId())
        val i = charIndexForPokemonIndex(index)
        val previousSpan = firstImageSpanAt(i) ?: return
        val matrix = ColorMatrix()
        matrix.setSaturation(0f)
        previousSpan.drawable.colorFilter = ColorMatrixColorFilter(matrix)
        invalidateText()
    }

    // Maps the 0-based order in which Pokémon appeared to the character index of its icon span.
    private fun charIndexForPokemonIndex(index: Int) =
            if (isGravityRight) SUFFIX_OFFSET + MAX_TEAM_SIZE - (index + 1)
            else spannableBuilder.length - MAX_TEAM_SIZE - SUFFIX_OFFSET + index

    override fun getTipPopupData(touchX: Int, touchY: Int): Any? {
        val layout = layout ?: return null
        val localX = (touchX - totalPaddingLeft + scrollX).toFloat()
        for (index in pokemonIds.indices) {
            // Only Pokémon that have actually appeared on the field (and thus carry move/condition
            // info) are tippable; preview-only or not-yet-revealed slots are skipped.
            val pokemon = pokemons.getOrNull(index) as? BattlingPokemon ?: continue
            val charIndex = charIndexForPokemonIndex(index)
            if (charIndex < 0 || charIndex + 1 > spannableBuilder.length) continue
            val left = layout.getPrimaryHorizontal(charIndex)
            val right = layout.getPrimaryHorizontal(charIndex + 1)
            val lo = min(left, right)
            val hi = max(left, right)
            if (localX in lo..hi) {
                lastAnchorCenterX = ((lo + hi) / 2f + totalPaddingLeft - scrollX).roundToInt()
                return pokemon
            }
        }
        return null
    }

    override fun getTipPopupAnchorX() = lastAnchorCenterX

    private fun firstImageSpanAt(i: Int): ImageSpan? {
        if (i < 0 || i + 1 > spannableBuilder.length) return null
        return spannableBuilder.getSpans(i, i + 1, ImageSpan::class.java).firstOrNull()
    }

    private fun invalidateText() {
        text = spannableBuilder
    }

    companion object {
        private const val SUFFIX_PATTERN = "  ------  "
        private const val SUFFIX_OFFSET = 2
        private const val MAX_TEAM_SIZE = 6
    }

}