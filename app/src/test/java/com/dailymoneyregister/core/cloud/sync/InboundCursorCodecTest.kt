package com.dailymoneyregister.core.cloud.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboundCursorCodecTest {
    @Test
    fun cursorRoundTripsWithMaterializerVersion() {
        val cursor = VersionedInboundCursor(InboundCursorCodec.CURRENT_MATERIALIZER_VERSION, "2026-10-09T10:00:00Z", "op-1")
        assertEquals(cursor, InboundCursorCodec.decode(InboundCursorCodec.encode(cursor)))
    }

    @Test
    fun versionChangeResetsCursor() {
        val raw = InboundCursorCodec.encode(
            VersionedInboundCursor(1, "2026-10-09T10:00:00Z", "op-1")
        )
        assertNull(InboundCursorCodec.decode(raw))
        assertNull(InboundCursorCodec.decode("2026-10-09T10:00:00Z|op-1"))
    }
}
