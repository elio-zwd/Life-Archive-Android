package com.elio.jianyu.telemetry

import kotlinx.coroutines.flow.StateFlow

interface DiagnosticServices {
    val events: StateFlow<List<TelemetryEvent>>
    val revision: StateFlow<Long>
    val payloadRevision: StateFlow<Long>
    val totalCount: StateFlow<Int>
    val level: StateFlow<TelemetryLevel>
    val storageError: StateFlow<String?>
    val usedBytes: StateFlow<Long>
    val capacity: StateFlow<Long>
    fun setLevel(level: TelemetryLevel): Boolean
    fun setCapacity(bytes: Long)
    fun delete(ids: Set<String>)
    fun deleteOperation(operationId: String)
    fun clearAllTelemetry(): Boolean
    suspend fun payloadSize(name: String): Long
    suspend fun formattedFile(name: String): String
    suspend fun readPage(name: String, offset: Long, size: Int = 16_384): Triple<String, Boolean, Long>
    suspend fun search(filter: DiagnosticFilter, limit: Int): Pair<List<TelemetryEvent>, Boolean>
    suspend fun wholeText(name: String?): String
    fun eventJson(event: TelemetryEvent): String
    suspend fun export(uri: String, selected: List<TelemetryEvent>?, operationId: String? = null, filter: DiagnosticFilter? = null)
}
