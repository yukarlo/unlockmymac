package com.yukarlo.unlockmymac.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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
        // Merged, not assigned. `_events.value = readFromDisk()` raced anything logged before the file
        // read finished and threw it away — and the earliest lines of a run are the ones worth keeping,
        // because that is where "previous run ended abruptly" is written. The next `persist` then wrote
        // the truncated list back, so the loss was permanent.
        //
        // Disk entries go first: they are older than anything this process can have logged.
        scope.launch {
            val fromDisk = readFromDisk()
            _events.update { live -> (fromDisk + live).takeLast(CAPACITY) }
        }
    }

    fun info(message: String) = add(EventLevel.INFO, message)

    fun warn(message: String) = add(EventLevel.WARN, message)

    fun error(message: String) = add(EventLevel.ERROR, message)

    fun add(
        level: EventLevel,
        message: String,
    ) {
        val event = LogEvent(System.currentTimeMillis(), level, message)
        // Atomic read-modify-write. Callers include GATT server callbacks, the BLE advertiser and
        // several coroutines on Dispatchers.IO, so a plain `_events.value = _events.value + event`
        // silently dropped entries whenever two of them interleaved — and this file is the primary
        // record used to debug the BLE behaviour, so a missing line is a missing measurement.
        val updated = _events.updateAndGet { (it + event).takeLast(CAPACITY) }
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
        // 500, not 200. This log is the only record of what the peripheral did while nobody was
        // watching, and a few busy minutes of connects and disconnects used to evict hours of it —
        // which is how an eleven-hour outage came to have no visible beginning.
        const val CAPACITY = 500
        const val TAG = "EventLog"
    }
}
