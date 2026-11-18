package app.hushtype.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import app.hushtype.data.Settings
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Puts the bubble on screen over other apps, with a caption beside it for the live transcript
 * and messages, and a target at the bottom to drop it on to hide it for an hour.
 */
class BubbleController(
    private val context: Context,
    private val dictation: DictationController,
    private val settings: Settings,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val scope = MainScope()
    private val diameter = (52 * density * settings.bubbleSize).roundToInt()
    private val margin = (6 * density).roundToInt()

    private val bubble = BubbleView(context, diameter)
    private val bubbleParams = overlayParams(touchable = true).apply {
        width = bubble.totalPx
        height = bubble.totalPx
    }
    private val caption = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxWidth = (250 * density).roundToInt()
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.START
        val h = (14 * density).roundToInt()
        val v = (9 * density).roundToInt()
        setPadding(h, v, h, v)
        background = GradientDrawable().apply {
            cornerRadius = 18 * density
            setColor(0xF01A1726.toInt())
            setStroke((1 * density).roundToInt().coerceAtLeast(1), 0x26FFFFFF)
        }
        elevation = 6 * density
        alpha = 0f
    }
    private val captionParams = overlayParams(touchable = false).also {
        // TextView.setText relayouts through its layout params, and measuring the caption before it is
        // on screen leaves it without any. Give it the window's params up front.
        caption.layoutParams = it
    }
    private val dismiss = DismissTarget(context, (64 * density).roundToInt())
    private val dismissParams = overlayParams(touchable = false).apply {
        width = dismiss.sizePx
        height = dismiss.sizePx
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = (48 * density).roundToInt()
    }

    private var attached = false
    private var wanted = false
    private var keyboardTop: Int? = null
    private var dragging = false
    private var snap: ValueAnimator? = null

    init {
        bubble.listener = object : BubbleView.Listener {
            override fun onTap() {
                val phase = dictation.phase.value
                if (phase is Phase.Notice && phase.action == NoticeAction.OPEN_APP) dictation.openApp() else dictation.toggle()
            }
            override fun onHoldStart() = dictation.start()
            override fun onHoldEnd() = dictation.stop()
            override fun onDragStart() {
                dragging = true
                snap?.cancel()
                showDismiss(true)
            }
            override fun onDrag(dx: Float, dy: Float) {
                bubbleParams.x += dx.roundToInt()
                bubbleParams.y += dy.roundToInt()
                applyBubble()
                dismiss.setHot(overDismiss())
                placeCaption()
            }
            override fun onDragEnd() {
                dragging = false
                val drop = overDismiss()
                showDismiss(false)
                if (drop) {
                    bubble.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    dictation.cancel()
                    settings.bubbleSnoozedUntil = System.currentTimeMillis() + SNOOZE_MS
                    setAttached(false)
                } else {
                    val screen = screen()
                    settings.bubbleRight = bubbleParams.x + bubble.totalPx / 2 > screen.first / 2
                    settings.bubbleY = (bubbleParams.y + bubble.totalPx / 2f) / screen.second
                    snapToEdge()
                }
            }
        }

        scope.launch {
            combine(dictation.phase, dictation.liveText, dictation.hearing) { phase, text, hearing -> Triple(phase, text, hearing) }.collect { (phase, text, hearing) ->
                // A drawing problem must never take down the accessibility service, or Android
                // marks it crashed and the bubble is gone until the user turns it on again.
                try {
                    render(phase, text, hearing)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Couldn't update the bubble", e)
                }
            }
        }
        scope.launch { dictation.level.collect { bubble.setLevel(it) } }
    }

    /** Called whenever focus or the keyboard changes. */
    fun update(visible: Boolean, keyboardTop: Int?) {
        this.keyboardTop = keyboardTop
        val snoozed = System.currentTimeMillis() < settings.bubbleSnoozedUntil
        wanted = visible && !snoozed
        setAttached(wanted || dictation.isBusy)
        if (attached && !dragging) position(animate = true)
    }

    fun destroy() {
        scope.cancel()
        setAttached(false)
        showDismiss(false)
    }

    private var buzzed = false

    private fun render(phase: Phase, text: String, hearing: Boolean) {
        bubble.setLook(
            when (phase) {
                Phase.Idle -> BubbleView.Look.IDLE
                Phase.Listening -> BubbleView.Look.LISTENING
                is Phase.Working -> BubbleView.Look.WORKING
                Phase.Done -> BubbleView.Look.DONE
                is Phase.Notice -> if (phase.isError) BubbleView.Look.ATTENTION else BubbleView.Look.IDLE
            },
        )
        // Buzz once the recognizer is really hearing, so people know when to start talking.
        if (phase != Phase.Listening) buzzed = false
        else if (hearing && !buzzed) {
            buzzed = true
            if (settings.haptics) bubble.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        val words = when (phase) {
            Phase.Listening -> text.ifBlank { if (hearing) "Listening…" else "One moment…" }
            is Phase.Working -> if (phase.polishing) "Polishing…" else text.ifBlank { "Transcribing…" }
            is Phase.Notice -> phase.message
            else -> null
        }
        if (phase != Phase.Idle && !attached) setAttached(true)
        if (phase == Phase.Idle && !wanted) setAttached(false)
        setCaption(words)
    }

    private fun setCaption(text: String?) {
        if (text == null) {
            caption.animate().alpha(0f).setDuration(160).withEndAction {
                if (caption.alpha == 0f && caption.isAttachedToWindow) runCatching { wm.removeView(caption) }
            }.start()
            return
        }
        caption.text = text
        if (!caption.isAttachedToWindow && attached) {
            placeCaption()
            runCatching { wm.addView(caption, captionParams) }
        }
        placeCaption()
        caption.animate().alpha(1f).setDuration(160).start()
    }

    private fun setAttached(on: Boolean) {
        if (on == attached) return
        attached = on
        if (on) {
            position(animate = false)
            runCatching { wm.addView(bubble, bubbleParams) }
            bubble.scaleX = 0.4f; bubble.scaleY = 0.4f; bubble.alpha = 0f
            bubble.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.6f)).start()
        } else {
            snap?.cancel()
            runCatching { if (caption.isAttachedToWindow) wm.removeView(caption) }
            bubble.animate().scaleX(0.4f).scaleY(0.4f).alpha(0f).setDuration(160).withEndAction {
                if (!attached) runCatching { wm.removeView(bubble) }
            }.start()
        }
    }

    /** Puts the bubble on its edge at the saved height, lifted above the keyboard when it's up. */
    private fun position(animate: Boolean) {
        val (w, h) = screen()
        val targetX = if (settings.bubbleRight) w - bubble.totalPx + bubble.padPx - margin else -bubble.padPx + margin
        var targetY = (settings.bubbleY * h - bubble.totalPx / 2f).roundToInt()
        keyboardTop?.let { top -> targetY = minOf(targetY, top - bubble.totalPx + bubble.padPx - margin) }
        targetY = targetY.coerceIn(statusBar() - bubble.padPx, h - bubble.totalPx)
        if (animate && attached && (bubbleParams.x != targetX || bubbleParams.y != targetY)) {
            animateTo(targetX, targetY)
        } else {
            bubbleParams.x = targetX
            bubbleParams.y = targetY
            applyBubble()
        }
        placeCaption()
    }

    private fun snapToEdge() = position(animate = true)

    private fun animateTo(x: Int, y: Int) {
        snap?.cancel()
        val fromX = bubbleParams.x
        val fromY = bubbleParams.y
        snap = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                val f = it.animatedValue as Float
                bubbleParams.x = (fromX + (x - fromX) * f).roundToInt()
                bubbleParams.y = (fromY + (y - fromY) * f).roundToInt()
                applyBubble()
                placeCaption()
            }
            start()
        }
    }

    private fun applyBubble() {
        if (bubble.isAttachedToWindow) runCatching { wm.updateViewLayout(bubble, bubbleParams) }
    }

    /** Keeps the caption beside the bubble, on the side facing the middle of the screen. */
    private fun placeCaption() {
        val (w, _) = screen()
        val onRight = bubbleParams.x + bubble.totalPx / 2 > w / 2
        caption.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val gap = (2 * density).roundToInt()
        captionParams.gravity = Gravity.TOP or if (onRight) Gravity.END else Gravity.START
        captionParams.x = if (onRight) w - bubbleParams.x - bubble.padPx + gap else bubbleParams.x + bubble.totalPx - bubble.padPx + gap
        captionParams.y = bubbleParams.y + bubble.totalPx / 2 - caption.measuredHeight / 2
        if (caption.isAttachedToWindow) runCatching { wm.updateViewLayout(caption, captionParams) }
    }

    private fun showDismiss(on: Boolean) {
        if (on && !dismiss.isAttachedToWindow) {
            runCatching { wm.addView(dismiss, dismissParams) }
            dismiss.alpha = 0f
            dismiss.translationY = 40 * density
            dismiss.animate().alpha(1f).translationY(0f).setDuration(200).start()
        } else if (!on && dismiss.isAttachedToWindow) {
            dismiss.animate().alpha(0f).translationY(40 * density).setDuration(160).withEndAction {
                runCatching { wm.removeView(dismiss) }
            }.start()
        }
    }

    private fun overDismiss(): Boolean {
        val (w, h) = screen()
        val cx = bubbleParams.x + bubble.totalPx / 2f
        val cy = bubbleParams.y + bubble.totalPx / 2f
        val tx = w / 2f
        val ty = h - dismissParams.y - dismiss.sizePx / 2f
        return hypot(cx - tx, cy - ty) < dismiss.sizePx * 1.1f
    }

    private fun screen(): Pair<Int, Int> {
        val bounds = wm.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    private fun statusBar(): Int = (28 * density).roundToInt()

    private fun overlayParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    companion object {
        const val SNOOZE_MS = 60 * 60 * 1000L
        private const val TAG = "HushtypeBubble"
    }
}

/** The circle at the bottom of the screen that hides the bubble when you drop it there. */
private class DismissTarget(context: Context, val sizePx: Int) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = sizePx * 0.05f
    }
    private var hot = false

    fun setHot(value: Boolean) {
        if (value == hot) return
        hot = value
        if (value) performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        animate().scaleX(if (value) 1.18f else 1f).scaleY(if (value) 1.18f else 1f).setDuration(140).start()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(sizePx, sizePx)

    override fun onDraw(canvas: Canvas) {
        val c = sizePx / 2f
        fill.color = if (hot) 0xF0E5484D.toInt() else 0xD91A1726.toInt()
        canvas.drawCircle(c, c, c * 0.92f, fill)
        val a = sizePx * 0.17f
        canvas.drawLine(c - a, c - a, c + a, c + a, cross)
        canvas.drawLine(c + a, c - a, c - a, c + a, cross)
    }
}
