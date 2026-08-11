package com.yukarlo.unlockmymac.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class EventLevel { INFO, WARN, ERROR }

class LogEvent(
    val atMs: Long,
    val level: EventLevel,
    val message: String,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("t", atMs)
            .put("l", level.name)
            .put("m", message)

    companion object {
        fun fromJson(json: JSONObject): LogEvent =
            LogEvent(
                atMs = json.optLong("t"),
                level = runCatching { EventLevel.valueOf(json.optString("l")) }.getOrDefault(EventLevel.INFO),
                message = json.optString("m"),
            )
    }
}

/**
 * User-visible diagnostics ring buffer, mirrored to a JSON file so a restart does not erase
 * the evidence of what happened overnight.
 *
 * Callers must never pass challenge payloads, signatures, or key material here — the log is
 * shown in the UI and readable from app storage. Use [challengeTag] to refer to a challenge.
 */
class EventLog(
    context: Context,
) {
    private val file = File(context.filesDir, FILE_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    init {
        scope.launch { _events.value = readFromDisk() }
    }

    fun info(message: String) = add(EventLevel.INFO, message)

    fun warn(message: String) = add(EventLevel.WARN, message)

    fun error(message: String) = add(EventLevel.ERROR, message)

    fun add(
        level: EventLevel,
        message: String,
    ) {
        val event = LogEvent(System.currentTimeMillis(), level, message)
        val updated = (_events.value + event).takeLast(CAPACITY)
        _events.value = updated
        scope.launch { persist(updated) }
    }

    fun clear() {
        _events.value = emptyList()
        scope.launch { persist(emptyList()) }
    }

    private suspend fun persist(events: List<LogEvent>) =
        writeMutex.withLock {
            runCatching {
                val array = JSONArray()
                events.forEach { array.put(it.toJson()) }
                file.writeText(array.toString())
            }.onFailure { Log.w(TAG, "Could not persist event log", it) }
            Unit
        }

    private fun readFromDisk(): List<LogEvent> =
        runCatching {
            if (!file.exists()) return@runCatching emptyList()
            val array = JSONArray(file.readText())
            (0 until array.length())
                .mapNotNull { index ->
                    array.optJSONObject(index)?.let(LogEvent::fromJson)
                }.takeLast(CAPACITY)
        }.getOrDefault(emptyList())

    private companion object {
        const val FILE_NAME = "events.json"
        const val CAPACITY = 200
        const val TAG = "EventLog"
    }
}
