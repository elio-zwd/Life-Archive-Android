package com.elio.jianyu.ui.screens.settings

import com.elio.jianyu.network.KeyStatus
import com.elio.jianyu.telemetry.TelemetryEvent
import com.elio.jianyu.telemetry.TelemetryLevel
import com.elio.jianyu.telemetry.DiagnosticFilter

data class TelemetryUiState(
    val events: List<TelemetryEvent>,
    val level: TelemetryLevel,
    val storageError: String?,
    val expandedEventId: String?,
    val usedBytes: Long = 0,
    val capacity: Long = 500L shl 20,
    val filter: DiagnosticFilter = DiagnosticFilter(),
    val totalEvents: Int = events.size,
    val detailTab: String = "概览",
    val page: String = "",
    val pageOffset: Long = 0,
    val nextPageOffset: Long = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val detailError: String? = null,
    val busyMessage: String? = null,
    val formatted: Boolean = false,
    val moreRecords: Boolean = false,
)

data class TelemetryEventPresentation(
    val isSuccess: Boolean,
    val statusText: String,
    val tone: SettingsTone,
)

internal fun telemetryLevelDescription(
    level: TelemetryLevel,
): String {
    return when (level) {
        TelemetryLevel.OFF -> "关闭：不创建本地遥测事件"
        TelemetryLevel.METADATA_ONLY -> "仅元数据（默认）：不读取或保存请求/回复正文"
        TelemetryLevel.CONTENT_DEBUG -> "完整记录：原始请求与响应，仅隐藏认证凭据；手动关闭和清理"
    }
}

internal fun telemetryAvailableKeyCount(statuses: List<KeyStatus>): Int {
    return statuses.count { !it.isBanned && !it.isManualDisabled }
}

internal fun telemetryEventPresentation(event: TelemetryEvent): TelemetryEventPresentation {
    val success = event.failureType == null &&
        event.errorMessage == null &&
        (event.statusCode?.let { it in 200..299 } ?: (event.recoveryResult == "SUCCESS"))
    val status = if (event.recoveryResult == "WAITING") "等待" else if (success) event.statusCode?.toString() ?: "成功" else "ERR"
    return TelemetryEventPresentation(
        isSuccess = success,
        statusText = "$status · ${event.durationMs}ms",
        tone = if (success || event.recoveryResult == "WAITING") SettingsTone.PRIMARY else SettingsTone.ERROR,
    )
}
