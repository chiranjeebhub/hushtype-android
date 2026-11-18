package app.hushtype.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The floating dictation bubble: a see-through indigo orb with the Hushtype mark that turns into a live
 * waveform while you speak, a mint ring while the text is being polished, and a check when
 * it's typed. Drawn by hand so it animates smoothly at any size.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context, private val diameterPx: Int) : View(context) {
    interface Listener {
        fun onTap()
        fun onHoldStart()
        fun onHoldEnd()
        fun onDragStart()
        fun onDrag(dx: Float, dy: Float)
        fun onDragEnd()
    }

    var listener: Listener? = null

    enum class Look { IDLE, LISTENING, WORKING, DONE, ATTENTION }

    private var look = Look.IDLE
    private var lookSince = 0L
    private var level = 0f
    private var smoothLevel = 0f
    private var lastTouch = SystemClock.uptimeMillis()
    /** Drawn a little see-through while resting. */
    private var fade = 1f

    private val density = resources.displayMetrics.density
    /** Room around the orb for its rings and shadow. */
    val padPx = (diameterPx * 0.32f).toInt()
    val totalPx = diameterPx + padPx * 2

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = MINT }
    private val path = Path()
    private val rect = RectF()

    init {
        val c = totalPx / 2f
        val r = diameterPx / 2f
        body.shader = LinearGradient(c - r, c - r, c + r, c + r, VIOLET_LIGHT, INDIGO, Shader.TileMode.CLAMP)
        shadow.shader = RadialGradient(c, c + r * 0.12f, r * 1.32f, intArrayOf(0x55000000, 0x22000000, 0x00000000), floatArrayOf(0.62f, 0.8f, 1f), Shader.TileMode.CLAMP)
        rim.strokeWidth = max(1f, density * 0.8f)
        rim.shader = LinearGradient(c, c - r, c, c + r, 0x66FFFFFF, 0x10FFFFFF, Shader.TileMode.CLAMP)
        arc.strokeWidth = diameterPx * 0.06f
        isHapticFeedbackEnabled = true
        contentDescription = "Hushtype dictation"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(totalPx, totalPx)

    fun setLook(next: Look) {
        if (next == look) return
        look = next
        lookSince = SystemClock.uptimeMillis()
        touched()
        invalidate()
    }

    fun setLevel(value: Float) {
        level = value
        if (look == Look.LISTENING) postInvalidateOnAnimation()
    }

    private fun touched() {
        lastTouch = SystemClock.uptimeMillis()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - lookSince) / 1000f
        val c = totalPx / 2f
        val r = diameterPx / 2f
        smoothLevel += (level - smoothLevel) * 0.25f

        // Rest a little more quietly on screen when nothing is happening.
        val idleFor = now - lastTouch
        // See-through while waiting, like a glass button; fully solid while it's working for you.
        val restAlpha = when {
            look != Look.IDLE -> 1f
            pressed || idleFor <= 2500 -> 0.82f
            else -> 0.6f
        }
        fade += (restAlpha - fade) * 0.12f
        val pressScale = if (pressed) 0.92f else 1f
        val appear = if (look == Look.DONE) 1f + 0.08f * sin(min(t * 5f, PI.toFloat())) else 1f
        val scale = pressScale * appear

        canvas.saveLayerAlpha(0f, 0f, totalPx.toFloat(), totalPx.toFloat(), (fade * 255).toInt())
        canvas.scale(scale, scale, c, c)

        // Listening: soft mint rings breathe outward with your voice.
        if (look == Look.LISTENING) {
            for (i in 0 until 2) {
                val phase = ((t * 0.9f + i * 0.5f) % 1f)
                val grow = 1f + phase * (0.22f + smoothLevel * 0.28f)
                ring.color = MINT
                ring.alpha = ((1f - phase) * (70 + smoothLevel * 120)).toInt().coerceIn(0, 255)
                ring.strokeWidth = diameterPx * 0.035f
                canvas.drawCircle(c, c, r * grow, ring)
            }
        }

        canvas.drawCircle(c, c, r * 1.3f, shadow)
        body.alpha = 255
        canvas.drawCircle(c, c, r, body)
        if (look == Look.LISTENING) {
            // A brighter core while hearing you.
            glyph.style = Paint.Style.FILL
            glyph.color = VIOLET_GLOW
            glyph.alpha = (40 + smoothLevel * 90).toInt()
            canvas.drawCircle(c, c, r * (0.72f + smoothLevel * 0.2f), glyph)
            glyph.color = Color.WHITE
            glyph.alpha = 255
        }
        canvas.drawCircle(c, c, r - rim.strokeWidth / 2, rim)

        when (look) {
            Look.IDLE -> drawLogo(canvas, c, r)
            Look.ATTENTION -> {
                drawLogo(canvas, c, r)
                ring.color = AMBER
                ring.alpha = 230
                ring.strokeWidth = diameterPx * 0.05f
                canvas.drawCircle(c, c, r * 1.06f, ring)
            }
            Look.LISTENING -> drawWave(canvas, c, r, t)
            Look.WORKING -> {
                drawDots(canvas, c, r, t)
                rect.set(c - r * 1.1f, c - r * 1.1f, c + r * 1.1f, c + r * 1.1f)
                arc.alpha = 255
                canvas.drawArc(rect, (t * 360f * 1.1f) % 360f, 100f, false, arc)
            }
            Look.DONE -> drawCheck(canvas, c, r, min(1f, t * 4f))
        }
        canvas.restore()

        val animating = look == Look.LISTENING || look == Look.WORKING || (look == Look.DONE && t < 1f) ||
            abs(fade - restAlpha) > 0.01f || abs(level - smoothLevel) > 0.01f
        if (animating) postInvalidateOnAnimation()
        else if (look == Look.IDLE && idleFor <= 2500) postInvalidateDelayed(2600 - idleFor)
    }

    /** The Hushtype mark (rising bars and a mint text caret), from res/drawable/hushtype_logo.xml. */
    private fun drawLogo(canvas: Canvas, c: Float, r: Float) {
        // The mark spans x 96..412 and y 116..396 in the logo's 512 grid.
        val k = r * 1.12f / 316f
        canvas.save()
        canvas.translate(c - 254f * k, c - 256f * k)
        canvas.scale(k, k)
        glyph.style = Paint.Style.FILL
        glyph.color = Color.WHITE
        for ((i, bar) in LOGO_BARS.withIndex()) {
            glyph.alpha = (LOGO_ALPHAS[i] * 255).toInt()
            rect.set(bar[0] - 17f, bar[1], bar[0] + 17f, bar[2])
            canvas.drawRoundRect(rect, 17f, 17f, glyph)
        }
        glyph.color = MINT
        rect.set(352f, 116f, 382f, 396f)
        canvas.drawRoundRect(rect, 15f, 15f, glyph)
        rect.set(322f, 116f, 412f, 142f)
        canvas.drawRoundRect(rect, 13f, 13f, glyph)
        rect.set(322f, 370f, 412f, 396f)
        canvas.drawRoundRect(rect, 13f, 13f, glyph)
        glyph.color = Color.WHITE
        glyph.alpha = 255
        canvas.restore()
    }

    private fun drawWave(canvas: Canvas, c: Float, r: Float, t: Float) {
        glyph.color = Color.WHITE
        glyph.style = Paint.Style.FILL
        val bars = 5
        val barW = r * 0.13f
        val gap = r * 0.09f
        val total = bars * barW + (bars - 1) * gap
        val voice = 0.25f + smoothLevel * 0.75f
        for (i in 0 until bars) {
            val shape = floatArrayOf(0.45f, 0.75f, 1f, 0.75f, 0.45f)[i]
            val wobble = 0.55f + 0.45f * sin(t * 9f + i * 1.3f)
            val h = r * (0.16f + 0.78f * shape * voice * wobble)
            val x = c - total / 2 + i * (barW + gap)
            rect.set(x, c - h / 2, x + barW, c + h / 2)
            canvas.drawRoundRect(rect, barW / 2, barW / 2, glyph)
        }
    }

    private fun drawDots(canvas: Canvas, c: Float, r: Float, t: Float) {
        glyph.color = Color.WHITE
        glyph.style = Paint.Style.FILL
        val dot = r * 0.085f
        for (i in -1..1) {
            val lift = max(0f, sin(t * 7f - (i + 1) * 0.8f)) * r * 0.14f
            glyph.alpha = (170 + 85 * (lift / (r * 0.14f))).toInt().coerceIn(0, 255)
            canvas.drawCircle(c + i * r * 0.28f, c - lift + r * 0.04f, dot, glyph)
        }
        glyph.alpha = 255
    }

    private fun drawCheck(canvas: Canvas, c: Float, r: Float, progress: Float) {
        glyph.color = MINT
        glyph.style = Paint.Style.STROKE
        glyph.strokeWidth = r * 0.13f
        path.reset()
        val a = floatArrayOf(c - r * 0.36f, c + r * 0.02f)
        val b = floatArrayOf(c - r * 0.1f, c + r * 0.28f)
        val e = floatArrayOf(c + r * 0.4f, c - r * 0.26f)
        path.moveTo(a[0], a[1])
        if (progress < 0.4f) {
            val p = progress / 0.4f
            path.lineTo(a[0] + (b[0] - a[0]) * p, a[1] + (b[1] - a[1]) * p)
        } else {
            val p = (progress - 0.4f) / 0.6f
            path.lineTo(b[0], b[1])
            path.lineTo(b[0] + (e[0] - b[0]) * p, b[1] + (e[1] - b[1]) * p)
        }
        canvas.drawPath(path, glyph)
        glyph.style = Paint.Style.FILL
    }

    // region Touch: tap to start or stop, hold to talk, drag to move

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var holding = false
    private var pressed = false
    private val holdDelay = 380L

    private val holdRunnable = Runnable {
        if (!dragging && pressed) {
            holding = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onHoldStart()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        touched()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the orb itself is touchable, not the room around it.
                val c = totalPx / 2f
                if (hypot(event.x - c, event.y - c) > diameterPx * 0.62f) return false
                downX = event.rawX; downY = event.rawY
                lastX = downX; lastY = downY
                dragging = false
                holding = false
                pressed = true
                if (look == Look.IDLE || look == Look.ATTENTION) postDelayed(holdRunnable, holdDelay)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !holding && hypot(event.rawX - downX, event.rawY - downY) > slop) {
                    dragging = true
                    removeCallbacks(holdRunnable)
                    listener?.onDragStart()
                }
                if (dragging) listener?.onDrag(event.rawX - lastX, event.rawY - lastY)
                lastX = event.rawX; lastY = event.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                pressed = false
                when {
                    dragging -> listener?.onDragEnd()
                    holding -> listener?.onHoldEnd()
                    event.actionMasked == MotionEvent.ACTION_UP -> {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        listener?.onTap()
                    }
                }
                dragging = false
                holding = false
                invalidate()
            }
        }
        return true
    }

    // endregion

    companion object {
        val VIOLET_LIGHT = Color.rgb(107, 92, 255)
        val INDIGO = Color.rgb(59, 46, 168)
        val VIOLET_GLOW = Color.rgb(160, 140, 255)
        val MINT = Color.rgb(125, 240, 196)
        val AMBER = Color.rgb(255, 196, 92)

        /** Logo bars as x centre, top and bottom in the 512 grid. */
        private val LOGO_BARS = arrayOf(
            floatArrayOf(113f, 226f, 286f),
            floatArrayOf(169f, 176f, 336f),
            floatArrayOf(225f, 126f, 386f),
            floatArrayOf(281f, 196f, 316f),
        )
        private val LOGO_ALPHAS = floatArrayOf(0.55f, 0.7f, 0.85f, 1f)
    }
}
