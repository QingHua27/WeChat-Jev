package com.jev.relationship.xposed.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jev.relationship.ipc.ChatAssistantRequest
import com.jev.relationship.ipc.ChatAssistantResult
import com.jev.relationship.ipc.ChatAssistantTurn
import java.util.UUID

class ChatAssistantDialog(
    activity: Activity,
    private val conversationId: String,
    private val title: String,
    private val requestAssistant: (ChatAssistantRequest, (ChatAssistantResult) -> Unit) -> Boolean,
    private val cancelRequest: (String) -> Unit,
) : Dialog(activity) {
    private val density = activity.resources.displayMetrics.density
    private val panel = LinearLayout(activity)
    private val turnList = LinearLayout(activity)
    private val status = TextView(activity)
    private val historyNotice = TextView(activity)
    private val retryButton = Button(activity)
    private val refreshButton = Button(activity)
    private val scroll = ScrollView(activity)
    private val composer = EditText(activity)
    private var activeRequestId: String? = null
    private var retryQuestion: String? = null
    private var retryResumeSession = false
    private var retryFullContext = false
    private var busy = false
    private var hasContent = false
    private var streamingText: TextView? = null
    private val streamingViews = mutableListOf<View>()
    private val visibleFrame = Rect()
    private val resizeForKeyboard = ViewTreeObserver.OnGlobalLayoutListener { fitAvailableHeight() }

    private fun fitAvailableHeight() {
        val dialogWindow = window ?: return
        dialogWindow.decorView.getWindowVisibleDisplayFrame(visibleFrame)
        if (visibleFrame.height() <= 0) return
        val preferredHeight = (context.resources.displayMetrics.heightPixels * 0.88f).toInt()
        // Floating windows keep their explicit height even with adjustResize. Fit the
        // visible display frame, which excludes both the IME and system bars.
        val height = minOf(preferredHeight, (visibleFrame.height() - dp(16)).coerceAtLeast(1))
        if (dialogWindow.attributes.height != height) {
            dialogWindow.setLayout(dialogWindow.attributes.width, height)
        }
    }

    @Suppress("DEPRECATION")
    fun open() {
        buildContent()
        setContentView(panel)
        setCancelable(true)
        setOnDismissListener {
            window?.decorView?.viewTreeObserver?.removeOnGlobalLayoutListener(resizeForKeyboard)
            activeRequestId?.let(cancelRequest)
            activeRequestId = null
        }
        show()
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((context.resources.displayMetrics.widthPixels * 0.92f).toInt(), (context.resources.displayMetrics.heightPixels * 0.88f).toInt())
            setGravity(Gravity.CENTER)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            decorView.viewTreeObserver.addOnGlobalLayoutListener(resizeForKeyboard)
            decorView.post { if (isShowing) fitAvailableHeight() }
        }
        request(question = null, resumeSession = true)
    }

    private fun buildContent() {
        if (hasContent) return
        hasContent = true
        val white = Color.WHITE
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(18), dp(14), dp(18), dp(14))
        panel.background = rounded(Color.rgb(32, 33, 36), dp(18))

        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = "AI分析 · $title"
            textSize = 19f
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(TextView(context).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(white)
            contentDescription = "关闭分析"
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        panel.addView(header)

        val disclosure = TextView(context).apply {
            text = "省 Token 模式：默认发送近期及相关聊天片段和最近问答。需要全部历史时点“完整分析”。内容发送到已配置的理解模型，对话自动保存在本机。"
            textSize = 12f
            setTextColor(Color.rgb(190, 193, 199))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(45, 47, 52), dp(10))
        }
        panel.addView(disclosure, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        historyNotice.apply {
            text = "较早的 AI 问答仍保存在本机；窗口显示近期内容，默认仅发送最近问答。"
            textSize = 12f
            setTextColor(Color.rgb(190, 193, 199))
            visibility = View.GONE
        }
        panel.addView(historyNotice, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        scroll.apply {
            isFillViewport = true
            addView(turnList, FrameLayout.LayoutParams(-1, -2))
        }
        turnList.orientation = LinearLayout.VERTICAL
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        status.setTextColor(Color.rgb(250, 195, 90))
        status.textSize = 14f
        panel.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        retryButton.text = "重试"
        retryButton.visibility = View.GONE
        retryButton.setOnClickListener { request(retryQuestion, retryResumeSession, retryFullContext) }
        refreshButton.text = "完整分析"
        refreshButton.setOnClickListener { request(question = null, fullContext = true) }
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(retryButton, LinearLayout.LayoutParams(-2, dp(44)))
            addView(refreshButton, LinearLayout.LayoutParams(-2, dp(44)))
        })

        val composerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        composer.hint = "继续提问，不明白的可以直接问我"
        composer.setTextColor(white)
        composer.setHintTextColor(Color.LTGRAY)
        composer.textSize = 14f
        composer.minLines = 1
        composer.maxLines = 4
        composer.setPadding(dp(12), dp(10), dp(12), dp(10))
        composer.background = rounded(Color.rgb(48, 50, 55), dp(10))
        composerRow.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
        composerRow.addView(Button(context).apply {
            text = "发送"
            setOnClickListener { submitQuestion() }
        }, LinearLayout.LayoutParams(dp(72), dp(48)).apply { marginStart = dp(8) })
        panel.addView(composerRow)
    }

    private fun submitQuestion() {
        if (busy) return
        val question = composer.text?.toString()?.trim().orEmpty()
        if (question.isBlank()) {
            status.text = "请输入想了解的问题。"
            return
        }
        if (question.length > ChatAssistantRequest.MAX_QUESTION_LENGTH) {
            status.text = "问题不能超过 ${ChatAssistantRequest.MAX_QUESTION_LENGTH} 个字符。"
            return
        }
        request(question)
    }

    private fun request(question: String?, resumeSession: Boolean = false, fullContext: Boolean = false) {
        if (!isShowing || busy) return
        activeRequestId?.let(cancelRequest)
        clearStreamingViews()
        val request = ChatAssistantRequest(UUID.randomUUID().toString(), conversationId, title, question, resumeSession, fullContext)
        activeRequestId = request.requestId
        retryQuestion = question
        retryResumeSession = resumeSession
        retryFullContext = fullContext
        busy = true
        status.text = when {
            resumeSession -> "正在加载对话，首次打开会生成分析…"
            fullContext -> "正在读取完整聊天并分析，本次消耗较多 Token…"
            question == null -> "正在选取聊天片段并分析…"
            else -> "正在选取相关聊天并回答…"
        }
        retryButton.visibility = View.GONE
        composer.isEnabled = false
        refreshButton.isEnabled = false
        val accepted = requestAssistant(request) { result ->
            if (!isShowing || activeRequestId != result.requestId || result.conversationId != conversationId) return@requestAssistant
            if (result.isPartial) {
                val text = result.turns.single().content
                if (streamingText == null) {
                    if (question != null) {
                        val questionView = appendTurn(ChatAssistantTurn(ChatAssistantTurn.ROLE_USER, question, 0L))
                        streamingViews += questionView.parent as View
                    }
                    streamingText = appendTurn(result.turns.single()).also { streamingViews += it.parent as View }
                } else {
                    streamingText?.text = text
                }
                status.text = "正在回复…"
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                return@requestAssistant
            }
            val hadPartial = streamingText != null
            clearStreamingViews()
            activeRequestId = null
            busy = false
            composer.isEnabled = true
            refreshButton.isEnabled = true
            if (result.error != null) {
                status.text = result.error + if (hadPartial) "\n本次回复未完成，未保存。" else ""
                retryButton.visibility = View.VISIBLE
            } else {
                status.text = ""
                retryButton.visibility = View.GONE
                historyNotice.visibility = if (result.olderTurnsOmitted) View.VISIBLE else View.GONE
                renderTurns(result.turns)
                if (question != null) composer.setText("")
            }
        }
        if (!accepted) {
            activeRequestId = null
            busy = false
            composer.isEnabled = true
            refreshButton.isEnabled = true
            status.text = "Jev 连接未就绪，请检查模块配对后重试。"
            retryButton.visibility = View.VISIBLE
        }
    }

    private fun renderTurns(turns: List<ChatAssistantTurn>) {
        turnList.removeAllViews()
        turns.forEach(::appendTurn)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearStreamingViews() {
        streamingViews.forEach(turnList::removeView)
        streamingViews.clear()
        streamingText = null
    }

    private fun appendTurn(turn: ChatAssistantTurn): TextView {
        val isAssistant = turn.role == ChatAssistantTurn.ROLE_ASSISTANT
        val displayText = if (
            !isAssistant && turn.content in listOf(ChatAssistantRequest.DEFAULT_ANALYSIS_PROMPT, ChatAssistantRequest.SAVING_ANALYSIS_PROMPT)
        ) {
            "开始上传并分析"
        } else {
            turn.content
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(if (isAssistant) Color.rgb(238, 240, 242) else Color.rgb(7, 193, 96), dp(12))
        }
        card.addView(TextView(context).apply {
            text = if (isAssistant) "Jev" else "我"
            textSize = 12f
            setTextColor(if (isAssistant) Color.DKGRAY else Color.rgb(0, 65, 30))
            typeface = Typeface.DEFAULT_BOLD
        })
        val content = TextView(context).apply {
            text = displayText
            textSize = 15f
            setTextColor(Color.rgb(28, 29, 31))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        card.addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        turnList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        return content
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int) = (value * density).toInt()
}
