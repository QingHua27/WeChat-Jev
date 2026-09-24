package com.jev.relationship.data.local

import com.jev.relationship.core.model.Contact
import com.jev.relationship.core.model.MemoryKind
import com.jev.relationship.core.model.MemoryObservation
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactMemoryEntityMapperTest {
    @Test
    fun contactEntityRoundTripPreservesIdentity() {
        val entity = ContactEntity.from(Contact("contact-1", "小王"), updatedAt = 12L)

        assertEquals(Contact("contact-1", "小王"), entity.toDomain())
        assertEquals(12L, entity.updatedAt)
    }

    @Test
    fun memoryObservationEntityRoundTripPreservesKindAndConfidence() {
        val observation = MemoryObservation(8L, "contact-1", MemoryKind.EmotionalPattern, "生气时回复很短", 0.8, 42L)

        val restored = MemoryObservationEntity.from(observation).toDomain()

        assertEquals(observation, restored)
    }
}

