package de.rhythm.tiles

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.rhythm.tiles.databinding.ActivityMainBinding
import de.rhythm.tiles.databinding.ItemSongBinding
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val chipIds = HashMap<Int, Instrument>()

    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private val pickSong = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startSong(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val savedDifficulty = Difficulty.entries.getOrNull(prefs.getInt("difficulty", 1)) ?: Difficulty.NORMAL
        binding.difficultyGroup.check(buttonFor(savedDifficulty))
        binding.difficultyGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) prefs.edit().putInt("difficulty", selectedDifficulty().ordinal).apply()
            refreshRecent()
        }

        val savedInstrument = runCatching { Instrument.valueOf(prefs.getString("instrument", null)!!) }
            .getOrDefault(Instrument.ALL)
        for (inst in Instrument.entries) {
            val chip = layoutInflater.inflate(R.layout.item_instrument_chip, binding.instrumentGroup, false) as Chip
            chip.id = View.generateViewId()
            chip.text = inst.label
            chipIds[chip.id] = inst
            binding.instrumentGroup.addView(chip)
            if (inst == savedInstrument) chip.isChecked = true
        }
        binding.instrumentGroup.setOnCheckedStateChangeListener { _, _ ->
            prefs.edit().putString("instrument", selectedInstrument().name).apply()
            updateInstrumentUi()
            refreshRecent()
        }

        binding.isolateSwitch.isChecked = prefs.getBoolean("isolate", true)
        binding.isolateSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("isolate", checked).apply()
        }

        binding.modelStatus.setOnLongClickListener {
            if (!StemSeparator.isModelInstalled(this) || SongPreparer.busy) return@setOnLongClickListener false
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.model_delete_title)
                .setMessage(R.string.model_delete_text)
                .setPositiveButton(R.string.delete) { _, _ ->
                    StemSeparator.deleteModel(this)
                    updateInstrumentUi()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }

        binding.pickButton.setOnClickListener {
            pickSong.launch(arrayOf("audio/*", "application/ogg"))
        }
        binding.cancelButton.setOnClickListener { SongPreparer.cancel() }

        updateInstrumentUi()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SongPreparer.status.collect { render(it) }
            }
        }

        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()
    }

    /** Songs, die per „Teilen“ oder „Öffnen mit“ aus anderen Apps kommen. */
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            intent?.action = null // nicht erneut starten, z. B. nach Drehen des Bildschirms
            startSong(uri)
        }
    }

    private fun buttonFor(d: Difficulty) = when (d) {
        Difficulty.EASY -> R.id.btn_easy
        Difficulty.NORMAL -> R.id.btn_normal
        Difficulty.HARD -> R.id.btn_hard
    }

    private fun selectedDifficulty() = when (binding.difficultyGroup.checkedButtonId) {
        R.id.btn_easy -> Difficulty.EASY
        R.id.btn_hard -> Difficulty.HARD
        else -> Difficulty.NORMAL
    }

    private fun selectedInstrument() = chipIds[binding.instrumentGroup.checkedChipId] ?: Instrument.ALL

    private fun updateInstrumentUi() {
        val inst = selectedInstrument()
        binding.isolateSwitch.isVisible = inst.needsAi
        binding.modelStatus.text = when {
            !inst.needsAi -> getString(R.string.model_rhythm)
            StemSeparator.isModelInstalled(this) ->
                getString(R.string.model_installed, (StemSeparator.modelFile(this).length() / 1_000_000).toInt())
            else -> getString(R.string.model_missing)
        }
    }

    private fun startSong(uri: Uri) {
        if (SongPreparer.busy) return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val instrument = selectedInstrument()
        val request = SongPreparer.Request(
            uri = uri,
            title = displayName(this, uri),
            songKey = ChartStore.songKey(this, uri),
            instrument = instrument,
            difficulty = selectedDifficulty(),
            isolate = instrument.needsAi && binding.isolateSwitch.isChecked,
        )

        val (ready, audio) = SongPreparer.readyFile(this, request)
        when {
            ready -> launchGame(request, audio)
            instrument.needsAi && !StemSeparator.isModelInstalled(this) -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.model_download_title)
                    .setMessage(getString(R.string.model_download_text, instrument.label))
                    .setPositiveButton(R.string.download) { _, _ -> SongPreparer.start(this, request) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            else -> SongPreparer.start(this, request)
        }
    }

    private fun render(status: SongPreparer.Status?) {
        val working = status is SongPreparer.Status.Working
        binding.progressGroup.isVisible = working
        binding.pickButton.isEnabled = !working
        binding.instrumentGroup.isEnabled = !working
        for (i in 0 until binding.instrumentGroup.childCount) binding.instrumentGroup.getChildAt(i).isEnabled = !working
        binding.difficultyGroup.isEnabled = !working
        binding.isolateSwitch.isEnabled = !working
        if (working) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        when (status) {
            null -> {}
            is SongPreparer.Status.Working -> {
                binding.status.text = status.text
                val p = status.progress
                if (p == null) {
                    if (!binding.progress.isIndeterminate) {
                        binding.progress.isVisible = false
                        binding.progress.isIndeterminate = true
                        binding.progress.isVisible = true
                    }
                } else {
                    if (binding.progress.isIndeterminate) {
                        binding.progress.isVisible = false
                        binding.progress.isIndeterminate = false
                        binding.progress.isVisible = true
                    }
                    binding.progress.progress = (p * 1000).toInt()
                }
            }
            is SongPreparer.Status.Ready -> {
                SongPreparer.consume()
                updateInstrumentUi()
                launchGame(status.request, status.audioFile)
            }
            is SongPreparer.Status.TooFewNotes -> {
                SongPreparer.consume()
                updateInstrumentUi()
                val text = if (status.request.instrument.needsAi) {
                    getString(R.string.too_few_notes, status.request.instrument.label)
                } else getString(R.string.too_few_beats)
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
            is SongPreparer.Status.Failed -> {
                SongPreparer.consume()
                updateInstrumentUi()
                Toast.makeText(this, getString(R.string.error_analyze, status.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchGame(r: SongPreparer.Request, audioFile: File?) {
        Scores.addRecent(this, r.uri, r.title)
        startActivity(
            Intent(this, GameActivity::class.java)
                .setData(r.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(GameActivity.EXTRA_SONG_KEY, r.songKey)
                .putExtra(GameActivity.EXTRA_TITLE, r.title)
                .putExtra(GameActivity.EXTRA_DIFFICULTY, r.difficulty.name)
                .putExtra(GameActivity.EXTRA_INSTRUMENT, r.instrument.name)
                .putExtra(GameActivity.EXTRA_AUDIO_FILE, audioFile?.path)
        )
    }

    private fun refreshRecent() {
        val container = binding.recentList
        container.removeAllViews()
        val recent = Scores.recent(this)
        binding.recentHeader.isVisible = recent.isNotEmpty()
        val difficulty = selectedDifficulty()
        val instrument = selectedInstrument()
        for (song in recent) {
            val item = ItemSongBinding.inflate(LayoutInflater.from(this), container, false)
            item.songTitle.text = song.title
            val key = ChartStore.songKey(this, song.uri)
            val best = Scores.best(this, key, instrument, difficulty)
            item.songBest.text = if (best > 0) {
                getString(R.string.best_score, best, instrument.label)
            } else getString(R.string.not_played)
            item.root.setOnClickListener {
                val readable = runCatching {
                    contentResolver.openInputStream(song.uri)?.close() != null
                }.getOrDefault(false)
                if (readable) {
                    startSong(song.uri)
                } else {
                    Toast.makeText(this, R.string.song_unavailable, Toast.LENGTH_LONG).show()
                    Scores.removeRecent(this, song.uri)
                    refreshRecent()
                }
            }
            item.root.setOnLongClickListener {
                Scores.removeRecent(this, song.uri)
                refreshRecent()
                true
            }
            container.addView(item.root)
        }
        binding.emptyHint.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
    }
}
