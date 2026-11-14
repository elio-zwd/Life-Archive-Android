package com.elio.jianyu.telemetry

import android.content.Context
import android.net.Uri

class AndroidDiagnosticServices(context: Context) : DiagnosticServices {
    private val context = context.applicationContext
    init { TelemetryRepository.init(this.context) }
    override val events get() = TelemetryRepository.events
    override val revision get() = TelemetryRepository.revision
    override val payloadRevision get() = TelemetryRepository.payloadRevision
    override val totalCount get() = TelemetryRepository.totalCount
    override val level get() = TelemetryRepository.level
    override val storageError get() = TelemetryRepository.storageError
    override val usedBytes get() = TelemetryRepository.usedBytes
    override val capacity get() = TelemetryRepository.capacity
    override fun setLevel(level: TelemetryLevel) = TelemetryRepository.setLevel(context, level)
    override fun setCapacity(bytes: Long) = TelemetryRepository.setCapacity(bytes)
    override fun delete(ids: Set<String>) = TelemetryRepository.delete(ids)
    override fun deleteOperation(operationId: String) = TelemetryRepository.deleteOperation(operationId)
    override fun clearAllTelemetry() = TelemetryRepository.clearAllTelemetry(context)
    override suspend fun payloadSize(name: String) = TelemetryRepository.payloadSize(name)
    override suspend fun formattedFile(name: String) = TelemetryRepository.formattedFile(name)
    override suspend fun readPage(name: String, offset: Long, size: Int) = TelemetryRepository.readPage(name, offset, size)
    override suspend fun search(filter: DiagnosticFilter, limit: Int) = TelemetryRepository.search(filter, limit)
    override suspend fun wholeText(name: String?) = TelemetryRepository.wholeText(name)
    override fun eventJson(event: TelemetryEvent) = TelemetryRepository.eventJson(event)
    override suspend fun export(uri: String, selected: List<TelemetryEvent>?, operationId: String?, filter: DiagnosticFilter?) {
        TelemetryRepository.export(context, Uri.parse(uri), selected, operationId, filter)
    }
}
