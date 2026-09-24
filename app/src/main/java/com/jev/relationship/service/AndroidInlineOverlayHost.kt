package com.jev.relationship.service

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.jev.relationship.domain.inline.InlineAnalysisCard
import com.jev.relationship.domain.inline.InlineCardPlacement
import com.jev.relationship.ui.theme.JevTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

internal class AndroidInlineOverlayHost(
    private val context: Context,
    private val windowManager: WindowManager,
    private val owner: OverlayViewTreeOwner,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onOpen: (InlineAnalysisCard) -> Unit,
) : InlineOverlayHost {
    override val isDragging: Boolean
        get() = dragging

    private val cardState = MutableStateFlow<InlineAnalysisCard?>(null)
    private var view: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var activeMessageId: String? = null
    private var offsetX = 0
    private var offsetY = 0
    private var dragRemainderX = 0f
    private var dragRemainderY = 0f
    private var dragging = false
    private var dragFramePosted = false
    private var pendingX = 0f
    private var pendingY = 0f

    override fun show(card: InlineAnalysisCard, placement: InlineCardPlacement) {
        if (activeMessageId != card.anchor.messageId) {
            activeMessageId = card.anchor.messageId
            offsetX = 0
            offsetY = 0
        }
        cardState.value = card
        val params = layoutParams ?: WindowManager.LayoutParams(
            placement.width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            layoutParams = it
        }
        params.width = placement.width
        val currentView = view ?: createView(params).also { created ->
            view = created
            runCatching { windowManager.addView(created, params) }
                .onFailure {
                    view = null
                    cardState.value = null
                }
        }
        if (!dragging) {
            val maxX = (screenWidth - params.width).coerceAtLeast(0)
            val maxY = (screenHeight - (currentView.height.takeIf { it > 0 } ?: placement.height)).coerceAtLeast(0)
            params.x = (placement.left + offsetX).coerceIn(0, maxX)
            params.y = (placement.top + offsetY).coerceIn(0, maxY)
        }
        if (view === currentView && currentView.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(currentView, params) }
        }
    }

    override fun hide() {
        cardState.value = null
        view?.let { currentView ->
            runCatching { windowManager.removeView(currentView) }
        }
        view = null
        layoutParams = null
        activeMessageId = null
        offsetX = 0
        offsetY = 0
    }

    private fun createView(params: WindowManager.LayoutParams): ComposeView = ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            JevTheme {
                cardState.collectAsState().value?.let { card ->
                    InlineAnalysisCardView(
                        card = card,
                        onOpen = { onOpen(card) },
                        onDragStart = {
                            dragging = true
                            dragRemainderX = 0f
                            dragRemainderY = 0f
                            pendingX = params.x.toFloat()
                            pendingY = params.y.toFloat()
                        },
                        onDrag = { dx, dy ->
                            moveBy(params, height, dx, dy)
                        },
                        onDragEnd = {
                            applyPending(params, height)
                            dragging = false
                        },
                    )
                }
            }
        }
    }

    private fun moveBy(
        params: WindowManager.LayoutParams,
        viewHeight: Int,
        dx: Float,
        dy: Float,
    ) {
        dragRemainderX += dx
        dragRemainderY += dy
        val requestedX = dragRemainderX.roundToInt()
        val requestedY = dragRemainderY.roundToInt()
        dragRemainderX -= requestedX
        dragRemainderY -= requestedY
        pendingX = (pendingX + requestedX).coerceIn(
            0f,
            (screenWidth - params.width).coerceAtLeast(0).toFloat(),
        )
        pendingY = (pendingY + requestedY).coerceIn(
            0f,
            (screenHeight - viewHeight).coerceAtLeast(0).toFloat(),
        )
        if (!dragFramePosted) {
            dragFramePosted = true
            view?.postOnAnimation {
                dragFramePosted = false
                applyPending(params, viewHeight)
            }
        }
    }

    private fun applyPending(
        params: WindowManager.LayoutParams,
        viewHeight: Int,
    ) {
        val oldX = params.x
        val oldY = params.y
        params.x = pendingX.roundToInt().coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
        params.y = pendingY.roundToInt().coerceIn(0, (screenHeight - viewHeight).coerceAtLeast(0))
        offsetX += params.x - oldX
        offsetY += params.y - oldY
        if (view?.isAttachedToWindow == true) {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }
}
