package com.jev.relationship.xposed.ipc

object BackoffPolicy {
    private const val INITIAL_DELAY_MS = 250L
    private const val MAX_DELAY_MS = 30_000L

    fun delayMillis(attempt: Int): Long {
        if (attempt <= 1) return INITIAL_DELAY_MS
        val exponent = (attempt - 1).coerceAtMost(16)
        return (INITIAL_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)
    }
}
