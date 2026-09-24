package com.jev.relationship.domain.inline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineWindowSnapshotTest {
    @Test
    fun `builder hashes text and drops empty or invisible nodes`() {
        val snapshot = InlineWindowSnapshotBuilder.build(
            packageName = "com.tencent.mm",
            screenWidth = 1080,
            screenHeight = 1920,
            timestampMs = 42L,
            nodes = listOf(
                RawInlineNodeSnapshot("android.widget.TextView", "  你好   世界 ", 1, 2, 3, 4, true),
                RawInlineNodeSnapshot("android.widget.TextView", "", 1, 2, 3, 4, true),
                RawInlineNodeSnapshot("android.widget.TextView", "隐藏", 1, 2, 3, 4, false),
            ),
        )

        assertEquals(1, snapshot.nodes.size)
        assertNotEquals("你好   世界", snapshot.nodes.single().textHash)
        assertEquals(1, snapshot.nodes.single().left)
        assertEquals(4, snapshot.nodes.single().bottom)
    }

    @Test
    fun `store publishes and clears current snapshot`() {
        val store = InlineWindowSnapshotStore()
        val snapshot = InlineWindowSnapshotBuilder.build(
            packageName = "com.tencent.mm",
            screenWidth = 100,
            screenHeight = 200,
            timestampMs = 1L,
            nodes = emptyList(),
        )

        store.publish(snapshot)
        assertEquals(snapshot, store.state.value)
        store.clear()
        assertNull(store.state.value)
    }
}
