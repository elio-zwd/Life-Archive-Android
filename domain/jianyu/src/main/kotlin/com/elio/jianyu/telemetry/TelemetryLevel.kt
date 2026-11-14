package com.elio.jianyu.telemetry

enum class TelemetryLevel {
    OFF,
    METADATA_ONLY,
    CONTENT_DEBUG
}

object TelemetryLevelResolver {
    fun resolve(requested: TelemetryLevel, isDebugBuild: Boolean): TelemetryLevel {
        return requested
    }
}
