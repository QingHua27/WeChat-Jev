package com.jev.relationship.xposed.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.jev.relationship.ipc.IpcAnalysisResult

class JevEmbeddedAnalysisCardView(context: Context) : LinearLayout(context) {
    private val content = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        setPadding(dp(6), dp(3), dp(6), dp(3))
        background = GradientDrawable().apply {
            setColor(Color.rgb(229, 229, 229))
            cornerRadius = dp(2).toFloat()
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
        content.setTextColor(Color.rgb(24, 24, 24))
        content.textSize = 12f
        content.maxWidth = dp(220)
        content.includeFontPadding = false
        content.setLineSpacing(0f, 1.05f)
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(result: IpcAnalysisResult) {
        val intention = if (!result.detailContextual) {
            result.detailIntention.ifBlank { "上下文意图暂不可用，请重新分析。" }
        } else {
            result.detailIntention.ifBlank { result.detailSummary }
        }
        val nextText = "解析：${intention.ifBlank { "暂未生成上下文意图。" }}"
        if (content.text.toString() != nextText) content.text = nextText
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
