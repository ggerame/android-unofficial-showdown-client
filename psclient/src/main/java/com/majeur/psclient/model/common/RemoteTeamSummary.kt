package com.majeur.psclient.model.common

import org.json.JSONArray
import org.json.JSONObject

data class RemoteTeamSummary(
        val id: String,
        val name: String,
        val format: String,
        val species: List<String>,
        val isPrivate: Boolean
) {
    companion object {
        fun parseResponse(raw: String): List<RemoteTeamSummary> {
            val clean = raw.removePrefix("]").trim()
            val array = if (clean.startsWith("[")) JSONArray(clean)
                    else JSONObject(clean).optJSONArray("teams") ?: JSONArray()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("teamid").ifBlank { return@mapNotNull null }
                RemoteTeamSummary(
                        id,
                        item.optString("name", "Server team"),
                        item.optString("format", "other").ifBlank { "other" },
                        item.optString("team").split(',').filter { it.isNotBlank() },
                        item.has("private") && item.opt("private") != false &&
                                item.optString("private").isNotBlank())
            }
        }

        fun parsePackedTeam(raw: String): String? {
            val clean = raw.removePrefix("]").trim()
            if (clean.isBlank() || clean == "null") return null
            if (!clean.startsWith("{")) return clean.trim('"')
            val json = JSONObject(clean)
            return json.optString("team").ifBlank { json.optString("packedTeam") }.ifBlank { null }
        }

        fun parseUploadedId(raw: String): String? {
            val clean = raw.removePrefix("]").trim()
            return try {
                if (clean.startsWith("{")) JSONObject(clean).optString("teamid").ifBlank { null }
                else clean.toLongOrNull()?.toString()
            } catch (_: Exception) { null }
        }
    }
}
