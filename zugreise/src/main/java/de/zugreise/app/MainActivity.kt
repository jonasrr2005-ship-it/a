package de.zugreise.app

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.zugreise.app.databinding.ActivityMainBinding
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: JourneyStore
    private lateinit var routeMap: RouteMap
    private lateinit var journey: Journey

    private val addLeg = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        refresh(zoom = result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        store = JourneyStore(this)
        routeMap = RouteMap(binding.map)

        binding.btnAdd.setOnClickListener {
            addLeg.launch(Intent(this, AddLegActivity::class.java).putExtra(AddLegActivity.EXTRA_JOURNEY, journey.id))
        }
        binding.btnImport.setOnClickListener { showImportMenu() }
        binding.btnFit.setOnClickListener { routeMap.zoomTo(journey.legs) }
        binding.btnJourneys.setOnClickListener { showJourneyMenu() }
        binding.journeyName.setOnClickListener { renameJourney() }

        refresh(zoom = true)
        showLastCrash()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refresh(zoom = true) // z. B. nach einem Import
    }

    override fun onRestart() {
        super.onRestart()
        refresh(zoom = false)
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            startActivity(
                Intent(this, ShareImportActivity::class.java)
                    .putExtra(ShareImportActivity.EXTRA_IMAGE, uri.toString())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    private fun showImportMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Züge importieren")
            .setMessage(
                "Tipp: Im DB Navigator (oder einer anderen App) bei der Verbindung auf „Teilen“ tippen und " +
                    "„Zugreise“ wählen – das geht mit Text und mit Screenshots."
            )
            .setPositiveButton("Screenshot wählen") { _, _ -> pickImage.launch("image/*") }
            .setNeutralButton("Text einfügen") { _, _ -> importClipboard() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun importClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        startActivity(
            Intent(this, ShareImportActivity::class.java)
                .putExtra(ShareImportActivity.EXTRA_TEXT, text.orEmpty())
        )
    }

    private fun showLastCrash() {
        val crash = ZugreiseApp.takeLastCrash(this) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Die App ist zuletzt abgestürzt")
            .setMessage("Bitte diese Meldung kopieren und weitergeben, damit der Fehler behoben werden kann:\n\n$crash")
            .setPositiveButton("Kopieren") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Zugreise-Fehler", crash))
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        routeMap.onResume()
    }

    override fun onPause() {
        routeMap.onPause()
        super.onPause()
    }

    private fun refresh(zoom: Boolean) {
        journey = store.current()
        binding.journeyName.text = journey.name
        routeMap.show(journey.legs)
        if (zoom) routeMap.zoomTo(journey.legs)
        updateSummary()
        renderLegs()
    }

    private fun updateSummary() {
        val legs = journey.legs
        if (legs.isEmpty()) {
            binding.summary.visibility = View.GONE
            return
        }
        val km = legs.sumOf { Geo.length(it.geometry.takeIf { g -> g.size >= 2 } ?: it.stops.map { s -> s.position }) } / 1000
        val stops = legs.sumOf { it.stops.size }
        binding.summary.visibility = View.VISIBLE
        binding.summary.text = "${legs.size} ${if (legs.size == 1) "Zug" else "Züge"} · ${km.roundToInt()} km · $stops Halte"
    }

    private fun renderLegs() {
        val list = binding.legList
        list.removeAllViews()
        if (journey.legs.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Noch keine Züge eingetragen.\n\nTippe auf „+ Zug hinzufügen“, wähle Einstiegsbahnhof, " +
                    "Datum, Uhrzeit und deinen Zug – die App sucht den kompletten Laufweg mit allen " +
                    "Zwischenhalten heraus und zeichnet ihn auf die Karte.\n\nFür Umstiege fügst du einfach " +
                    "weitere Züge hinzu."
                setPadding(0, dp(12), 0, 0)
            })
            return
        }
        journey.legs.forEachIndexed { i, leg ->
            // Umstiegszeit zwischen zwei Zügen anzeigen
            if (i > 0) {
                val prev = journey.legs[i - 1]
                val transfer = Times.duration(prev.to.arrival ?: prev.to.scheduledArrival, leg.from.departure ?: leg.from.scheduledDeparture)
                if (transfer.isNotEmpty()) {
                    val samePlace = Geo.distance(prev.to.position, leg.from.position) < 1000
                    list.addView(TextView(this).apply {
                        text = if (samePlace) "↓ Umstieg in ${leg.from.name}: $transfer" else "↓ $transfer (anderer Bahnhof: ${prev.to.name} → ${leg.from.name})"
                        textSize = 13f
                        alpha = 0.75f
                        setPadding(dp(12), dp(2), 0, dp(2))
                    })
                }
            }
            list.addView(legCard(i, leg))
        }
    }

    private fun legCard(index: Int, leg: Leg): View {
        val color = RouteMap.colorFor(index)
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
            radius = dp(10).toFloat()
            isClickable = true
            setOnClickListener {
                routeMap.zoomTo(listOf(leg))
                showStops(leg)
            }
            setOnLongClickListener { confirmDelete(leg); true }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(3).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT)
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        val from = leg.from
        val to = leg.to
        col.addView(TextView(this).apply {
            text = "${leg.name}  → ${leg.headsign ?: to.name}"
            setTypeface(typeface, Typeface.BOLD)
            textSize = 17f
        })
        col.addView(TextView(this).apply {
            text = listOfNotNull(Times.date(leg.departureTime, from.tz), leg.operator).joinToString(" · ")
            textSize = 13f
            alpha = 0.75f
        })
        col.addView(TextView(this).apply {
            text = "ab ${Times.withDelay(from.scheduledDeparture, from.departure, from.tz)}  ${from.name}" +
                (from.track?.let { " (Gl. $it)" } ?: "") +
                "\nan ${Times.withDelay(to.scheduledArrival, to.arrival, to.tz)}  ${to.name}" +
                (to.track?.let { " (Gl. $it)" } ?: "")
            textSize = 15f
            setPadding(0, dp(4), 0, dp(2))
        })
        val km = Geo.length(leg.geometry.takeIf { it.size >= 2 } ?: leg.stops.map { it.position }) / 1000
        val between = leg.stops.size - 2
        col.addView(TextView(this).apply {
            text = listOf(
                Times.duration(from.scheduledDeparture ?: from.departure, to.scheduledArrival ?: to.arrival),
                "${km.roundToInt()} km",
                if (between == 1) "1 Zwischenhalt" else "$between Zwischenhalte",
            ).filter { it.isNotEmpty() }.joinToString(" · ")
            textSize = 13f
            alpha = 0.75f
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        return card
    }

    private fun showStops(leg: Leg) {
        val lines = leg.stops.mapIndexed { k, s ->
            val arr = if (k > 0) Times.withDelay(s.scheduledArrival, s.arrival, s.tz) else "     "
            val dep = if (k < leg.stops.lastIndex) Times.withDelay(s.scheduledDeparture, s.departure, s.tz) else ""
            val time = if (k == 0) "ab $dep" else if (k == leg.stops.lastIndex) "an $arr" else "$arr – $dep"
            buildString {
                append(time).append("   ").append(s.name)
                s.track?.let { append("  (Gl. $it)") }
                if (s.cancelled) append("  ✕ fällt aus")
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("${leg.name} → ${leg.headsign ?: leg.to.name}")
            .setItems(lines.toTypedArray(), null)
            .setPositiveButton("OK", null)
            .setNegativeButton("Löschen") { _, _ -> confirmDelete(leg) }
            .show()
    }

    private fun confirmDelete(leg: Leg) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Zug löschen?")
            .setMessage("${leg.name}: ${leg.from.name} → ${leg.to.name} am ${Times.date(leg.departureTime, leg.from.tz)}")
            .setPositiveButton("Löschen") { _, _ ->
                store.removeLeg(journey.id, leg.id)
                refresh(zoom = false)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showJourneyMenu() {
        val all = store.all()
        val labels = all.map { j ->
            val date = j.legs.firstOrNull()?.let { " – " + Times.date(it.departureTime, it.from.tz) } ?: ""
            (if (j.id == journey.id) "✓ " else "    ") + j.name + date + " (${j.legs.size} ${if (j.legs.size == 1) "Zug" else "Züge"})"
        } + listOf("+ Neue Reise anlegen", "✎ Diese Reise umbenennen", "🗑 Diese Reise löschen")
        MaterialAlertDialogBuilder(this)
            .setTitle("Reisen")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which - all.size) {
                    0 -> askName("Neue Reise", "") { name ->
                        store.create(name)
                        refresh(zoom = true)
                    }
                    1 -> renameJourney()
                    2 -> deleteJourney()
                    else -> {
                        store.setCurrent(all[which].id)
                        refresh(zoom = true)
                    }
                }
            }
            .show()
    }

    private fun renameJourney() = askName("Reise umbenennen", journey.name) { name ->
        store.update(journey.copy(name = name))
        refresh(zoom = false)
    }

    private fun deleteJourney() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reise „${journey.name}“ löschen?")
            .setMessage("Alle ${journey.legs.size} eingetragenen Züge dieser Reise werden gelöscht.")
            .setPositiveButton("Löschen") { _, _ ->
                store.delete(journey.id)
                refresh(zoom = true)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            hint = "z. B. Urlaub Italien"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
