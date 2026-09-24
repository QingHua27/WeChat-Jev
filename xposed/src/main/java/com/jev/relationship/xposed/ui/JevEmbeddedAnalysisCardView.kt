package com.jev.relationship.xposed.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.jev.relationship.ipc.IpcAnalysisResult

class JevEmbeddedAnalysisCardView(context: Context) : LinearLayout(context) {
    private val content = TextView(context)
    private var appliedNightMode: Boolean? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        setPadding(dp(6), dp(3), dp(6), dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dp(2).toFloat()
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
        content.textSize = 12f
        content.maxWidth = dp(220)
        content.includeFontPadding = false
        content.setLineSpacing(0f, 1.05f)
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        applyTheme(resources.configuration.uiMode)
    }

    fun applyTheme(uiMode: Int) {
        val dark = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (appliedNightMode == dark) return
        appliedNightMode = dark
        (background as GradientDrawable).setColor(if (dark) Color.BLACK else Color.rgb(229, 229, 229))
        content.setTextColor(if (dark) Color.WHITE else Color.rgb(24, 24, 24))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme(newConfig.uiMode)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyTheme((parent as? View)?.resources?.configuration?.uiMode ?: resources.configuration.uiMode)
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
