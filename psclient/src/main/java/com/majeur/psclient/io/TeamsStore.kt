package com.majeur.psclient.io

import android.content.Context
import com.majeur.psclient.model.common.Team
import com.majeur.psclient.util.toId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException

class TeamsStore(context: Context) {

    private val jsonFile = File(context.filesDir, FILE_NAME)

    suspend fun get(): List<Team.Group> = withContext(Dispatchers.IO) {
        try {
            getInternal()
        } catch (e: Exception) {
            if (e is JSONException) Timber.e(e)
            emptyList<Team.Group>()
        }
    }

    @Throws(IOException::class, JSONException::class)
    private fun getInternal(): List<Team.Group> {
        val raw = jsonFile.readText()
        val legacy = raw.trimStart().startsWith("[")
        val groupsJson = if (legacy) JSONArray(raw) else JSONObject(raw).getJSONArray(JSON_KEY_GROUPS)
        val groups = groupsJson.run {
            val groups = mutableListOf<Team.Group>()
            (0 until length()).map { getJSONObject(it) }.forEach { groupJson ->
                val format = groupJson.getString(JSON_KEY_FORMAT)
                val group = Team.Group(format)
                val teamsJson = groupJson.getJSONArray(JSON_KEY_TEAMS)
                (0 until teamsJson.length()).map { teamsJson.getJSONObject(it) }.forEach {
                    val label = it.optString(JSON_KEY_TEAM_LABEL)
                    val data = it.getString(JSON_KEY_TEAM_DATA)
                    val id = it.optString(JSON_KEY_TEAM_ID).ifBlank { java.util.UUID.randomUUID().toString() }
                    Team.unpack(label, format, data, id, legacyMiscOrder = legacy)?.let { team ->
                        team.remoteTeamId = it.optString(JSON_KEY_REMOTE_ID).ifBlank { null }
                        team.remoteOwnerId = it.optString(JSON_KEY_REMOTE_OWNER).ifBlank { null }
                        team.remotePrivate = it.optBoolean(JSON_KEY_REMOTE_PRIVATE, true)
                        team.remoteState = try {
                            Team.RemoteState.valueOf(it.optString(JSON_KEY_REMOTE_STATE, Team.RemoteState.LOCAL_ONLY.name))
                        } catch (_: IllegalArgumentException) { Team.RemoteState.LOCAL_ONLY }
                        team.isRemoteStub = it.optBoolean(JSON_KEY_REMOTE_STUB, false)
                        team.isDraft = it.optBoolean(JSON_KEY_DRAFT, false)
                        group.teams.add(team)
                    }
                }
                groups.add(group)
            }
            groups
        }
        if (legacy) {
            val backup = File(jsonFile.parentFile, "$FILE_NAME.v1.bak")
            if (!backup.exists()) jsonFile.copyTo(backup)
            writeJsonToFile(makeJson(groups))
        }
        return groups
    }

    suspend fun store(groups: List<Team.Group>): Boolean = withContext(Dispatchers.IO) {
        try {
            makeJson(groups).run { writeJsonToFile(this) }
            true
        } catch (e: Exception) {
            if (e is JSONException || e is IOException) Timber.e(e)
            false
        }
    }

    @Throws(JSONException::class)
    private fun makeJson(groups: List<Team.Group>): JSONObject {
        val jsonArray = JSONArray()
        val formats = groups.map { it.format.toId() }.toSet()
        formats.forEach { formatId ->
            val teams = groups.filter { it.format.toId() == formatId }.flatMap { it.teams }
            if (teams.isEmpty()) return@forEach
            JSONObject().apply {
                put(JSON_KEY_FORMAT, formatId)
                put(JSON_KEY_TEAMS, JSONArray().apply {
                    teams.forEach { team ->
                        put(JSONObject().apply {
                            put(JSON_KEY_TEAM_ID, team.uniqueId)
                            put(JSON_KEY_TEAM_LABEL, team.label)
                            put(JSON_KEY_TEAM_DATA, team.pack())
                            put(JSON_KEY_REMOTE_ID, team.remoteTeamId)
                            put(JSON_KEY_REMOTE_OWNER, team.remoteOwnerId)
                            put(JSON_KEY_REMOTE_PRIVATE, team.remotePrivate)
                            put(JSON_KEY_REMOTE_STATE, team.remoteState.name)
                            put(JSON_KEY_REMOTE_STUB, team.isRemoteStub)
                            put(JSON_KEY_DRAFT, team.isDraft)
                        })
                    }
                })
            }.also { jsonArray.put(it) }
        }
        return JSONObject().put(JSON_KEY_VERSION, STORE_VERSION).put(JSON_KEY_GROUPS, jsonArray)
    }

    private fun writeJsonToFile(json: JSONObject) {
        val temporary = File(jsonFile.parentFile, "$FILE_NAME.tmp")
        temporary.writeText(json.toString())
        if (!temporary.renameTo(jsonFile)) {
            temporary.copyTo(jsonFile, overwrite = true)
            temporary.delete()
        }
    }

    companion object {
        private const val FILE_NAME = "user_teams.json"
        private const val STORE_VERSION = 2
        private const val JSON_KEY_VERSION = "version"
        private const val JSON_KEY_GROUPS = "groups"
        private const val JSON_KEY_FORMAT = "label"
        private const val JSON_KEY_TEAMS = "teams"
        private const val JSON_KEY_TEAM_LABEL = "label"
        private const val JSON_KEY_TEAM_ID = "id"
        private const val JSON_KEY_TEAM_DATA = "data"
        private const val JSON_KEY_REMOTE_ID = "remoteId"
        private const val JSON_KEY_REMOTE_OWNER = "remoteOwner"
        private const val JSON_KEY_REMOTE_PRIVATE = "remotePrivate"
        private const val JSON_KEY_REMOTE_STATE = "remoteState"
        private const val JSON_KEY_REMOTE_STUB = "remoteStub"
        private const val JSON_KEY_DRAFT = "draft"
    }

}
