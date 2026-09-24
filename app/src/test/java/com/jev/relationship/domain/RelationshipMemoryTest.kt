package com.jev.relationship.domain

import com.jev.relationship.core.model.Contact
import com.jev.relationship.core.model.MemoryKind
import com.jev.relationship.core.model.MemoryObservation
import com.jev.relationship.core.model.MemoryObservationDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipMemoryTest {
    @Test
    fun observationTextIsTrimmedAndBlankNotesAreRejected() {
        val valid = MemoryObservationDraft(" style ", MemoryKind.CommunicationStyle).toObservation("contact-1", 10L)

        assertEquals("style", valid!!.text)
        assertTrue(MemoryObservationDraft("  ", MemoryKind.UserNote).toObservation("contact-1", 10L) == null)
    }

    @Test
    fun observationsAreOrderedNewestFirst() {
        val older = MemoryObservation(1L, "contact-1", MemoryKind.UserNote, "older", 1.0, 10L)
        val newer = MemoryObservation(2L, "contact-1", MemoryKind.UserNote, "newer", 1.0, 20L)

        assertEquals(listOf(newer, older), listOf(older, newer).sortedByDescending { it.updatedAt })
    }

    @Test
    fun contactUsesStableIdAndDisplayName() {
        assertEquals(Contact("contact-1", "小王"), Contact("contact-1", "小王"))
    }
}
