package com.majeur.psclient.model.common

import com.majeur.psclient.util.toId
import java.io.Serializable

fun BattleFormat.toId(): String = id

/** The format metadata sent by Pokémon Showdown's `|formats|` message. */
class BattleFormat(
        val label: String,
        private val formatInt: Int,
        val id: String = label.toId(),
        val section: String = "",
        val column: Int = 1
) : Serializable {

    val isTeamNeeded get() = formatInt and MASK_PRESET_TEAM == 0
    val isSearchShow get() = formatInt and MASK_SEARCH_SHOW != 0
    val isChallengeShow get() = formatInt and MASK_CHALLENGE_SHOW != 0
    val isTournamentShow get() = formatInt and MASK_TOURNAMENT_SHOW != 0
    val defaultLevel get() = if (formatInt and MASK_LEVEL_50 != 0) 50 else profile.defaultLevel
    val profile get() = FormatProfile.from(id)

    override fun equals(other: Any?) = (other as? BattleFormat)?.id == id

    override fun hashCode() = id.hashCode()

    override fun toString() = label

    class Category(var label: String = "", var column: Int = 1) : Serializable {

        val formats = mutableListOf<BattleFormat>()

        val searchableBattleFormats: List<BattleFormat>
            get() = formats.filter { it.isSearchShow }
    }

    companion object {

        private const val MASK_PRESET_TEAM = 0x1
        private const val MASK_SEARCH_SHOW = 0x2
        private const val MASK_CHALLENGE_SHOW = 0x4
        private const val MASK_TOURNAMENT_SHOW = 0x8
        private const val MASK_LEVEL_50 = 0x10

        @JvmStatic val FORMAT_OTHER = BattleFormat("[Other]", -1, "other")
        val FORMAT_ALL = BattleFormat("All formats", -1, "all")

        fun compare(formats: List<Category>?, f1: String, f2: String): Int {
            if (f1 == f2) return 0
            if (f1.contains("other")) return 1
            if (f2.contains("other")) return -1
            if (formats == null) return f1.compareTo(f2)
            val ordered = formats.flatMap { it.formats }.map { it.id }
            val first = ordered.indexOf(f1).let { if (it < 0) Int.MAX_VALUE else it }
            val second = ordered.indexOf(f2).let { if (it < 0) Int.MAX_VALUE else it }
            return first.compareTo(second)
        }

        @JvmStatic fun resolveName(formats: List<Category>?, formatId: String): String {
            if ("other" == formatId) return "Other"
            return formats?.asSequence()?.flatMap { it.formats.asSequence() }
                    ?.firstOrNull { it.id == formatId }?.label ?: formatId
        }
    }
}

/** Small UI profile; the server validator remains authoritative for legality. */
data class FormatProfile(
        val generation: Int,
        val defaultLevel: Int,
        val hasItems: Boolean,
        val hasAbilities: Boolean,
        val hasNatures: Boolean,
        val hasShiny: Boolean,
        val hasHappiness: Boolean,
        val hasHiddenPower: Boolean,
        val hasDynamax: Boolean,
        val hasTera: Boolean
) : Serializable {
    companion object {
        fun from(id: String): FormatProfile {
            // Matches Pokémon Showdown's Dex.formatGen(): format suffixes may start with digits
            // (for example gen912switch is Gen 9's "1-2 Switch", not generation 912).
            val generation = when {
                id.isBlank() -> CURRENT_GENERATION
                !id.startsWith("gen") -> 6
                else -> id.getOrNull(3)?.digitToIntOrNull() ?: CURRENT_GENERATION
            }
            val isLetsGo = "letsgo" in id
            val isNatDex = "nationaldex" in id || "natdex" in id
            val isBDSP = "bdsp" in id
            val isChampions = "champions" in id
            val defaultLevel = when {
                "lc" in id -> 5
                listOf("vgc", "bss", "ultrasinnohclassic", "battlespot", "battlestadium",
                        "battlefestival", "letsgo", "champions").any { it in id } -> 50
                else -> 100
            }
            return FormatProfile(generation, defaultLevel,
                    hasItems = generation > 1 && !isLetsGo,
                    hasAbilities = generation > 2 && !isLetsGo,
                    hasNatures = generation >= 3,
                    hasShiny = generation > 1,
                    hasHappiness = isLetsGo || generation < 8 || isNatDex,
                    hasHiddenPower = generation in 2..7 || isNatDex,
                    hasDynamax = generation == 8 && !isBDSP,
                    hasTera = generation == 9 && !isChampions)
        }

        private const val CURRENT_GENERATION = 9
    }
}

/** Parser kept separate from the observer so protocol fixtures can be unit-tested. */
object BattleFormatParser {
    fun parse(tokens: List<String>): List<BattleFormat.Category> {
        val categories = mutableListOf<BattleFormat.Category>()
        var category: BattleFormat.Category? = null
        var pendingColumn = 1
        for (token in tokens) {
            if (token.startsWith(",")) {
                pendingColumn = token.drop(1).substringBefore(',').toIntOrNull() ?: 1
                category = null
                continue
            }
            if (category == null) {
                category = BattleFormat.Category(token, pendingColumn).also(categories::add)
                continue
            }
            val name = token.substringBefore(',').trim()
            if (name.isBlank()) continue
            val flags = token.substringAfter(',', "").substringBefore(',').trim().toIntOrNull(16) ?: 0
            category.formats += BattleFormat(name, flags, name.toId(), category.label, category.column)
        }
        return categories.filter { it.formats.isNotEmpty() }
    }
}
