package com.jev.relationship.feature.usage

import com.jev.relationship.data.usage.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class UsageChartModelTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    @Test fun `buckets are half open and separate model sources`() {
        val window = usageWindow(UsagePeriod.DAY, LocalDate.of(2026, 9, 24), zone)
        val events = listOf(
            TokenUsageEvent(timestampMs = window.start, source = "JEV", model = "j", totalTokens = 10, succeeded = true),
            TokenUsageEvent(timestampMs = window.start + 3_600_000, source = "UNDERSTANDING", model = "q", totalTokens = 20, succeeded = true),
            TokenUsageEvent(timestampMs = window.end, source = "JEV", model = "j", totalTokens = 999, succeeded = true))
        val buckets = usageBuckets(events, window)
        assertEquals(24, buckets.size)
        assertEquals(10L, buckets[0].total(UsageSource.JEV))
        assertEquals(20L, buckets[1].total(UsageSource.UNDERSTANDING))
        assertEquals(30L, buckets.sumOf { usageTotals(it.events).total })
    }
    @Test fun `calendar periods handle leap month and DST`() {
        assertEquals(29, usageWindow(UsagePeriod.MONTH, LocalDate.of(2024, 2, 1), zone).labels.size)
        assertEquals(23, usageWindow(UsagePeriod.DAY, LocalDate.of(2026, 3, 8), ZoneId.of("America/New_York")).labels.size)
        assertEquals(7, usageWindow(UsagePeriod.WEEK, LocalDate.of(2026, 9, 24), zone).labels.size)
    }
    @Test fun `unknown usage remains visible and units do not change raw totals`() {
        val events = listOf(TokenUsageEvent(source = "JEV", model = "j", succeeded = false),
            TokenUsageEvent(source = "JEV", model = "j", inputTokens = 1000, outputTokens = 500, totalTokens = 1500, succeeded = true))
        val totals = usageTotals(events)
        assertEquals(1500L, totals.total)
        assertEquals(1, totals.unreported)
        assertEquals(1, totals.failed)
        assertEquals("1.5", TokenUnit.THOUSAND.format(totals.total))
        assertEquals("0.000001", TokenUnit.MILLION.format(1))
        assertNull(ReportedTokens.parse(com.google.gson.JsonParser.parseString("""{"input_tokens":-1}""")))
    }
}
