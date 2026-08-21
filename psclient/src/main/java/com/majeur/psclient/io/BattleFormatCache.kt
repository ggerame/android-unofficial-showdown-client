package com.majeur.psclient.io

import android.content.Context
import com.majeur.psclient.model.common.BattleFormat
import timber.log.Timber
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class BattleFormatCache(context: Context) {
    private val file = File(context.filesDir, "battle_formats.cache")

    fun store(formats: List<BattleFormat.Category>) = try {
        ObjectOutputStream(file.outputStream().buffered()).use { it.writeObject(formats) }
    } catch (e: Exception) {
        Timber.w(e, "Could not cache battle formats")
    }

    @Suppress("UNCHECKED_CAST")
    fun get(): List<BattleFormat.Category> = try {
        ObjectInputStream(file.inputStream().buffered()).use { it.readObject() as List<BattleFormat.Category> }
    } catch (_: Exception) {
        emptyList()
    }
}
