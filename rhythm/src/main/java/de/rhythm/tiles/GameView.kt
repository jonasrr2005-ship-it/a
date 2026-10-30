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
import kotlin.math.min
import kotlin.random.Random

/**
 * Zeichnet die fallenden Kacheln und wertet die Berührungen aus.
 * Die Spielzeit (in Sekunden Songposition) liefert [clock].
 */
@SuppressLint("ViewConstructor")
class GameView(
    context: Context,
    private val notes: List<Note>,
    private val difficulty: Difficulty,
    private val songDuration: Double,
    private val clock: () -> Double,
) : View(context) {

    enum class Judgement(val label: String, val points: Int, val color: Int) {
        PERFECT("PERFEKT", 300, Color.rgb(120, 255, 240)),
        GREAT("SUPER", 200, Color.rgb(140, 255, 120)),
        GOOD("OK", 100, Color.rgb(255, 220, 90)),
        MISS("VERPASST", 0, Color.rgb(255, 80, 100)),
    }

    class Stats {
        var score = 0L
        var combo = 0
        var maxCombo = 0
        var perfect = 0
        var great = 0
        var good = 0
        var miss = 0
        val accuracy: Double
            get() {
                val total = perfect + great + good + miss
                return if (total == 0) 0.0 else (perfect + great * 0.7 + good * 0.4) / total
            }
    }

    val stats = Stats()

    private val laneColors = intArrayOf(
        Color.rgb(0, 229, 255), Color.rgb(255, 64, 200), Color.rgb(255, 214, 0), Color.rgb(100, 255, 140),
    )

    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bgPaint = Paint()
    private val flashPaint = Paint()
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var hitY = 0f
    private var laneW = 0f
    private var tileH = 0f
    private var pxPerSec = 0f
    private val density = resources.displayMetrics.density

    /** Index der ersten Kachel, die noch sichtbar oder offen ist. */
    private var firstActive = 0
    private val laneFlash = DoubleArray(ChartGenerator.LANES) { -10.0 }
    private var lastJudgement: Judgement? = null
    private var lastJudgementAt = -10.0
    private var bonusAt = -10.0
    private val pointerLane = HashMap<Int, Int>()

    private class Particle(var x: Float, var y: Float, val vx: Float, var vy: Float, val color: Int, val born: Double)
    private val particles = ArrayList<Particle>()
    private val rnd = Random(1)

    var countdownText: String? = null
    var paused = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        hitY = h * 0.80f
        laneW = w / ChartGenerator.LANES.toFloat()
        tileH = h * 0.13f
        pxPerSec = (hitY / difficulty.travelTime).toFloat()
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.rgb(36, 10, 70), Color.rgb(12, 6, 30), Color.rgb(4, 2, 10)),
            null, Shader.TileMode.CLAMP,
        )
    }

    // ---------------------------------------------------------------- Spiel-Logik

    private fun windowFor(diff: Double): Judgement? = when {
        diff <= 0.06 -> Judgement.PERFECT
        diff <= 0.11 -> Judgement.GREAT
        diff <= 0.17 -> Judgement.GOOD
        else -> null
    }

    private fun register(j: Judgement, t: Double) {
        lastJudgement = j
        lastJudgementAt = t
        when (j) {
            Judgement.PERFECT -> stats.perfect++
            Judgement.GREAT -> stats.great++
            Judgement.GOOD -> stats.good++
            Judgement.MISS -> stats.miss++
        }
        if (j == Judgement.MISS) {
            stats.combo = 0
        } else {
            stats.combo++
            stats.maxCombo = maxOf(stats.maxCombo, stats.combo)
            stats.score += j.points * multiplier()
        }
    }

    fun multiplier(): Int = 1 + min(stats.combo / 25, 3)

    private fun update(t: Double) {
        for (i in firstActive until notes.size) {
            val n = notes[i]
            if (n.time > t + 0.5) break
            if (!n.judged && t > n.time + 0.17) {
                n.judged = true
                n.missed = true
                register(Judgement.MISS, t)
            }
            if (n.holdingPointer >= 0 && t >= n.end) {
                finishHold(n, t, true)
            }
        }
        // Erste aktive Kachel nach vorn schieben, wenn sie vollständig vorbei ist
        while (firstActive < notes.size) {
            val n = notes[firstActive]
            val goneAt = n.end + (height - hitY + tileH) / pxPerSec + 0.3
            if (n.judged && n.holdingPointer < 0 && t > goneAt) firstActive++ else break
        }
    }

    private fun finishHold(n: Note, t: Double, success: Boolean) {
        n.holdingPointer = -1
        n.holdFinished = true
        if (success) {
            stats.score += 150L * multiplier()
            bonusAt = t
            burst(n.lane, laneColors[n.lane])
        } else {
            stats.combo = 0
            lastJudgement = Judgement.MISS
            lastJudgementAt = t
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (paused || laneW == 0f) return true
        val t = clock()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val lane = (event.getX(idx) / laneW).toInt().coerceIn(0, ChartGenerator.LANES - 1)
                val id = event.getPointerId(idx)
                pointerLane[id] = lane
                tap(lane, id, t)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                pointerLane.remove(id)
                release(id, t)
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerLane.keys.toList().forEach { release(it, t) }
                pointerLane.clear()
            }
        }
        return true
    }

    private fun tap(lane: Int, pointerId: Int, t: Double) {
        laneFlash[lane] = t
        var best: Note? = null
        for (i in firstActive until notes.size) {
            val n = notes[i]
            if (n.time > t + 0.17) break
            if (n.lane != lane || n.judged) continue
            if (best == null || abs(n.time - t) < abs(best.time - t)) best = n
        }
        val note = best ?: return
        val j = windowFor(abs(note.time - t)) ?: return
        note.judged = true
        note.hitAt = t
        register(j, t)
        if (j == Judgement.PERFECT) burst(lane, laneColors[lane])
        if (note.isHold) note.holdingPointer = pointerId
    }

    private fun release(pointerId: Int, t: Double) {
        for (i in firstActive until notes.size) {
            val n = notes[i]
            if (n.time > t + 0.5) break
            if (n.holdingPointer == pointerId) finishHold(n, t, t >= n.end - 0.15)
        }
    }

    private fun burst(lane: Int, color: Int) {
        val cx = laneW * (lane + 0.5f)
        val now = clock()
        repeat(14) {
            particles.add(
                Particle(
                    cx, hitY,
                    (rnd.nextFloat() - 0.5f) * 900f * density / 3,
                    -(rnd.nextFloat() * 700f + 200f) * density / 3,
                    color, now,
                )
            )
        }
    }

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        val t = clock()
        if (!paused) update(t)

        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Spuren mit Aufleuchten beim Tippen
        for (lane in 0 until ChartGenerator.LANES) {
            val age = t - laneFlash[lane]
            if (age in 0.0..0.25) {
                val a = ((1 - age / 0.25) * 70).toInt()
                flashPaint.shader = LinearGradient(
                    0f, hitY, 0f, hitY - h * 0.5f,
                    Color.argb(a, Color.red(laneColors[lane]), Color.green(laneColors[lane]), Color.blue(laneColors[lane])),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(lane * laneW, 0f, (lane + 1) * laneW, h, flashPaint)
            }
        }
        linePaint.color = Color.argb(40, 255, 255, 255)
        linePaint.strokeWidth = density
        for (lane in 1 until ChartGenerator.LANES) {
            canvas.drawLine(lane * laneW, 0f, lane * laneW, h, linePaint)
        }

        // Trefferlinie
        linePaint.color = Color.argb(60, 255, 255, 255)
        linePaint.strokeWidth = 10 * density
        canvas.drawLine(0f, hitY, w, hitY, linePaint)
        linePaint.color = Color.WHITE
        linePaint.strokeWidth = 2 * density
        canvas.drawLine(0f, hitY, w, hitY, linePaint)

        drawNotes(canvas, t)
        drawParticles(canvas, t)
        drawHud(canvas, t)
        if (!paused) postInvalidateOnAnimation()
    }

    private fun drawNotes(canvas: Canvas, t: Double) {
        val pad = 4 * density
        val corner = 10 * density
        borderPaint.strokeWidth = 3 * density
        for (i in firstActive until notes.size) {
            val n = notes[i]
            val bottom = hitY - ((n.time - t) * pxPerSec).toFloat()
            val bodyH = if (n.isHold) maxOf(tileH, (n.duration * pxPerSec).toFloat()) else tileH
            var top = bottom - bodyH
            if (top > height) continue
            if (bottom < 0f) break
            val color = laneColors[n.lane]
            val left = n.lane * laneW + pad
            val right = (n.lane + 1) * laneW - pad

            when {
                // Getroffene normale Kachel: kurz ausblenden
                n.judged && !n.missed && !n.isHold -> {
                    val age = t - n.hitAt
                    if (age > 0.2) continue
                    val a = ((1 - age / 0.2) * 200).toInt()
                    tilePaint.shader = null
                    tilePaint.color = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
                    val grow = (age * 60 * density).toFloat()
                    rect.set(left - grow, hitY - tileH - grow, right + grow, hitY + grow)
                    canvas.drawRoundRect(rect, corner, corner, tilePaint)
                }
                n.isHold -> {
                    var bottomY = bottom
                    if (n.holdingPointer >= 0) bottomY = hitY // wird „aufgefressen“
                    if (n.holdFinished && !n.missed && t > n.end) continue
                    top = minOf(top, bottomY - 4 * density)
                    if (top >= bottomY) continue
                    val active = n.holdingPointer >= 0
                    val alpha = if (n.missed || (n.holdFinished && t < n.end)) 90 else 255
                    tilePaint.shader = LinearGradient(
                        0f, top, 0f, bottomY,
                        Color.argb(alpha / 3, Color.red(color), Color.green(color), Color.blue(color)),
                        Color.argb(if (active) 255 else alpha * 2 / 3, Color.red(color), Color.green(color), Color.blue(color)),
                        Shader.TileMode.CLAMP,
                    )
                    rect.set(left, top, right, bottomY)
                    canvas.drawRoundRect(rect, corner, corner, tilePaint)
                    tilePaint.shader = null
                    borderPaint.color = if (n.missed) Color.rgb(255, 80, 100) else color
                    canvas.drawRoundRect(rect, corner, corner, borderPaint)
                    if (!n.judged) {
                        // Kopf der Halte-Kachel
                        tilePaint.color = Color.rgb(10, 8, 20)
                        rect.set(left + pad, bottomY - tileH * 0.5f, right - pad, bottomY - pad)
                        canvas.drawRoundRect(rect, corner, corner, tilePaint)
                    }
                }
                else -> {
                    // Normale (noch offene oder verpasste) Kachel
                    tilePaint.shader = null
                    tilePaint.color = if (n.missed) Color.rgb(120, 20, 40) else Color.rgb(10, 8, 20)
                    rect.set(left, top, right, bottom)
                    canvas.drawRoundRect(rect, corner, corner, tilePaint)
                    borderPaint.color = if (n.missed) Color.rgb(255, 80, 100) else color
                    canvas.drawRoundRect(rect, corner, corner, borderPaint)
                    tilePaint.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
                    rect.set(left + pad * 2, bottom - tileH * 0.3f, right - pad * 2, bottom - pad * 2)
                    canvas.drawRoundRect(rect, corner / 2, corner / 2, tilePaint)
                }
            }
        }
    }

    private fun drawParticles(canvas: Canvas, t: Double) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val age = (t - p.born).toFloat()
            if (age > 0.6f || age < 0f) { it.remove(); continue }
            val x = p.x + p.vx * age
            val y = p.y + p.vy * age + 900f * density / 3 * age * age
            particlePaint.color = Color.argb(((1 - age / 0.6f) * 255).toInt(), Color.red(p.color), Color.green(p.color), Color.blue(p.color))
            canvas.drawCircle(x, y, 3.5f * density, particlePaint)
        }
    }

    private fun drawHud(canvas: Canvas, t: Double) {
        val w = width.toFloat()
        // Fortschrittsbalken
        if (songDuration > 0) {
            val p = (t / songDuration).coerceIn(0.0, 1.0).toFloat()
            linePaint.strokeWidth = 4 * density
            linePaint.color = Color.argb(50, 255, 255, 255)
            canvas.drawLine(0f, 2 * density, w, 2 * density, linePaint)
            linePaint.color = Color.rgb(0, 229, 255)
            canvas.drawLine(0f, 2 * density, w * p, 2 * density, linePaint)
        }

        textPaint.color = Color.WHITE
        textPaint.textSize = 30 * density
        canvas.drawText(stats.score.toString(), w / 2, 48 * density, textPaint)

        if (stats.combo >= 5) {
            textPaint.textSize = 20 * density
            textPaint.color = Color.argb(220, 255, 255, 255)
            canvas.drawText("${stats.combo} Combo  ×${multiplier()}", w / 2, 78 * density, textPaint)
        }

        val j = lastJudgement
        val age = t - lastJudgementAt
        if (j != null && age in 0.0..0.5) {
            val scale = 1f + (0.15f * (1 - (age / 0.1).toFloat().coerceAtMost(1f)))
            textPaint.textSize = 34 * density * scale
            textPaint.color = Color.argb(((1 - age / 0.5) * 255).toInt(), Color.red(j.color), Color.green(j.color), Color.blue(j.color))
            canvas.drawText(j.label, w / 2, height * 0.42f, textPaint)
        }
        val bAge = t - bonusAt
        if (bAge in 0.0..0.6) {
            textPaint.textSize = 22 * density
            textPaint.color = Color.argb(((1 - bAge / 0.6) * 255).toInt(), 255, 255, 255)
            canvas.drawText("GEHALTEN +${150 * multiplier()}", w / 2, height * 0.42f + 34 * density, textPaint)
        }

        countdownText?.let {
            textPaint.color = Color.WHITE
            textPaint.textSize = 72 * density
            canvas.drawText(it, w / 2, height * 0.5f, textPaint)
        }
    }
}
