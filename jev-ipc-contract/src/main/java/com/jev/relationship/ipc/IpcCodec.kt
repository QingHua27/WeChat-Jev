package com.jev.relationship.ipc

import android.os.Bundle

object IpcCodec {
    fun encodeHandshakeResult(result: HandshakeResult): Bundle = Bundle().apply {
        putBoolean(IpcProtocol.KEY_ACCEPTED, result.accepted)
        putString(IpcProtocol.KEY_REASON, result.reason?.name)
        putInt(IpcProtocol.KEY_SERVER_PROTOCOL_VERSION, result.serverProtocolVersion)
    }

    fun decodeHandshakeResult(bundle: Bundle): HandshakeResult = HandshakeResult(
        accepted = bundle.getBoolean(IpcProtocol.KEY_ACCEPTED),
        reason = bundle.getString(IpcProtocol.KEY_REASON).toRejectReasonOrNull(),
        serverProtocolVersion = bundle.getInt(IpcProtocol.KEY_SERVER_PROTOCOL_VERSION, IpcProtocol.VERSION),
    )

    fun encodeSubmitResult(result: SubmitResult): Bundle = Bundle().apply {
        putBoolean(IpcProtocol.KEY_ACCEPTED, result.accepted)
        putString(IpcProtocol.KEY_REASON, result.reason?.name)
        putInt(IpcProtocol.KEY_SERVER_PROTOCOL_VERSION, result.serverProtocolVersion)
    }

    fun decodeSubmitResult(bundle: Bundle): SubmitResult = SubmitResult(
        accepted = bundle.getBoolean(IpcProtocol.KEY_ACCEPTED),
        reason = bundle.getString(IpcProtocol.KEY_REASON).toRejectReasonOrNull(),
        serverProtocolVersion = bundle.getInt(IpcProtocol.KEY_SERVER_PROTOCOL_VERSION, IpcProtocol.VERSION),
    )

    fun encodeAnalysisResult(result: IpcAnalysisResult): Bundle {
        validateAnalysisResult(result)
        return Bundle().apply {
            putString(IpcProtocol.KEY_MESSAGE_ID, result.messageId)
            putString(IpcProtocol.KEY_CONVERSATION_ID, result.conversationHash)
            putString(IpcProtocol.KEY_TEXT, result.textHash)
            putBoolean(IpcProtocol.KEY_IS_OUTGOING, result.isOutgoing)
            putInt(IpcProtocol.KEY_MESSAGE_OCCURRENCE, result.messageOccurrence)
            putString(IpcProtocol.KEY_EMOTION, result.emotion)
            putStringArrayList(
                IpcProtocol.KEY_INTENT_NAMES,
                ArrayList(result.intents.map(IpcIntentProbability::name)),
            )
            putDoubleArray(
                IpcProtocol.KEY_INTENT_CONFIDENCES,
                result.intents.map(IpcIntentProbability::confidence).toDoubleArray(),
            )
            putInt(IpcProtocol.KEY_RISK_LEVEL, result.riskLevel)
            putString(IpcProtocol.KEY_SUGGESTION, result.suggestion)
            putString(IpcProtocol.KEY_DETAIL_SUMMARY, result.detailSummary)
            putString(IpcProtocol.KEY_DETAIL_INTENTION, result.detailIntention)
            putBoolean(IpcProtocol.KEY_DETAIL_CONTEXTUAL, result.detailContextual)
            putStringArrayList(IpcProtocol.KEY_DETAIL_EVIDENCE, ArrayList(result.detailEvidence))
            putString(IpcProtocol.KEY_DETAIL_ACTION, result.detailAction)
            putString(IpcProtocol.KEY_DETAIL_REPLY, result.detailReply)
            putParcelableArrayList(IpcProtocol.KEY_ANALYSIS_SECTIONS, ArrayList(result.sections.map { section ->
                Bundle().apply {
                    putString("title", section.title)
                    putString("body", section.text)
                    putStringArrayList("names", ArrayList(section.options.map { it.name }))
                    putDoubleArray("probabilities", section.options.map { it.confidence }.toDoubleArray())
                }
            }))
            result.historyId?.let { putLong(IpcProtocol.KEY_HISTORY_ID, it) }
        }
    }

