package com.jev.relationship.domain.inline

object InlineMessageLocator {
    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val GAP_PX = 16
    private val ignoredClassFragments = setOf(
        "edittext",
        "toolbar",
        "actionbar",
        "recyclerview",
    )

    fun locate(
        anchor: InlineMessageAnchor,
        nodes: List<InlineNodeSnapshot>,
        screenWidth: Int,
        screenHeight: Int,
        cardWidth: Int,
        cardHeight: Int,
    ): InlineCardPlacement? {
        if (screenWidth <= 0 || screenHeight <= 0 || cardWidth <= 0 || cardHeight <= 0) return null
        val candidate = nodes.asSequence()
            .filter { node ->
                node.packageName == WECHAT_PACKAGE &&
                    node.visibleToUser &&
                    node.textHash == anchor.textHash &&
                    node.right > node.left &&
                    node.bottom > node.top &&
                    ignoredClassFragments.none { fragment -> node.className.lowercase().contains(fragment) }
            }
            .maxByOrNull { node -> node.bottom }
            ?: return null

        val left = if (anchor.isOutgoing) {
            candidate.right - cardWidth
        } else {
            candidate.left
        }.coerceIn(0, (screenWidth - cardWidth).coerceAtLeast(0))

        val belowTop = candidate.bottom + GAP_PX
        val top = if (belowTop + cardHeight <= screenHeight) {
            belowTop
        } else {
            (candidate.top - GAP_PX - cardHeight).coerceAtLeast(0)
        }
        return InlineCardPlacement(left = left, top = top, width = cardWidth, height = cardHeight)
    }
}
