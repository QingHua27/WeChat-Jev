package com.jev.relationship.domain.inline

import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RawInlineNodeSnapshot(
    val className: String,
    val text: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val visibleToUser: Boolean,
    val viewIdResourceName: String? = null,
)

data class InlineWindowSnapshot(
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val timestampMs: Long,
    val nodes: List<InlineNodeSnapshot>,
)

object InlineTextHasher {
    fun hash(value: String): String {
        val normalized = value.trim().replace(Regex("\\s+"), " ")
        return MessageDigest
            .getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

object InlineWindowSnapshotBuilder {
    fun build(
        packageName: String,
        screenWidth: Int,
        screenHeight: Int,
        timestampMs: Long,
        nodes: List<RawInlineNodeSnapshot>,
    ): InlineWindowSnapshot = InlineWindowSnapshot(
        packageName = packageName,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        timestampMs = timestampMs,
        nodes = nodes.asSequence()
            .filter { node ->
                node.visibleToUser &&
                    !node.text.isNullOrBlank() &&
                    node.right > node.left &&
                    node.bottom > node.top
            }
            .map { node ->
                InlineNodeSnapshot(
                    packageName = packageName,
                    className = node.className,
                    textHash = node.text?.let(InlineTextHasher::hash),
                    left = node.left,
                    top = node.top,
                    right = node.right,
                    bottom = node.bottom,
                    visibleToUser = node.visibleToUser,
                )
            }
            .toList(),
    )
}

class InlineWindowSnapshotStore {
    private val _state = MutableStateFlow<InlineWindowSnapshot?>(null)
    val state: StateFlow<InlineWindowSnapshot?> = _state.asStateFlow()

    fun publish(snapshot: InlineWindowSnapshot) {
        _state.value = snapshot
    }

    fun clear() {
        _state.value = null
    }
}
