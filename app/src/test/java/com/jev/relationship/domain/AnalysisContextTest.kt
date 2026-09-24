package com.jev.relationship.domain

import com.jev.relationship.core.model.Contact
import com.jev.relationship.core.model.Conversation
import com.jev.relationship.core.model.MemoryKind
import com.jev.relationship.core.model.MemoryObservation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisContextTest {
    @Test
    fun memoryContextIsBoundedAndClearlyLabeledAsUserObservation() {
        val context = AnalysisContext(
            conversation = Conversation("她：还好吗？", contactId = "contact-1"),
            contact = Contact("contact-1", "小王"),
            observations = listOf(
                MemoryObservation(1L, "contact-1", MemoryKind.UserNote, "她不喜欢被连续追问".repeat(100), 1.0, 1L),
            ),
        )

        val promptContext = context.memoryPrompt()

        assertTrue(promptContext.contains("用户备注"))
        assertTrue(promptContext.length <= 1600)
        assertFalse(promptContext.contains("诊断"))
    }
}

