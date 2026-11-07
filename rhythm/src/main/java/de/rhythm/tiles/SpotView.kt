package de.rhythm.tiles

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * Spielfläche für selbst eingetippte Songs: Jede Kachel erscheint an einer zufälligen
 * Stelle des Bildschirms. Ein Rahmen schrumpft auf die Kachel zu – wenn er sie erreicht,
 * muss getippt werden. Halte-Kacheln müssen bis zum Ende gedrückt bleiben.
 */
@SuppressLint("ViewConstructor")
class SpotView(
    context: Context,
    private val notes: List<Note>,
    difficulty: Difficulty,
    private val songDuration: Double,
    private val clock: () -> Double,
) : View(context), PlayField {

    override val stats = GameView.Stats()
    override var countdownText: String? = null
    override var paused = false

    /** So lange vor dem Tipp-Zeitpunkt wird eine Kachel sichtbar. */
    private val approach = difficulty.travelTime * 0.7
    private val window = 0.17

    private val colors = intArrayOf(
        Color.rgb(0, 229, 255), Color.rgb(255, 64, 200), Color.rgb(255, 214, 0), Color.rgb(100, 255, 140),
    )

    private val density = resources.displayMetrics.density
    private val size = 84 * density
    private val xs = FloatArray(notes.size)
    private val ys = FloatArray(notes.size)
    private var placed = false

    private var firstActive = 0
    private var lastJudgement: GameView.Judgement? = null
    private var lastJudgementAt = -10.0
    private var lastJudgementX = 0f
    private var lastJudgementY = 0f
    private var bonusAt = -10.0

    private class Particle(val x: Float, val y: Float, val vx: Float, val vy: Float, val color: Int, val born: Double)
    private val particles = ArrayList<Particle>()
    private val rnd = Random(System.nanoTime())

    private val bgPaint = Paint()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.rgb(36, 10, 70), Color.rgb(12, 6, 30), Color.rgb(4, 2, 10)),
            null, Shader.TileMode.CLAMP,
        )
        if (!placed && w > 0 && h > 0) {
            placeNotes(w.toFloat(), h.toFloat())
            placed = true
        }
    }

    // ---------------------------------------------------------------- zufällige Stellen

    /**
     * Verteilt die Kacheln zufällig. Gleichzeitig sichtbare Kacheln dürfen sich nicht
     * überdecken, und schnell aufeinanderfolgende liegen nicht zu weit auseinander.
     */
    private fun placeNotes(w: Float, h: Float) {
        val margin = size * 0.75f
        val left = margin
        val right = w - margin
        val top = 110 * density // Platz für Punkte und Combo
        val bottom = h - margin
        val minDist = size * 1.4f

        for (i in notes.indices) {
            val n = notes[i]
            val prev = (i - 1 downTo maxOf(0, i - 12)).firstOrNull { notes[it].time < n.time - 0.01 }
            var bestX = 0f
            var bestY = 0f
            var bestScore = Float.NEGATIVE_INFINITY
            repeat(30) {
                val x = left + rnd.nextFloat() * (right - left)
                val y = top + rnd.nextFloat() * (bottom - top)
                var score = 0f
                // Abstand zu allen Kacheln, die gleichzeitig zu sehen sind
                for (j in maxOf(0, i - 30) until i) {
                    val o = notes[j]
                    if (o.end + 0.35 < n.time - approach) continue
                    val d = hypot(x - xs[j], y - ys[j])
                    if (d < minDist) score -= (minDist - d) * 10
                }
                // Schnelle Folge: nächste Kachel eher in der Nähe der vorigen
                if (prev != null) {
                    val gap = n.time - notes[prev].time
                    val d = hypot(x - xs[prev], y - ys[prev])
                    val ideal = (w * 0.25f + (gap * w * 0.4f).toFloat()).coerceAtMost(w * 0.7f)
                    score -= abs(d - ideal) * 0.3f
                }
                if (score > bestScore) { bestScore = score; bestX = x; bestY = y }
            }
            xs[i] = bestX
            ys[i] = bestY
        }
    }

    // ---------------------------------------------------------------- Spiel-Logik

    private fun register(j: GameView.Judgement, t: Double, x: Float, y: Float) {
        lastJudgement = j
        lastJudgementAt = t
        lastJudgementX = x
        lastJudgementY = y
        when (j) {
            GameView.Judgement.PERFECT -> stats.perfect++
            GameView.Judgement.GREAT -> stats.great++
            GameView.Judgement.GOOD -> stats.good++
            GameView.Judgement.MISS -> stats.miss++
        }
        if (j == GameView.Judgement.MISS) {
            stats.combo = 0
        } else {
            stats.combo++
            stats.maxCombo = maxOf(stats.maxCombo, stats.combo)
            stats.score += j.points * multiplier()
        }
    }

    private fun multiplier(): Int = 1 + min(stats.combo / 25, 3)

    private fun update(t: Double) {
        for (i in firstActive until notes.size) {
            val n = notes[i]
            if (n.time > t + 0.5) break
            if (!n.judged && t > n.time + window) {
                n.judged = true
                n.missed = true
                n.hitAt = t
                register(GameView.Judgement.MISS, t, xs[i], ys[i])
            }
            if (n.holdingPointer >= 0 && t >= n.end) finishHold(i, t, true)
        }
        while (firstActive < notes.size) {
            val n = notes[firstActive]
            if (n.judged && n.holdingPointer < 0 && t > maxOf(n.end, n.hitAt) + 0.6) firstActive++ else break
        }
    }

    private fun finishHold(i: Int, t: Double, success: Boolean) {
        val n = notes[i]
        n.holdingPointer = -1
        n.holdFinished = true
        n.hitAt = t
        if (success) {
            stats.score += 150L * multiplier()
            bonusAt = t
            burst(xs[i], ys[i], colors[i % colors.size])
        } else {
            stats.combo = 0
            lastJudgement = GameView.Judgement.MISS
            lastJudgementAt = t
            lastJudgementX = xs[i]
            lastJudgementY = ys[i]
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (paused || !placed) return true
        val t = clock()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                tap(event.getX(idx), event.getY(idx), event.getPointerId(idx), t)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(event.getPointerId(event.actionIndex), t)
            MotionEvent.ACTION_CANCEL -> {
                for (i in firstActive until notes.size) {
                    if (notes[i].holdingPointer >= 0) finishHold(i, t, t >= notes[i].end - 0.15)
                }
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float, pointerId: Int, t: Double) {
        // Die früheste offene Kachel unter dem Finger, die gerade dran ist
        val reach = size * 0.75f
        var best = -1
        for (i in firstActive until notes.size) {
            val n = notes[i]
            if (n.time > t + window) break
            if (n.judged) continue
            if (hypot(x - xs[i], y - ys[i]) > reach) continue
            if (best < 0 || abs(n.time - t) < abs(notes[best].time - t)) best = i
        }
        if (best < 0) return
        val n = notes[best]
        val diff = abs(n.time - t)
        val j = when {
            diff <= 0.06 -> GameView.Judgement.PERFECT
            diff <= 0.11 -> GameView.Judgement.GREAT
            else -> GameView.Judgement.GOOD
        }
        n.judged = true
        n.hitAt = t
        register(j, t, xs[best], ys[best])
        if (j == GameView.Judgement.PERFECT) burst(xs[best], ys[best], colors[best % colors.size])
        if (n.isHold) n.holdingPointer = pointerId
    }

    private fun release(pointerId: Int, t: Double) {
        for (i in firstActive until notes.size) {
            val n = notes[i]
            if (n.time > t + 0.5) break
            if (n.holdingPointer == pointerId) finishHold(i, t, t >= n.end - 0.15)
        }
    }

    private fun burst(x: Float, y: Float, color: Int) {
        val now = clock()
        repeat(16) {
            particles.add(
                Particle(
                    x, y,
                    (rnd.nextFloat() - 0.5f) * 700f * density / 3,
                    (rnd.nextFloat() - 0.5f) * 700f * density / 3,
                    color, now,
                )
            )
        }
    }

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        val t = clock()
        if (!paused) update(t)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (placed) {
            // Spätere Kacheln zuerst zeichnen, damit die nächste oben liegt
            var last = firstActive
            while (last < notes.size && notes[last].time - approach <= t) last++
            for (i in last - 1 downTo firstActive) drawNote(canvas, i, t)
        }
        drawParticles(canvas, t)
        drawHud(canvas, t)
        if (!paused) postInvalidateOnAnimation()
    }

    private fun drawNote(canvas: Canvas, i: Int, t: Double) {
        val n = notes[i]
        val x = xs[i]
        val y = ys[i]
        val color = colors[i % colors.size]
        val half = size / 2
        val corner = 16 * density
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        when {
            // verpasst: rot ausblenden
            n.missed -> {
                val age = t - n.hitAt
                if (age > 0.4) return
                val a = ((1 - age / 0.4) * 200).toInt()
                fillPaint.color = Color.argb(a, 160, 20, 50)
                rect.set(x - half, y - half, x + half, y + half)
                canvas.drawRoundRect(rect, corner, corner, fillPaint)
                strokePaint.color = Color.argb(a, 255, 80, 100)
                strokePaint.strokeWidth = 4 * density
                canvas.drawLine(x - half * 0.4f, y - half * 0.4f, x + half * 0.4f, y + half * 0.4f, strokePaint)
                canvas.drawLine(x + half * 0.4f, y - half * 0.4f, x - half * 0.4f, y + half * 0.4f, strokePaint)
            }
            // Halte-Kachel wird gerade gehalten: Fortschritt als füllender Balken
            n.holdingPointer >= 0 -> {
                val p = ((t - n.time) / n.duration).coerceIn(0.0, 1.0).toFloat()
                fillPaint.color = Color.argb(90, r, g, b)
                rect.set(x - half, y - half, x + half, y + half)
                canvas.drawRoundRect(rect, corner, corner, fillPaint)
                fillPaint.color = Color.argb(230, r, g, b)
                rect.set(x - half, y + half - size * p, x + half, y + half)
                canvas.drawRoundRect(rect, corner, corner, fillPaint)
                strokePaint.color = Color.WHITE
                strokePaint.strokeWidth = 3 * density
                rect.set(x - half, y - half, x + half, y + half)
                canvas.drawRoundRect(rect, corner, corner, strokePaint)
            }
            // getroffen (oder Halten beendet): kurz aufleuchten und wachsen
            n.judged -> {
                val age = t - n.hitAt
                if (age > 0.25 || age < 0) return
                val a = ((1 - age / 0.25) * 220).toInt()
                val grow = (age * 120 * density).toFloat()
                fillPaint.color = Color.argb(a, r, g, b)
                rect.set(x - half - grow, y - half - grow, x + half + grow, y + half + grow)
                canvas.drawRoundRect(rect, corner, corner, fillPaint)
            }
            // offen: Kachel plus schrumpfender Rahmen, der den Zeitpunkt anzeigt
            else -> {
                val left = n.time - t
                val appear = ((approach - left) / 0.2).coerceIn(0.0, 1.0).toFloat()
                val a = (appear * 255).toInt()
                fillPaint.color = Color.argb(a, 14, 10, 28)
                rect.set(x - half, y - half, x + half, y + half)
                canvas.drawRoundRect(rect, corner, corner, fillPaint)
                fillPaint.color = Color.argb((appear * 70).toInt(), r, g, b)
                canvas.drawRoundRect(rect, corner, corner, fillPaint)
                strokePaint.color = Color.argb(a, r, g, b)
                strokePaint.strokeWidth = (if (n.isHold) 6 else 3) * density
                canvas.drawRoundRect(rect, corner, corner, strokePaint)
                if (n.isHold) {
                    textPaint.textSize = 13 * density
                    textPaint.color = Color.argb(a, 255, 255, 255)
                    canvas.drawText(context.getString(R.string.spot_hold), x, y + 5 * density, textPaint)
                }

                val k = (left / approach).coerceIn(0.0, 1.0).toFloat()
                if (k > 0f) {
                    val ring = half * (1 + 1.6f * k)
                    strokePaint.color = Color.argb((appear * 230).toInt(), r, g, b)
                    strokePaint.strokeWidth = 3 * density
                    rect.set(x - ring, y - ring, x + ring, y + ring)
                    canvas.drawRoundRect(rect, corner * (1 + k), corner * (1 + k), strokePaint)
                }
            }
        }
    }

    private fun drawParticles(canvas: Canvas, t: Double) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val age = (t - p.born).toFloat()
            if (age > 0.5f || age < 0f) { it.remove(); continue }
            fillPaint.color = Color.argb(((1 - age / 0.5f) * 255).toInt(), Color.red(p.color), Color.green(p.color), Color.blue(p.color))
            canvas.drawCircle(p.x + p.vx * age, p.y + p.vy * age, 3.5f * density, fillPaint)
        }
    }

    private fun drawHud(canvas: Canvas, t: Double) {
        val w = width.toFloat()
        if (songDuration > 0) {
            val p = (t / songDuration).coerceIn(0.0, 1.0).toFloat()
            fillPaint.color = Color.argb(50, 255, 255, 255)
            canvas.drawRect(0f, 0f, w, 4 * density, fillPaint)
            fillPaint.color = Color.rgb(255, 64, 200)
            canvas.drawRect(0f, 0f, w * p, 4 * density, fillPaint)
        }

        textPaint.color = Color.WHITE
        textPaint.textSize = 30 * density
        canvas.drawText(stats.score.toString(), w / 2, 48 * density, textPaint)
        if (stats.combo >= 5) {
            textPaint.textSize = 20 * density
            textPaint.color = Color.argb(220, 255, 255, 255)
            canvas.drawText("${stats.combo} Combo  ×${multiplier()}", w / 2, 78 * density, textPaint)
        }

        // Bewertung direkt an der getroffenen Kachel
        val j = lastJudgement
        val age = t - lastJudgementAt
        if (j != null && age in 0.0..0.5) {
            textPaint.textSize = 22 * density
            textPaint.color = Color.argb(((1 - age / 0.5) * 255).toInt(), Color.red(j.color), Color.green(j.color), Color.blue(j.color))
            canvas.drawText(j.label, lastJudgementX, lastJudgementY - size * 0.7f - (age * 30 * density).toFloat(), textPaint)
        }
        val bAge = t - bonusAt
        if (bAge in 0.0..0.6) {
            textPaint.textSize = 20 * density
            textPaint.color = Color.argb(((1 - bAge / 0.6) * 255).toInt(), 255, 255, 255)
            canvas.drawText("GEHALTEN +${150 * multiplier()}", w / 2, 104 * density, textPaint)
        }

        countdownText?.let {
            textPaint.color = Color.WHITE
            textPaint.textSize = 72 * density
            canvas.drawText(it, w / 2, height * 0.5f, textPaint)
        }
    }
}
