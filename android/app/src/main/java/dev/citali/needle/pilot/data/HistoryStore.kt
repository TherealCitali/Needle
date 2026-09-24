package dev.citali.needle.pilot.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.historyDataStore by preferencesDataStore(name = "needle_pilot_history")

/**
 * A small, redacted task history. Only the user's command text and a status are
 * stored — never accessibility-tree values, API keys, or sensitive task data.
 */
data class HistoryEntry(
    val command: String,
    val status: String,
    val completedAtMillis: Long,
)

object HistoryStore {
    private val KEY = stringPreferencesKey("entries_json")
    private const val MAX_ENTRIES = 50

    fun entries(context: Context): Flow<List<HistoryEntry>> =
        context.historyDataStore.data.map { p -> parse(p[KEY]) }

    suspend fun record(context: Context, command: String, status: String) {
        context.historyDataStore.edit { p ->
            val updated = (
                listOf(HistoryEntry(command.trim(), status, System.currentTimeMillis())) +
                    parse(p[KEY])
                ).take(MAX_ENTRIES)
            p[KEY] = serialize(updated)
        }
    }

    suspend fun clear(context: Context) {
        context.historyDataStore.edit { it.remove(KEY) }
    }

    private fun parse(raw: String?): List<HistoryEntry> = runCatching {
        val arr = JSONArray(raw ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryEntry(
                command = o.optString("command"),
                status = o.optString("status"),
                completedAtMillis = o.optLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    private fun serialize(list: List<HistoryEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("command", e.command)
                    .put("status", e.status)
                    .put("at", e.completedAtMillis)
            )
        }
        return arr.toString()
    }
}
