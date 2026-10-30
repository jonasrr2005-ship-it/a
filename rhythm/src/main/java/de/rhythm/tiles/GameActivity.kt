package de.rhythm.tiles

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs

class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SONG_KEY = "song_key"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DIFFICULTY = "difficulty"

        /** Vorlauf, damit die ersten Kacheln von oben hereinfallen können. */
        private const val LEAD_IN = 2.5
    }

    private enum class State { LOADING, LEAD_IN, PLAYING, PAUSED, RESUMING, FINISHED }

    private lateinit var gameView: GameView
    private lateinit var player: MediaPlayer
    private lateinit var songKey: String
    private lateinit var songTitle: String
    private lateinit var difficulty: Difficulty
    private var state = State.LOADING

    // Uhr: Songzeit = base + (jetzt − anchor), sanft an die Player-Position angeglichen
    private var clockBase = 0.0
    private var clockAnchor = 0L
    private var frozenTime = -LEAD_IN
    private var resumeAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val uri: Uri = intent.data ?: return finish()
        songKey = intent.getStringExtra(EXTRA_SONG_KEY) ?: return finish()
        songTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        difficulty = Difficulty.valueOf(intent.getStringExtra(EXTRA_DIFFICULTY) ?: Difficulty.NORMAL.name)
        val notes = ChartStore.load(this, songKey, difficulty) ?: return finish()

        player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        try {
            player.setDataSource(this, uri)
            player.prepare()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_play, e.message), Toast.LENGTH_LONG).show()
            player.release()
            finish()
            return
        }
        player.setOnCompletionListener { finishGame() }

        gameView = GameView(this, notes, difficulty, player.duration / 1000.0, ::songTime)
        setContentView(gameView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (state == State.PAUSED) showPauseDialog() else pause()
            }
        })

        clockAnchor = System.nanoTime()
        state = State.LEAD_IN
    }

    /** Aktuelle Position im Song in Sekunden (negativ während des Vorlaufs). */
    private fun songTime(): Double {
        val now = System.nanoTime()
        when (state) {
            State.LOADING -> return -LEAD_IN
            State.PAUSED, State.FINISHED -> return frozenTime
            State.RESUMING -> {
                val left = (resumeAt - now) / 1e9
                if (left > 0) {
                    gameView.countdownText = (left + 1).toInt().toString()
                    return frozenTime
                }
                gameView.countdownText = null
                startPlayback(frozenTime)
            }
            State.LEAD_IN -> {
                val t = -LEAD_IN + (now - clockAnchor) / 1e9
                if (t < 0) {
                    gameView.countdownText = if (t < -LEAD_IN + 1.5) songTitle.take(30).ifEmpty { null } else null
                    return t
                }
                gameView.countdownText = null
                startPlayback(0.0)
            }
            State.PLAYING -> {}
        }
        var t = clockBase + (System.nanoTime() - clockAnchor) / 1e9
        if (player.isPlaying) {
            val pos = player.currentPosition / 1000.0
            val drift = pos - t
            if (abs(drift) > 0.12) {
                clockBase = pos; clockAnchor = System.nanoTime(); t = pos
            } else {
                clockBase += drift * 0.03
            }
        }
        if (t > player.duration / 1000.0 + 0.5) handler.post { finishGame() }
        return t
    }

    private fun startPlayback(from: Double) {
        if (from > 0) player.seekTo((from * 1000).toInt())
        player.start()
        clockBase = from
        clockAnchor = System.nanoTime()
        state = State.PLAYING
    }

    private fun pause() {
        if (state != State.PLAYING && state != State.LEAD_IN && state != State.RESUMING) return
        when (state) {
            State.PLAYING -> {
                player.pause()
                frozenTime = player.currentPosition / 1000.0
            }
            // Im Vorlauf pausiert: nach dem Countdown am Songanfang weitermachen
            State.LEAD_IN -> frozenTime = 0.0
            else -> {} // RESUMING: frozenTime ist bereits gesetzt
        }
        state = State.PAUSED
        gameView.paused = true
        gameView.countdownText = null
        gameView.invalidate()
        showPauseDialog()
    }

    private fun showPauseDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.paused)
            .setCancelable(false)
            .setPositiveButton(R.string.resume) { _, _ -> resume() }
            .setNegativeButton(R.string.quit) { _, _ -> finish() }
            .setNeutralButton(R.string.restart) { _, _ -> restart() }
            .show()
    }

    private fun resume() {
        if (state != State.PAUSED) return
        resumeAt = System.nanoTime() + 3_000_000_000L
        state = State.RESUMING
        gameView.paused = false
        gameView.invalidate()
    }

    private fun restart() {
        startActivity(Intent(intent))
        finish()
    }

    private fun finishGame() {
        if (state == State.FINISHED) return
        state = State.FINISHED
        frozenTime = player.duration / 1000.0
        gameView.paused = true
        val s = gameView.stats
        val best = Scores.submit(this, songKey, difficulty, s.score)
        startActivity(
            Intent(this, ResultActivity::class.java)
                .setData(intent.data)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_SONG_KEY, songKey)
                .putExtra(EXTRA_TITLE, songTitle)
                .putExtra(EXTRA_DIFFICULTY, difficulty.name)
                .putExtra(ResultActivity.EXTRA_SCORE, s.score)
                .putExtra(ResultActivity.EXTRA_BEST, best)
                .putExtra(ResultActivity.EXTRA_ACCURACY, s.accuracy)
                .putExtra(ResultActivity.EXTRA_MAX_COMBO, s.maxCombo)
                .putExtra(ResultActivity.EXTRA_COUNTS, intArrayOf(s.perfect, s.great, s.good, s.miss))
        )
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (state == State.PLAYING || state == State.LEAD_IN || state == State.RESUMING) pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
    }
}
