package com.smartalarm.mobile.data

import android.content.Context
import android.util.Log
import com.smartalarm.core.model.EpochFeatures
import com.smartalarm.core.model.SessionSummary
import com.smartalarm.core.protocol.WearJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Nights on disk.
 *
 * Finished nights are one JSON document each; the night in progress is a JSON-lines file that
 * epochs are appended to as they arrive from the watch. Append-only per-night documents fit the
 * data better than a relational schema would — a night is read whole or not at all, never
 * joined or queried across — and the format is still readable with `cat` when something needs
 * explaining at six in the morning.
 */
class SessionRepository(context: Context) {

    private val nightsDir = File(context.filesDir, "nights").apply { mkdirs() }
    private val activeDir = File(context.filesDir, "active").apply { mkdirs() }

    private val _nights = MutableStateFlow<List<SessionSummary>>(emptyList())

    /** Finished nights, most recent first. */
    val nights: StateFlow<List<SessionSummary>> = _nights.asStateFlow()

    init {
        _nights.value = readAllNights()
    }

    suspend fun saveNight(summary: SessionSummary) = withContext(Dispatchers.IO) {
        runCatching {
            File(nightsDir, "${summary.sessionId}.json")
                .writeText(WearJson.instance.encodeToString(summary))
        }.onFailure { Log.e(TAG, "could not save night ${summary.sessionId}", it) }
        _nights.value = readAllNights()
    }

    suspend fun deleteNight(sessionId: String) = withContext(Dispatchers.IO) {
        File(nightsDir, "$sessionId.json").delete()
        _nights.value = readAllNights()
    }

    /** Append epochs for the night in progress, so a killed process loses nothing. */
    fun appendEpochs(sessionId: String, epochs: List<EpochFeatures>) {
        if (epochs.isEmpty()) return
        runCatching {
            File(activeDir, "$sessionId.jsonl").appendText(
                epochs.joinToString(separator = "\n", postfix = "\n") {
                    WearJson.instance.encodeToString(it)
                }
            )
        }.onFailure { Log.e(TAG, "could not append epochs", it) }
    }

    /**
     * Read back the night in progress. Epochs are de-duplicated by index, because a watch that
     * reconnects after a gap may republish a batch the phone already has.
     */
    fun readEpochs(sessionId: String): List<EpochFeatures> {
        val file = File(activeDir, "$sessionId.jsonl")
        if (!file.exists()) return emptyList()
        val byIndex = LinkedHashMap<Int, EpochFeatures>()
        runCatching {
            file.forEachLine { line ->
                if (line.isNotBlank()) {
                    runCatching { WearJson.instance.decodeFromString<EpochFeatures>(line) }
                        .getOrNull()?.let { byIndex[it.index] = it }
                }
            }
        }.onFailure { Log.e(TAG, "could not read epochs for $sessionId", it) }
        return byIndex.values.sortedBy { it.index }
    }

    fun clearActive(sessionId: String) {
        File(activeDir, "$sessionId.jsonl").delete()
    }

    /** Remove stray in-progress files from nights that were never finished. */
    fun pruneActive(except: String?) {
        activeDir.listFiles()?.forEach { file ->
            if (except == null || file.nameWithoutExtension != except) file.delete()
        }
    }

    private fun readAllNights(): List<SessionSummary> =
        nightsDir.listFiles()
            ?.mapNotNull { file ->
                runCatching { WearJson.instance.decodeFromString<SessionSummary>(file.readText()) }
                    .getOrNull()
            }
            ?.sortedByDescending { it.startedMillis }
            ?: emptyList()

    private companion object {
        const val TAG = "SessionRepository"
    }
}