    fun decodeAnalysisResult(bundle: Bundle): IpcAnalysisResult {
        val names = bundle.getStringArrayList(IpcProtocol.KEY_INTENT_NAMES).orEmpty()
        val confidences = bundle.getDoubleArray(IpcProtocol.KEY_INTENT_CONFIDENCES) ?: doubleArrayOf()
        require(names.size == confidences.size) { "intent fields have different sizes" }
        return IpcAnalysisResult(
            messageId = bundle.getString(IpcProtocol.KEY_MESSAGE_ID).orEmpty(),
            conversationHash = bundle.getString(IpcProtocol.KEY_CONVERSATION_ID).orEmpty(),
            textHash = bundle.getString(IpcProtocol.KEY_TEXT).orEmpty(),
            isOutgoing = bundle.getBoolean(IpcProtocol.KEY_IS_OUTGOING),
            messageOccurrence = bundle.getInt(IpcProtocol.KEY_MESSAGE_OCCURRENCE),
            emotion = bundle.getString(IpcProtocol.KEY_EMOTION).orEmpty(),
            intents = names.zip(confidences.toList(), ::IpcIntentProbability),
            riskLevel = bundle.getInt(IpcProtocol.KEY_RISK_LEVEL),
            suggestion = bundle.getString(IpcProtocol.KEY_SUGGESTION).orEmpty(),
            detailSummary = bundle.getString(IpcProtocol.KEY_DETAIL_SUMMARY).orEmpty(),
            detailIntention = bundle.getString(IpcProtocol.KEY_DETAIL_INTENTION).orEmpty(),
            detailContextual = bundle.getBoolean(IpcProtocol.KEY_DETAIL_CONTEXTUAL),
            detailEvidence = bundle.getStringArrayList(IpcProtocol.KEY_DETAIL_EVIDENCE).orEmpty(),
            detailAction = bundle.getString(IpcProtocol.KEY_DETAIL_ACTION).orEmpty(),
            detailReply = bundle.getString(IpcProtocol.KEY_DETAIL_REPLY).orEmpty(),
            sections = decodeSections(bundle),
            historyId = if (bundle.containsKey(IpcProtocol.KEY_HISTORY_ID)) {
                bundle.getLong(IpcProtocol.KEY_HISTORY_ID)
            } else {
                null
            },
        ).also(::validateAnalysisResult)
    }

    private fun validateAnalysisResult(result: IpcAnalysisResult) {
        require(result.sections.size <= IpcProtocol.MAX_ANALYSIS_SECTIONS) { "too many sections" }
        result.sections.forEach { section ->
            requireBounded(section.title, IpcProtocol.MAX_INTENT_NAME_LENGTH, "section title")
            require(section.text.length <= IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH) { "section text too long" }
            require(section.options.size <= IpcProtocol.MAX_ANALYSIS_INTENTS) { "too many section options" }
            section.options.forEach {
                requireBounded(it.name, IpcProtocol.MAX_INTENT_NAME_LENGTH, "option name")
                require(it.confidence.isFinite() && it.confidence in 0.0..1.0) { "invalid section probability" }
            }
        }
        requireBounded(result.messageId, IpcProtocol.MAX_MESSAGE_ID_LENGTH, "messageId")
        requireBounded(result.conversationHash, IpcProtocol.MAX_HASH_LENGTH, "conversationHash")
        requireBounded(result.textHash, IpcProtocol.MAX_HASH_LENGTH, "textHash")
        require(result.messageOccurrence >= 0) { "message occurrence must not be negative" }
        requireBounded(result.emotion, IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH, "emotion")
        requireBounded(result.suggestion, IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH, "suggestion")
        listOf(
            "detailSummary" to result.detailSummary,
            "detailIntention" to result.detailIntention,
            "detailAction" to result.detailAction,
            "detailReply" to result.detailReply,
        ).forEach { (field, value) ->
            if (value.isNotBlank()) requireBounded(value, IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH, field)
        }
        require(result.detailEvidence.size <= IpcProtocol.MAX_DETAIL_EVIDENCE) {
            "too many detail evidence items"
        }
        result.detailEvidence.forEach { evidence ->
            requireBounded(evidence, IpcProtocol.MAX_ANALYSIS_FIELD_LENGTH, "detailEvidence")
        }
        require(result.intents.size <= IpcProtocol.MAX_ANALYSIS_INTENTS) {
            "too many intents"
        }
        require(result.riskLevel in 0..10) { "risk level out of range" }
        result.intents.forEach { intent ->
            requireBounded(intent.name, IpcProtocol.MAX_INTENT_NAME_LENGTH, "intent name")
            require(intent.confidence.isFinite() && intent.confidence in 0.0..1.0) {
                "intent confidence out of range"
            }
        }
    }

    private fun requireBounded(value: String, maxLength: Int, field: String) {
        require(value.isNotBlank()) { "$field is blank" }
        require(value.length <= maxLength) { "$field is too long" }
    }

    @Suppress("DEPRECATION")
    private fun decodeSections(bundle: Bundle): List<IpcAnalysisSection> {
        val sections = bundle.getParcelableArrayList<Bundle>(IpcProtocol.KEY_ANALYSIS_SECTIONS).orEmpty()
        require(sections.size <= IpcProtocol.MAX_ANALYSIS_SECTIONS) { "too many sections" }
        return sections.map { section ->
            val names = section.getStringArrayList("names").orEmpty()
            val values = section.getDoubleArray("probabilities") ?: doubleArrayOf()
            require(names.size == values.size) { "section fields have different sizes" }
            IpcAnalysisSection(section.getString("title").orEmpty(), names.zip(values.toList(), ::IpcIntentProbability), section.getString("body").orEmpty())
        }
    }

    private fun String?.toRejectReasonOrNull(): RejectReason? = this?.let { value ->
        runCatching { RejectReason.valueOf(value) }.getOrNull()
    }
}
