package de.rhythm.tiles

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.random.Random

/**
 * Fläche zum Eintippen: Jede Berührung (egal wo) wird mit ihrer Songzeit gespeichert.
 * Hält man den Finger länger als [HOLD_MIN] Sekunden, entsteht eine Halte-Kachel.
 */
class RecordView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    companion object {
        const val HOLD_MIN = 0.4
        private const val HOLD_MAX = 4.0
    }

    /** Aktuelle Songzeit, oder null wenn gerade nicht aufgenommen wird (Countdown, Pause). */
    var clock: () -> Double? = { null }
    var songDuration = 0.0
    var countdownText: String? = null
    var hint = ""

    val taps = ArrayList<Tap>()
    private val downAt = HashMap<Int, Double>()

    private class Ripple(val x: Float, val y: Float, val born: Long, val color: Int)
    private val ripples = ArrayList<Ripple>()
    private val colors = intArrayOf(
        Color.rgb(0, 229, 255), Color.rgb(255, 64, 200), Color.rgb(255, 214, 0), Color.rgb(100, 255, 140),
    )
    private val rnd = Random(7)
    private var flashAt = 0L

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.rgb(36, 10, 70), Color.rgb(12, 6, 30), Color.rgb(4, 2, 10)),
            null, Shader.TileMode.CLAMP,
        )
    }

    /** Songzeit, zu der das Ereignis tatsächlich passiert ist (Verzögerung der Verarbeitung herausgerechnet). */
    private fun eventTime(event: MotionEvent): Double? {
        val t = clock() ?: return null
        val delay = (SystemClock.uptimeMillis() - event.eventTime) / 1000.0
        return (t - delay.coerceIn(0.0, 0.1)).coerceAtLeast(0.0)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val t = eventTime(event) ?: return true
                val idx = event.actionIndex
                downAt[event.getPointerId(idx)] = t
                ripples.add(Ripple(event.getX(idx), event.getY(idx), SystemClock.uptimeMillis(), colors[rnd.nextInt(colors.size)]))
                flashAt = SystemClock.uptimeMillis()
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                val start = downAt.remove(id) ?: return true
                val end = eventTime(event) ?: start
                addTap(start, end)
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun addTap(start: Double, end: Double) {
        val held = end - start
        val duration = if (held >= HOLD_MIN) held.coerceAtMost(HOLD_MAX) else 0.0
        // Liste nach Zeit sortiert halten (bei mehreren Fingern kann ein späterer Tap früher enden)
        var idx = taps.size
        while (idx > 0 && taps[idx - 1].time > start) idx--
        taps.add(idx, Tap(start, duration))
    }

    /** Alle noch gedrückten Finger als losgelassen werten (z. B. bei Pause oder Ende). */
    fun releaseAll() {
        val now = clock()
        for ((_, start) in downAt) addTap(start, now ?: start)
        downAt.clear()
    }

    fun undo(): Boolean {
        if (taps.isEmpty()) return false
        taps.removeAt(taps.size - 1)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // kurzes Aufleuchten bei jedem Tap
        val flash = (now - flashAt) / 150f
        if (flash < 1f) canvas.drawColor(Color.argb(((1 - flash) * 40).toInt(), 255, 255, 255))

        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val age = (now - r.born) / 600f
            if (age > 1f) { it.remove(); continue }
            ringPaint.color = Color.argb(((1 - age) * 255).toInt(), Color.red(r.color), Color.green(r.color), Color.blue(r.color))
            ringPaint.strokeWidth = 6 * density * (1 - age) + density
            canvas.drawCircle(r.x, r.y, (20 + age * 90) * density, ringPaint)
        }

        val t = clock()
        // Fortschritt
        if (songDuration > 0 && t != null) {
            val p = (t / songDuration).coerceIn(0.0, 1.0).toFloat()
            barPaint.color = Color.argb(50, 255, 255, 255)
            canvas.drawRect(0f, 0f, w, 4 * density, barPaint)
            barPaint.color = Color.rgb(255, 64, 200)
            canvas.drawRect(0f, 0f, w * p, 4 * density, barPaint)
            textPaint.textSize = 16 * density
            textPaint.color = Color.argb(180, 255, 255, 255)
            canvas.drawText("${format(t)} / ${format(songDuration)}", w / 2, 32 * density, textPaint)
        }

        textPaint.color = Color.WHITE
        textPaint.textSize = 64 * density
        canvas.drawText(taps.size.toString(), w / 2, h * 0.36f, textPaint)
        textPaint.textSize = 16 * density
        textPaint.color = Color.argb(180, 255, 255, 255)
        canvas.drawText(context.getString(R.string.record_count), w / 2, h * 0.36f + 26 * density, textPaint)

        // gehaltener Finger: Anzeige, ob es schon eine lange Kachel wird
        if (t != null && downAt.isNotEmpty()) {
            val held = t - downAt.values.min()
            if (held >= HOLD_MIN) {
                textPaint.textSize = 22 * density
                textPaint.color = Color.rgb(100, 255, 140)
                canvas.drawText(context.getString(R.string.record_holding, held), w / 2, h * 0.5f, textPaint)
            }
        }

        countdownText?.let {
            textPaint.color = Color.WHITE
            textPaint.textSize = 72 * density
            canvas.drawText(it, w / 2, h * 0.58f, textPaint)
        }

        textPaint.textSize = 15 * density
        textPaint.color = Color.argb(160, 255, 255, 255)
        var y = h * 0.68f
        for (line in hint.split('\n')) {
            canvas.drawText(line, w / 2, y, textPaint)
            y += 22 * density
        }

        postInvalidateOnAnimation()
    }

    private fun format(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
