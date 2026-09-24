package com.jev.relationship.ipc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextPolicyTest {
    @Test
    fun `wechat call and system rows are not dialogue`() {
        listOf(
            "通话时长 06:24",
            "通话中断 01:46",
            "未应答",
            "对方已取消",
        ).forEach { assertFalse(it, ChatTextPolicy.isDialogue(it)) }
    }

    @Test
    fun `ordinary conversation remains dialogue`() {
        assertTrue(ChatTextPolicy.isDialogue("干嘛呢？"))
    }
}
