package com.jev.relationship.ipc

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IpcReplySuggestionTest {
    @Test fun `suggestion and explicit clearing survive bounded bundle transport`() {
        val reply = IpcReplySuggestion("hash", "wechat-8.0.72-20", "好的，明天见")
        assertEquals(reply, IpcReplySuggestion.fromBundle(reply.toBundle()))
        val clear = reply.copy(text = "")
        assertEquals(clear, IpcReplySuggestion.fromBundle(clear.toBundle()))
        val large = reply.copy(text = "字".repeat(1000))
        assertEquals(IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH,
            IpcReplySuggestion.fromBundle(large.toBundle()).text.length)
    }
}
