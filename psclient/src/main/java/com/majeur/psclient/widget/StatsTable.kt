package com.majeur.psclient.widget

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.majeur.psclient.R
import com.majeur.psclient.model.common.Nature
import com.majeur.psclient.model.common.Stats
import com.majeur.psclient.util.Utils
import com.majeur.psclient.util.dp

class StatsTable @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val statData = Array(4) { IntArray(STAT_NAMES.size) }
    private val rows = STAT_NAMES.mapIndexed { index, name -> StatRow(index, name) }
    private val warning = TextView(context).apply {
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.errorTextColor))
        visibility = View.GONE
        setPadding(dp(8f))
    }
    private var level = 100
    private var nature = Nature.DEFAULT
    private var rowClickListener: OnRowClickListener? = null

    init {
        orientation = VERTICAL
        TextView(context).apply {
            text = context.getString(R.string.stats)
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setPadding(dp(8f))
            addView(this)
        }
        rows.forEach { addView(it.root) }
        addView(warning)
        refreshRows()
    }

    fun setLevel(level: Int) {
        this.level = level
        recalculate()
    }

    fun setNature(nature: Nature) {
        this.nature = nature
        recalculate()
    }

    fun setBaseStats(baseStats: Stats) {
        statData[BASE] = baseStats.array
        recalculate()
    }

    fun setEVs(evs: Stats) {
        statData[EVS] = evs.array
        recalculate()
    }

    fun setIVs(ivs: Stats) {
        statData[IVS] = ivs.array
        recalculate()
    }

    fun clear() {
        statData.forEach { it.fill(0) }
        refreshRows()
    }

    fun setRowClickListener(listener: OnRowClickListener?) {
        rowClickListener = listener
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        rows.forEach { it.root.isEnabled = enabled }
        alpha = if (enabled) 1f else 0.5f
    }

    private fun recalculate() {
        STAT_NAMES.indices.forEach { index ->
            statData[TOTAL][index] = when {
                statData[BASE][index] == 0 -> 0
                index == 0 -> Stats.calculateHp(
                        statData[BASE][index], statData[IVS][index], statData[EVS][index], level)
                else -> Stats.calculateStat(
                        statData[BASE][index], statData[IVS][index], statData[EVS][index], level,
                        nature.getStatModifier(index))
            }
        }
        refreshRows()
    }

    private fun refreshRows() {
        rows.forEach { it.bind() }
        val excess = statData[EVS].sum() - MAX_EV_SUM
        warning.visibility = if (excess > 0) View.VISIBLE else View.GONE
        warning.text = resources.getString(R.string.too_many_evs, excess.coerceAtLeast(0))
    }

    private inner class StatRow(private val index: Int, private val statName: String) {
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            minimumHeight = dp(56f)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            val selectable = context.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.selectableItemBackground))
            background = selectable.getDrawable(0)
            selectable.recycle()
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            setOnClickListener { rowClickListener?.onRowClicked(this@StatsTable, statName, index) }
        }
        private val name = TextView(context).apply {
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        }
        private val total = TextView(context).apply {
            gravity = Gravity.END
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        }
        private val details = TextView(context).apply {
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        private val indicator = LinearProgressIndicator(context).apply {
            max = 504
            trackThickness = dp(4f)
            trackCornerRadius = dp(2f)
        }

        init {
            root.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(total, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            })
            root.addView(details, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            root.addView(indicator, LayoutParams(LayoutParams.MATCH_PARENT, dp(4f)))
        }

        fun bind() {
            val totalValue = statData[TOTAL][index]
            name.text = statName
            total.text = totalValue.toString()
            details.text = resources.getString(R.string.stat_details,
                    statData[BASE][index], statData[EVS][index], statData[IVS][index])
            indicator.max = if (index == 0) 704 else 504
            indicator.setProgressCompat(totalValue.coerceAtMost(indicator.max), false)
            indicator.setIndicatorColor(statColor(totalValue))
            root.contentDescription = resources.getString(R.string.stat_accessibility, statName,
                    statData[BASE][index], statData[EVS][index], statData[IVS][index], totalValue)
        }
    }

    private fun statColor(value: Int): Int {
        val hue = (value * 180f / 714f).coerceAtMost(360f)
        val rgb = Utils.hslToRgb(hue, 40f, 75f)
        return Color.rgb((rgb[0] * 255).toInt(), (rgb[1] * 255).toInt(), (rgb[2] * 255).toInt())
    }

    fun interface OnRowClickListener {
        fun onRowClicked(statsTable: StatsTable?, rowName: String?, rowIndex: Int)
    }

    companion object {
        private val STAT_NAMES = arrayOf("HP", "Attack", "Defense", "Sp. Atk.", "Sp. Def.", "Speed")
        private const val BASE = 0
        private const val EVS = 1
        private const val IVS = 2
        private const val TOTAL = 3
        private const val MAX_EV_SUM = 510
    }
}
