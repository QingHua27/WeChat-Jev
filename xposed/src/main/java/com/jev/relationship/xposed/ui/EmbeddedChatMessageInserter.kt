package com.jev.relationship.xposed.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.RelativeLayout

/**
 * Places the analysis view in the matched message's own hierarchy.
 *
 * The wrapper is a real row child, so the result participates in the chat
 * list's normal measurement, scrolling, and recycling instead of being an
 * independently positioned overlay.
 */
class EmbeddedChatMessageInserter {
    private class MessageRow(
        val nativeMessage: View,
        val nativeParams: ViewGroup.LayoutParams,
        var owner: EmbeddedChatMessageInserter?,
    ) : LinearLayout(nativeMessage.context) {
        fun restoreNativeMessage() {
            val destination = parent as? ViewGroup ?: return
            val index = destination.indexOfChild(this)
            if (index < 0 || nativeMessage.parent !== this) return
            val innerParams = nativeMessage.layoutParams
            val wrapperParams = layoutParams
            removeView(nativeMessage)
            destination.removeView(this)
            try {
                destination.addView(nativeMessage, index.coerceAtMost(destination.childCount), nativeParams)
            } catch (_: Exception) {
                // A host-specific parent can reject restored params. Roll back the
                // hierarchy so a failed card cleanup never strands the native text.
                if (nativeMessage.parent == null) addView(nativeMessage, 0, innerParams)
                if (parent == null) destination.addView(this, index.coerceAtMost(destination.childCount), wrapperParams)
            }
        }
    }
    private var parent: ViewGroup? = null
    private var row: LinearLayout? = null
    private var message: View? = null
    private var analysis: View? = null
    private var originalLayoutParams: ViewGroup.LayoutParams? = null
    private var insertedInsideMessageRow = false
    val boundMessage: View? get() = message

    fun insertBelow(message: View, analysis: View, outgoing: Boolean = false): Boolean {
        if (this.message === message && this.analysis === analysis &&
            ((row?.parent != null && message.parent === row && analysis.parent === row) ||
                (insertedInsideMessageRow && analysis.parent === message))
        ) {
            return true
        }
        remove()

        // A recycled native text view may still belong to an older result's card.
        // Release that owner before inserting; nested wrappers let stale cleanup
        // detach the newer card together with the original WeChat message.
        (message.parent as? MessageRow)?.owner?.remove()
        (message.parent as? MessageRow)?.restoreNativeMessage()
        if (message.parent is MessageRow) return false

        val messageParent = message.parent as? ViewGroup ?: return false
        if (messageParent.javaClass.name.contains("RecyclerView", ignoreCase = true)) {
            return insertInsideRecyclerRow(message, analysis, outgoing)
        }
        val index = messageParent.indexOfChild(message)
        if (index < 0) return false

        val messageParams = message.layoutParams
        val messageRow = MessageRow(message, messageParams, this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (outgoing) Gravity.END else Gravity.START
        }
        return runCatching {
            messageParent.removeViewAt(index)
            messageRow.addView(
                message,
                LinearLayout.LayoutParams(
                    messageContentWidth(message),
                    messageContentHeight(message),
                ).apply {
                    gravity = if (outgoing) Gravity.END else Gravity.START
                },
            )
            messageRow.addView(
                analysis,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = if (outgoing) Gravity.END else Gravity.START
                },
            )
            // Copy rather than share parent-specific state, preserving avatar
            // spacing and alignment while allowing the analysis to grow the row.
            val wrapperParams = when (messageParams) {
                is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(messageParams)
                is RelativeLayout.LayoutParams -> RelativeLayout.LayoutParams(messageParams)
                is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(messageParams)
                is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(messageParams)
                else -> ViewGroup.LayoutParams(messageParams)
            }.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            messageParent.addView(messageRow, index, wrapperParams)

            parent = messageParent
            row = messageRow
            this.message = message
            this.analysis = analysis
            originalLayoutParams = messageParams
            insertedInsideMessageRow = false
            true
        }.getOrElse {
            runCatching { messageRow.removeView(message) }
            runCatching { messageRow.removeView(analysis) }
            runCatching {
                if (message.parent == null) {
                    messageParent.addView(
                        message,
                        index.coerceAtMost(messageParent.childCount),
                        messageParams,
                    )
                }
            }
            false
        }
    }

    fun remove() {
        if (insertedInsideMessageRow) {
            (message as? ViewGroup)?.removeView(analysis)
            parent = null
            row = null
            message = null
            analysis = null
            originalLayoutParams = null
            insertedInsideMessageRow = false
            return
        }
        val messageRow = row
        val messageView = message
        val analysisView = analysis
        if (messageRow != null && messageView != null) {
            // WeChat can detach/reparent a reusable row before our callback runs.
            // Never remove its native child unless there is a real destination.
            val actualParent = messageRow.parent as? ViewGroup
            analysisView?.let(messageRow::removeView)
            (messageRow as? MessageRow)?.restoreNativeMessage()
            if (actualParent != null && messageRow.childCount == 0) {
                actualParent.removeView(messageRow)
            }
            // A detached wrapper retains its native message until WeChat reattaches
            // it. It keeps its own restoration metadata, not a stale inserter owner.
            (messageRow as? MessageRow)?.owner = null
        }
        parent = null
        row = null
        message = null
        analysis = null
        originalLayoutParams = null
        insertedInsideMessageRow = false
    }

    private fun insertInsideRecyclerRow(
        message: View,
        analysis: View,
        outgoing: Boolean,
    ): Boolean {
        val rowParent = message as? ViewGroup ?: return false
        // A RecyclerView row can be attached before WeChat has measured it.
        // Inserting at offset 0 in that state makes the card render on top of
        // the row's other content. Let the host retry after layout instead.
        if (message.width <= 0 || message.height <= 0) return false
        return runCatching {
            val topMargin = message.height
            val layoutParams = when (rowParent) {
                is RelativeLayout -> RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    this.topMargin = topMargin
                    addRule(
                        if (outgoing) ALIGN_PARENT_RIGHT else ALIGN_PARENT_LEFT,
                    )
                }

                is FrameLayout -> FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = if (outgoing) Gravity.END else Gravity.START
                    this.topMargin = topMargin
                }

                else -> LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = if (outgoing) Gravity.END else Gravity.START
                }
            }
            rowParent.addView(analysis, layoutParams)
            parent = rowParent
            this.message = message
            this.analysis = analysis
            insertedInsideMessageRow = true
            true
        }.getOrDefault(false)
    }

    private fun messageContentWidth(message: View): Int {
        val width = message.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
        return if (width == ViewGroup.LayoutParams.MATCH_PARENT) {
            ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            width
        }
    }

    private fun messageContentHeight(message: View): Int {
        val height = message.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        return if (height == ViewGroup.LayoutParams.MATCH_PARENT) {
            ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            height
        }
    }

    private companion object {
        const val ALIGN_PARENT_LEFT = 9
        const val ALIGN_PARENT_RIGHT = 11
    }
}
