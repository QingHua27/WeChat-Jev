package com.jev.relationship.xposed.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jev.relationship.ipc.IpcReplySuggestion

/** Owns only its added row. Native message and composer views are never reparented. */
class WechatReplySuggestionBar(
    private val resourceName: (View) -> String = {
        runCatching { it.resources.getResourceEntryName(it.id) }.getOrDefault("")
    },
) {
    private var conversationHash = ""
    private var latestId = Long.MIN_VALUE
    private var suggestion = ""
    private var enabled = true
    private var resumed = true
    private var userHidden = false
    private var transitionPending = false
    private var transitionGeneration = 0
    private val portal = ReplySuggestionPortal()
    private var row: LinearLayout? = null
    private var preview: TextView? = null
    private var composer: EditText? = null

    fun setConversation(hash: String) {
        if (hash == conversationHash) return
        cancelTransition()
        conversationHash = hash
        latestId = Long.MIN_VALUE
        suggestion = ""
        render()
    }

    fun setEnabled(value: Boolean) { enabled = value; render() }
    fun setResumed(value: Boolean) { resumed = value; render() }

    fun setUserHidden(value: Boolean, anchor: View? = null) {
        if (userHidden == value) return
        userHidden = value
        val view = row
        if (anchor == null || view == null || !enabled || !resumed || suggestion.isBlank()) {
            cancelTransition()
            render()
            return
        }
        val generation = ++transitionGeneration
        transitionPending = true
        fun animate() {
            if (generation != transitionGeneration || row !== view) return
            transitionPending = false
            val started = portal.animate(view, anchor, value) { render() }
            if (!started) { portal.cancel(); render() }
        }
        if (!value && !portal.isRunning) {
            // INVISIBLE participates in layout, so keyboard changes cannot leave a stale destination.
            view.visibility = View.INVISIBLE
            view.post { animate() }
        } else animate()
    }

    fun update(value: IpcReplySuggestion) {
        if (conversationHash.isBlank() || value.conversationHash != conversationHash) return
        val id = value.messageId.removePrefix("wechat-8.0.72-").toLongOrNull() ?: return
        if (id < latestId) return
        latestId = id
        suggestion = value.text.trim()
        render()
    }

    fun attach(root: View) {
        val input = findComposer(root) ?: return
        var footer: View = input
        while (true) {
            val parent = footer.parent as? ViewGroup ?: return
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL && resourceName(parent) == "cal") {
                if (composer === input && row?.parent === parent) return
                detach()
                composer = input
                val context = input.context
                fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
                val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                val next = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(4), dp(8), dp(4))
                    setBackgroundColor(if (dark) Color.rgb(35, 35, 35) else Color.rgb(247, 247, 247))
                    visibility = View.GONE
                }
                preview = TextView(context).apply {
                    textSize = 14f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(if (dark) Color.rgb(225, 225, 225) else Color.rgb(55, 55, 55))
                    setPadding(0, dp(4), dp(10), dp(4))
                }.also { next.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
                next.addView(TextView(context).apply {
                    text = "回复"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    minHeight = dp(48)
                    minWidth = dp(64)
                    contentDescription = "将建议回复填入输入框"
                    isFocusable = true
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(7, 165, 85)); cornerRadius = dp(8).toFloat()
                    }
                    setOnClickListener { fillComposer() }
                }, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
                parent.addView(next, parent.indexOfChild(footer), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                row = next
                render()
                return
            }
            footer = parent
        }
    }

    fun detach() {
        cancelTransition()
        row?.let { (it.parent as? ViewGroup)?.removeView(it) }
        row = null
        preview = null
        composer = null
    }

    private fun render() {
        val label = "建议回复：$suggestion"
        if (preview?.text?.toString() != label) preview?.text = label
        preview?.contentDescription = label
        val available = enabled && resumed && suggestion.isNotBlank()
        if (!available) cancelTransition()
        if (portal.isRunning || transitionPending) return
        row?.visibility = if (available && !userHidden) View.VISIBLE else View.GONE
    }

    private fun cancelTransition() {
        transitionGeneration++
        transitionPending = false
        portal.cancel()
    }

    private fun fillComposer() {
        val input = composer ?: return
        if (!enabled || !resumed || userHidden || portal.isRunning || suggestion.isBlank() || row?.visibility != View.VISIBLE) return
        val draft = input.text.toString()
        if (draft != suggestion && !draft.endsWith("\n$suggestion")) {
            input.setText(if (draft.isBlank()) suggestion else "$draft\n$suggestion")
        }
        input.setSelection(input.text.length)
        input.requestFocus()
        (input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        Toast.makeText(input.context, "已填入，可编辑后发送", Toast.LENGTH_SHORT).show()
    }

    private fun findComposer(root: View): EditText? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var inspected = 0
        while (queue.isNotEmpty() && inspected++ < 2500) {
            val view = queue.removeFirst()
            if (view.visibility != View.VISIBLE) continue
            if (view is EditText && resourceName(view) == "c3t") return view
            if (view is ViewGroup) for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
        }
        return null
    }
}
