package com.jev.relationship.xposed.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** A non-interactive snapshot in the window overlay; native views never leave their parents. */
internal class ReplySuggestionPortal {
    private var animator: ValueAnimator? = null
    private var overlay: PortalView? = null
    private var host: ViewGroup? = null
    private var source: View? = null
    private var target: View? = null
    private var progress = 0f

    val isRunning: Boolean get() = overlay != null

    fun animate(row: View, anchor: View, hiding: Boolean, onComplete: () -> Unit): Boolean {
        if (!ValueAnimator.areAnimatorsEnabled() || !row.isAttachedToWindow ||
            !anchor.isShown || row.width <= 0 || row.height <= 0) return false
        val root = row.rootView as? ViewGroup ?: return false
        if (anchor.rootView !== root) return false
        if (source !== row || target !== anchor || overlay == null) {
            cancel()
            val bitmap = runCatching {
                Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888).also {
                    row.draw(Canvas(it))
                }
            }.getOrNull() ?: return false
            val rootPosition = IntArray(2).also(root::getLocationOnScreen)
            val start = IntArray(2).also(row::getLocationOnScreen)
            val end = IntArray(2).also(anchor::getLocationOnScreen)
            val frame = RectF((start[0] - rootPosition[0]).toFloat(), (start[1] - rootPosition[1]).toFloat(),
                (start[0] - rootPosition[0] + row.width).toFloat(), (start[1] - rootPosition[1] + row.height).toFloat())
            val ghost = PortalView(row, bitmap, frame,
                end[0] - rootPosition[0] + anchor.width / 2f,
                end[1] - rootPosition[1] + anchor.height / 2f)
            ghost.layout(0, 0, root.width, root.height)
            root.overlay.add(ghost)
            host = root
            source = row
            target = anchor
            overlay = ghost
            progress = if (hiding) 0f else 1f
        }
        stopAnimator()
        row.visibility = View.INVISIBLE
        val endProgress = if (hiding) 1f else 0f
        overlay?.progress = progress
        animator = ValueAnimator.ofFloat(progress, endProgress).apply {
            duration = (520 * abs(endProgress - progress)).toLong().coerceAtLeast(100)
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                overlay?.progress = progress
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    this@ReplySuggestionPortal.cancel()
                    onComplete()
                }
            })
            start()
        }
        return true
    }

    fun cancel() {
        stopAnimator()
        overlay?.let { view ->
            host?.overlay?.remove(view)
            view.release()
        }
        overlay = null
        host = null
        source = null
        target = null
    }

    private fun stopAnimator() {
        val previous = animator
        animator = null
        previous?.removeAllListeners()
        previous?.cancel()
    }

    private class PortalView(
        row: View, private val bitmap: Bitmap, private val frame: RectF,
        private val targetX: Float, private val targetY: Float,
    ) : View(row.context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val vertices = FloatArray((COLUMNS + 1) * (ROWS + 1) * 2)
        private val radius = 26 * resources.displayMetrics.density
        var progress = 0f
            set(value) { field = value; invalidate() }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (bitmap.isRecycled) return
            val p = progress.coerceIn(0f, 1f)
            val pulse = sin(PI * p).toFloat()
            paint.shader = RadialGradient(targetX, targetY, radius * (1 + pulse * .45f),
                intArrayOf(Color.argb((180 * pulse).toInt(), 5, 9, 20),
                    Color.argb((170 * pulse).toInt(), 55, 145, 255), Color.TRANSPARENT),
                floatArrayOf(0f, .58f, 1f), Shader.TileMode.CLAMP)
            paint.alpha = 255
            canvas.drawCircle(targetX, targetY, radius * 1.5f, paint)
            paint.shader = null
            val travel = (1f - p).pow(1.8f)
            val centerX = targetX + (frame.centerX() - targetX) * travel
            val centerY = targetY + (frame.centerY() - targetY) * travel
            var cursor = 0
            for (y in 0..ROWS) for (x in 0..COLUMNS) {
                val u = x.toFloat() / COLUMNS
                val v = y.toFloat() / ROWS
                val dx = frame.width() * (u - .5f)
                val dy = frame.height() * (v - .5f)
                // Different edges accelerate toward the same point, forming a twisting funnel.
                val contraction = (1f - p).pow(1.45f + u * .45f)
                val angle = p * p * 2.4f + (u - .5f) * pulse * .20f
                val c = cos(angle)
                val s = sin(angle)
                vertices[cursor++] = centerX + (dx * c - dy * s) * contraction
                vertices[cursor++] = centerY + (dx * s + dy * c) * contraction
            }
            paint.alpha = (255 * (1f - p.pow(5))).toInt()
            canvas.drawBitmapMesh(bitmap, COLUMNS, ROWS, vertices, 0, null, 0, paint)
        }

        fun release() { bitmap.recycle() }
        companion object { const val COLUMNS = 16; const val ROWS = 4 }
    }
}
