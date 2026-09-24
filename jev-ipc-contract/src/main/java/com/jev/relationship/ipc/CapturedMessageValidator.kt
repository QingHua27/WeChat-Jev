package com.jev.relationship.ipc

object CapturedMessageValidator {
    fun validate(message: CapturedMessage): ValidationResult {
        if (message.conversationId.isBlank()) {
            return ValidationResult(false, RejectReason.CONVERSATION_ID_REQUIRED)
        }
        if (message.sourcePackage != IpcProtocol.WECHAT_PACKAGE) {
            return ValidationResult(false, RejectReason.SOURCE_PACKAGE_NOT_ALLOWED)
        }
        if (message.text.isBlank()) {
            return ValidationResult(false, RejectReason.BLANK_TEXT)
        }
        if (message.text.length > IpcProtocol.MAX_TEXT_LENGTH) {
            return ValidationResult(false, RejectReason.TEXT_TOO_LONG)
        }
        if (message.timestampMs <= 0L) {
            return ValidationResult(false, RejectReason.TIMESTAMP_INVALID)
        }
        return ValidationResult(accepted = true)
    }

    fun validateBatch(messages: List<CapturedMessage>): ValidationResult {
        if (messages.size > IpcProtocol.MAX_BATCH_SIZE) {
            return ValidationResult(false, RejectReason.BATCH_TOO_LARGE)
        }
        messages.forEach { message ->
            val result = validate(message)
            if (!result.accepted) return result
        }
        return ValidationResult(accepted = true)
    }
}
