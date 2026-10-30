package de.rhythm.tiles

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import de.rhythm.tiles.databinding.ActivityMainBinding
import de.rhythm.tiles.databinding.ItemSongBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var busy = false

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

        val prefs = getPreferences(MODE_PRIVATE)
        val saved = Difficulty.entries.getOrNull(prefs.getInt("difficulty", 1)) ?: Difficulty.NORMAL
        binding.difficultyGroup.check(buttonFor(saved))
        binding.difficultyGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) prefs.edit().putInt("difficulty", selectedDifficulty().ordinal).apply()
            refreshRecent()
        }

        binding.pickButton.setOnClickListener {
            pickSong.launch(arrayOf("audio/*", "application/ogg"))
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

    private fun startSong(uri: Uri) {
        if (busy) return
        val difficulty = selectedDifficulty()
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val title = displayName(this, uri)
        val key = ChartStore.songKey(this, uri)

        if (ChartStore.load(this, key, difficulty) != null) {
            Scores.addRecent(this, uri, title)
            launchGame(uri, key, title, difficulty)
            return
        }

        busy = true
        binding.progressGroup.isVisible = true
        binding.pickButton.isEnabled = false
        binding.progress.progress = 0
        binding.status.text = getString(R.string.analyzing, title)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val pcm = AudioDecoder.decodeMono(this@MainActivity, uri) { p ->
                        binding.progress.post { binding.progress.progress = (p * 90).toInt() }
                    }
                    val onsets = BeatAnalyzer.detectOnsets(pcm)
                    val seed = key.hashCode().toLong()
                    // Gleich alle Schwierigkeitsgrade erzeugen, die Analyse ist der teure Teil
                    for (d in Difficulty.entries) {
                        ChartStore.save(this@MainActivity, key, d, ChartGenerator.generate(onsets, d, seed))
                    }
                    ChartStore.load(this@MainActivity, key, difficulty)!!.size
                }
            }
            busy = false
            binding.progressGroup.isVisible = false
            binding.pickButton.isEnabled = true
            result.onSuccess { count ->
                if (count < 5) {
                    Toast.makeText(this@MainActivity, R.string.too_few_beats, Toast.LENGTH_LONG).show()
                } else {
                    Scores.addRecent(this@MainActivity, uri, title)
                    launchGame(uri, key, title, difficulty)
                }
            }.onFailure {
                Toast.makeText(this@MainActivity, getString(R.string.error_analyze, it.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchGame(uri: Uri, key: String, title: String, difficulty: Difficulty) {
        startActivity(
            Intent(this, GameActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(GameActivity.EXTRA_SONG_KEY, key)
                .putExtra(GameActivity.EXTRA_TITLE, title)
                .putExtra(GameActivity.EXTRA_DIFFICULTY, difficulty.name)
        )
    }

    private fun refreshRecent() {
        val container = binding.recentList
        container.removeAllViews()
        val recent = Scores.recent(this)
        binding.recentHeader.isVisible = recent.isNotEmpty()
        val difficulty = selectedDifficulty()
        for (song in recent) {
            val item = ItemSongBinding.inflate(LayoutInflater.from(this), container, false)
            item.songTitle.text = song.title
            val key = ChartStore.songKey(this, song.uri)
            val best = Scores.best(this, key, difficulty)
            item.songBest.text = if (best > 0) getString(R.string.best_score, best) else getString(R.string.not_played)
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
