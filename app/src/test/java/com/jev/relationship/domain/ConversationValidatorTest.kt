package com.jev.relationship.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationValidatorTest {
    private val validator = ConversationValidator()

    @Test
    fun blankConversationIsRejected() {
        val result = validator.validate("  \n\t")

        assertTrue(result is ConversationValidation.Invalid)
        assertEquals(ConversationValidation.Error.Empty, (result as ConversationValidation.Invalid).error)
    }

    @Test
    fun validConversationIsTrimmedButKeepsInternalLineBreaks() {
        val result = validator.validate("  你：今天怎么没找我？\n她：忙  ")

        assertEquals(
            ConversationValidation.Valid("你：今天怎么没找我？\n她：忙"),
            result,
        )
    }

    @Test
    fun confidenceAndRiskAreKeptInsideProductRanges() {
        assertEquals(0.0, normalizeConfidence(-0.2), 0.0)
        assertEquals(1.0, normalizeConfidence(1.2), 0.0)
        assertEquals(0, normalizeRisk(-1))
        assertEquals(10, normalizeRisk(12))
    }
}

