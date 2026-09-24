package com.jev.relationship.ipc

/** System notifications are not dialogue, even when accessibility exposes them as TextView. */
object ChatTextPolicy {
    fun isDialogue(text: String): Boolean {
        val value = text.trim()
        return value.isNotEmpty() && !recalled.matches(value) &&
            !englishRecalled.matches(value) && !wechatCallRow.matches(value) &&
            !value.startsWith("<sysmsg", ignoreCase = true)
    }

    private val recalled = Regex(".{0,100}撤回了一条消息(?:[，,。 ]*重新编辑)?[。 ]*")
    private val englishRecalled = Regex(".{0,100}(?:recalled|withdrew) a message[. ]*", RegexOption.IGNORE_CASE)
    private val wechatCallRow = Regex(
        "^(?:通话时长|通话中断)\\s*\\d{1,2}:\\d{2}$|^(?:未应答|(?:对方|你)已取消)$",
    )
}
