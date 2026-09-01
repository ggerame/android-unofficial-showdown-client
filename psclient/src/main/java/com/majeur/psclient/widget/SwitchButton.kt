package com.majeur.psclient.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.majeur.psclient.R
import com.majeur.psclient.model.battle.Condition
import com.majeur.psclient.model.common.Colors
import com.majeur.psclient.util.dp
import com.google.android.material.card.MaterialCardView
import java.util.Locale
import kotlin.math.roundToInt

class SwitchButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : MaterialCardView(context, attrs, defStyleAttr) {

    private lateinit var nameView: TextView
    private lateinit var iconView: ImageView
    private lateinit var healthView: ProgressBar
    private lateinit var conditionView: TextView
    private lateinit var badgeView: TextView

    private var pokemonName: String? = null
    private var condition: Condition? = null
    private var stateDescription: String? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        nameView = findViewById(R.id.name_view)
        iconView = findViewById(R.id.dex_icon_view)
        healthView = findViewById(R.id.health_view)
        conditionView = findViewById(R.id.condition_view)
        badgeView = findViewById(R.id.choice_badge)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!::nameView.isInitialized) return
        nameView.isEnabled = enabled
        alpha = if (enabled) 1f else 0.62f
        updateContentDescription()
    }

    fun setDexIcon(dexIcon: Drawable?) {
        iconView.setImageDrawable(dexIcon)
    }

    fun setIconVisible(visible: Boolean) {
        iconView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setPokemonName(name: String?) {
        pokemonName = name
        nameView.text = name
        updateContentDescription()
    }

    fun setCondition(condition: Condition?) {
        this.condition = condition
        if (condition == null) {
            healthView.visibility = View.INVISIBLE
            conditionView.text = context.getString(R.string.battle_hp_unknown)
        } else {
            val percent = (condition.health * 100).roundToInt().coerceIn(0, 100)
            healthView.apply {
                visibility = View.VISIBLE
                progress = percent
                progressTintList = ColorStateList.valueOf(Colors.healthColor(condition.health))
            }
            conditionView.text = condition.status?.let {
                context.getString(R.string.battle_hp_status, percent, it.uppercase(Locale.ROOT))
            } ?: context.getString(R.string.battle_hp_percent, percent)
        }
        updateContentDescription()
    }

    fun setChoiceState(label: CharSequence?, description: String? = label?.toString(), selected: Boolean = false) {
        stateDescription = description
        badgeView.apply {
            text = label
            visibility = if (label.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        strokeWidth = context.dp(if (selected) 2f else 1f)
        strokeColor = ContextCompat.getColor(context, if (selected) R.color.primary else R.color.outline)
        updateContentDescription()
    }

    private fun updateContentDescription() {
        if (!::nameView.isInitialized) return
        val name = pokemonName ?: run {
            contentDescription = null
            return
        }
        val details = mutableListOf(conditionView.text.toString())
        stateDescription?.let(details::add)
        val unavailable = context.getString(R.string.battle_choice_unavailable)
        if (!isEnabled && stateDescription != unavailable) details.add(unavailable)
        contentDescription = context.getString(
                R.string.battle_pokemon_choice_description, name, details.joinToString(", "))
    }
}
