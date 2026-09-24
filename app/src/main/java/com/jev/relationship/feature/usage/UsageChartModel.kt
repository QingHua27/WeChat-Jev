package com.jev.relationship.feature.usage

import com.jev.relationship.data.usage.TokenUsageEvent
import com.jev.relationship.data.usage.UsageSource
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.text.NumberFormat

enum class UsagePeriod(val label: String) { DAY("日"), WEEK("周"), MONTH("月"), YEAR("年") }
enum class TokenUnit(val label: String, val divisor: Double) {
    TOKEN("Token", 1.0), THOUSAND("千 / K", 1_000.0), MILLION("百万 / M", 1_000_000.0);
    fun format(value: Long): String = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = when (this@TokenUnit) { TOKEN -> 0; THOUSAND -> 3; MILLION -> 6 }
    }.format(if (this == TOKEN) value else value / divisor)
}

data class UsageWindow(val boundaries: List<ZonedDateTime>, val labels: List<String>, val title: String) {
    val start: Long get() = boundaries.first().toInstant().toEpochMilli()
    val end: Long get() = boundaries.last().toInstant().toEpochMilli()
}

fun usageWindow(period: UsagePeriod, date: LocalDate, zone: ZoneId): UsageWindow {
    val first = when (period) {
        UsagePeriod.DAY -> date
        UsagePeriod.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        UsagePeriod.MONTH -> date.withDayOfMonth(1)
        UsagePeriod.YEAR -> date.withDayOfYear(1)
    }.atStartOfDay(zone)
    val end = when (period) {
        UsagePeriod.DAY -> first.plusDays(1)
        UsagePeriod.WEEK -> first.plusWeeks(1)
        UsagePeriod.MONTH -> first.plusMonths(1)
        UsagePeriod.YEAR -> first.plusYears(1)
    }
    val boundaries = mutableListOf(first)
    while (boundaries.last() < end) {
        boundaries += when (period) {
            UsagePeriod.DAY -> boundaries.last().plusHours(1)
            UsagePeriod.YEAR -> boundaries.last().plusMonths(1)
            else -> boundaries.last().plusDays(1)
        }
    }
    val pattern = when (period) {
        UsagePeriod.DAY -> "HH:mm"
        UsagePeriod.YEAR -> "M月"
        else -> "M/d"
    }
    val title = when (period) {
        UsagePeriod.DAY -> first.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        UsagePeriod.WEEK -> "${first.toLocalDate()} — ${end.minusDays(1).toLocalDate()}"
        UsagePeriod.MONTH -> first.format(DateTimeFormatter.ofPattern("yyyy年M月"))
        UsagePeriod.YEAR -> "${first.year}年"
    }
    return UsageWindow(boundaries, boundaries.dropLast(1).map { it.format(DateTimeFormatter.ofPattern(pattern)) }, title)
}

fun shiftUsageDate(date: LocalDate, period: UsagePeriod, direction: Long): LocalDate = when (period) {
    UsagePeriod.DAY -> date.plusDays(direction)
    UsagePeriod.WEEK -> date.plusWeeks(direction)
    UsagePeriod.MONTH -> date.plusMonths(direction)
    UsagePeriod.YEAR -> date.plusYears(direction)
}

data class UsageTotals(val input: Long = 0, val output: Long = 0, val total: Long = 0,
    val requests: Int = 0, val unreported: Int = 0, val missingBreakdown: Int = 0, val failed: Int = 0)

fun usageTotals(events: List<TokenUsageEvent>, source: UsageSource? = null): UsageTotals {
    val matching = events.filter { source == null || it.source == source.name }
    return UsageTotals(matching.sumOf { it.inputTokens ?: 0 }, matching.sumOf { it.outputTokens ?: 0 },
        matching.sumOf { it.totalTokens ?: 0 }, matching.size, matching.count { it.totalTokens == null },
        matching.count { it.inputTokens == null || it.outputTokens == null }, matching.count { !it.succeeded })
}

data class UsageBucket(val label: String, val events: List<TokenUsageEvent>) {
    fun total(source: UsageSource) = usageTotals(events, source).total
}

fun usageBuckets(events: List<TokenUsageEvent>, window: UsageWindow): List<UsageBucket> =
    window.labels.mapIndexed { index, label ->
        val start = window.boundaries[index].toInstant().toEpochMilli()
        val end = window.boundaries[index + 1].toInstant().toEpochMilli()
        UsageBucket(label, events.filter { it.timestampMs >= start && it.timestampMs < end })
    }
