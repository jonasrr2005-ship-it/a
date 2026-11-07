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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.rhythm.tiles.databinding.ActivityRecordBinding
import kotlin.math.abs

/**
 * Song einmal abspielen und überall hintippen, wo eine Kachel kommen soll.
 * Danach startet direkt das Spiel mit den eingetippten Kacheln in zufälligen Spuren.
 */
class RecordActivity : AppCompatActivity() {

    private enum class State { COUNTDOWN, RECORDING, PAUSED, DONE }

    private lateinit var binding: ActivityRecordBinding
    private lateinit var player: MediaPlayer
    private lateinit var songKey: String
    private var state = State.COUNTDOWN

    private var clockBase = 0.0
    private var clockAnchor = 0L
    private var pausedAt = 0.0
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
        songKey = intent.getStringExtra(GameActivity.EXTRA_SONG_KEY) ?: return finish()

        binding = ActivityRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.buttons) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bars.bottom + view.paddingTop)
            insets
        }

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
        player.setOnCompletionListener { finishRecording() }

        val view = binding.recordView
        view.songDuration = player.duration / 1000.0
        view.hint = getString(R.string.record_hint)
        view.clock = { if (state == State.RECORDING) songTime() else null }

        binding.doneButton.setOnClickListener { finishRecording() }
        binding.undoButton.setOnClickListener {
            if (!view.undo()) Toast.makeText(this, R.string.record_nothing_to_undo, Toast.LENGTH_SHORT).show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = pause()
        })

        countdownThen(0.0)
    }

    /** Songzeit in Sekunden, laufend an die Position des Players angeglichen. */
    private fun songTime(): Double {
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
        return t
    }

    /** 3-2-1 anzeigen, dann ab [from] abspielen und aufnehmen. */
    private fun countdownThen(from: Double) {
        state = State.COUNTDOWN
        for (i in 0..3) {
            handler.postDelayed({
                if (state != State.COUNTDOWN) return@postDelayed
                if (i < 3) {
                    binding.recordView.countdownText = (3 - i).toString()
                } else {
                    binding.recordView.countdownText = null
                    if (from > 0) player.seekTo((from * 1000).toInt())
                    player.start()
                    clockBase = from
                    clockAnchor = System.nanoTime()
                    state = State.RECORDING
                }
            }, i * 1000L)
        }
    }

    private fun pause() {
        if (state == State.DONE || state == State.PAUSED) return
        handler.removeCallbacksAndMessages(null)
        binding.recordView.releaseAll()
        if (state == State.RECORDING) {
            pausedAt = songTime()
            player.pause()
        }
        state = State.PAUSED
        binding.recordView.countdownText = null
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.paused)
            .setCancelable(false)
            .setPositiveButton(R.string.resume) { _, _ -> countdownThen(pausedAt) }
            .setNeutralButton(R.string.record_save_now) { _, _ -> finishRecording() }
            .setNegativeButton(R.string.record_discard) { _, _ -> finish() }
            .show()
    }

    private fun finishRecording() {
        if (state == State.DONE) return
        binding.recordView.releaseAll()
        state = State.DONE
        handler.removeCallbacksAndMessages(null)
        if (player.isPlaying) player.pause()

        val taps = binding.recordView.taps
        if (taps.size < 5) {
            Toast.makeText(this, R.string.record_too_few, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        TapStore.save(this, songKey, taps)
        Toast.makeText(this, getString(R.string.record_saved, taps.size), Toast.LENGTH_SHORT).show()
        // Direkt losspielen – mit denselben Angaben wie für die Aufnahme
        startActivity(
            Intent(this, GameActivity::class.java)
                .setData(intent.data)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtras(intent)
                .putExtra(GameActivity.EXTRA_TAPPED, true)
        )
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing && (state == State.RECORDING || state == State.COUNTDOWN)) pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
    }
}
