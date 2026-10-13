package com.dailymoneyregister.core.cloud.sync

internal data class VersionedInboundCursor(
    val materializerVersion: Int,
    val receivedAtIso: String,
    val operationId: String
)

internal object InboundCursorCodec {
    const val CURRENT_MATERIALIZER_VERSION = 2

    fun encode(cursor: VersionedInboundCursor): String =
        "${cursor.materializerVersion}|${cursor.receivedAtIso}|${cursor.operationId}"

    fun decode(raw: String, expectedVersion: Int = CURRENT_MATERIALIZER_VERSION): VersionedInboundCursor? {
        val parts = raw.split("|", limit = 3)
        if (parts.size != 3 || parts.any { it.isBlank() }) return null
        val version = parts[0].toIntOrNull() ?: return null
        if (version != expectedVersion) return null
        return VersionedInboundCursor(version, parts[1], parts[2])
    }
}
