package de.rhythm.tiles

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import de.rhythm.tiles.databinding.ActivityResultBinding

class ResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCORE = "score"
        const val EXTRA_BEST = "best"
        const val EXTRA_ACCURACY = "accuracy"
        const val EXTRA_MAX_COMBO = "max_combo"
        const val EXTRA_COUNTS = "counts"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val score = intent.getLongExtra(EXTRA_SCORE, 0)
        val best = intent.getLongExtra(EXTRA_BEST, 0)
        val accuracy = intent.getDoubleExtra(EXTRA_ACCURACY, 0.0)
        val counts = intent.getIntArrayExtra(EXTRA_COUNTS) ?: IntArray(4)
        val difficulty = Difficulty.valueOf(intent.getStringExtra(GameActivity.EXTRA_DIFFICULTY) ?: Difficulty.NORMAL.name)

        val (grade, stars) = when {
            counts[3] == 0 && accuracy >= 0.98 -> "S" to 3
            accuracy >= 0.92 -> "A" to 3
            accuracy >= 0.82 -> "B" to 2
            accuracy >= 0.68 -> "C" to 1
            else -> "D" to 0
        }

        binding.songTitle.text = getString(R.string.result_title, intent.getStringExtra(GameActivity.EXTRA_TITLE), difficulty.label)
        binding.grade.text = grade
        binding.stars.text = "★".repeat(stars) + "☆".repeat(3 - stars)
        binding.score.text = score.toString()
        binding.newRecord.isVisible = score > best && score > 0
        binding.details.text = getString(
            R.string.result_details,
            accuracy * 100, intent.getIntExtra(EXTRA_MAX_COMBO, 0),
            counts[0], counts[1], counts[2], counts[3],
            maxOf(best, score),
        )

        binding.againButton.setOnClickListener {
            startActivity(
                Intent(this, GameActivity::class.java)
                    .setData(intent.data)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtras(intent)
            )
            finish()
        }
        binding.menuButton.setOnClickListener { finish() }
    }
}
