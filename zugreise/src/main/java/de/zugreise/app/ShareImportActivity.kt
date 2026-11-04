package de.zugreise.app

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.zugreise.app.databinding.ActivityImportBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Nimmt geteilten Text (z. B. „Teilen“ im DB Navigator) oder Bilder/Screenshots entgegen,
 * erkennt darin die Züge und fügt die ausgewählten zur Reise hinzu.
 */
class ShareImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding
    private lateinit var store: JourneyStore
    private lateinit var journeyId: String
    private var date: LocalDate = LocalDate.now()

    private class Row(val leg: ParsedLeg, val check: CheckBox, val state: TextView, val manual: MaterialButton) {
        var done = false
    }

    private val rows = mutableListOf<Row>()
    private var manualRow: Row? = null

    private val manualAdd = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val row = manualRow
        manualRow = null
        if (result.resultCode == RESULT_OK && row != null) markDone(row)
        else if (result.resultCode == RESULT_OK) finishToMain()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
        store = JourneyStore(this)
        journeyId = store.current().id

        binding.btnDate.setOnClickListener { pickDate() }
        binding.btnTarget.setOnClickListener { pickTarget() }
        binding.btnImport.setOnClickListener { importSelected() }
        binding.btnManual.setOnClickListener { openManual(null) }
        binding.btnReparse.setOnClickListener { parse(binding.rawText.text.toString()) }
        updateButtons()

        val images = imageUris()
        val text = sharedText()
        when {
            images.isNotEmpty() -> recognize(images)
            text != null -> {
                binding.rawText.setText(text)
                parse(text)
            }
            else -> showStatus("Nichts zum Übernehmen gefunden. Teile Text oder einen Screenshot mit der App.")
        }
    }

    // ---------------- Eingang ----------------

    private fun sharedText(): String? {
        intent.getStringExtra(EXTRA_TEXT)?.let { return it }
        if (intent.action != Intent.ACTION_SEND) return null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        return listOfNotNull(subject, text).joinToString("\n").ifBlank { null }
    }

    @Suppress("DEPRECATION")
    private fun imageUris(): List<Uri> {
        intent.getStringExtra(EXTRA_IMAGE)?.let { return listOf(Uri.parse(it)) }
        val type = intent.type ?: return emptyList()
        if (!type.startsWith("image/")) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
    }

    private fun recognize(images: List<Uri>) {
        binding.preview.visibility = View.VISIBLE
        binding.preview.setImageURI(images.first())
        lifecycleScope.launch {
            setLoading(true)
            showStatus("Lese Text aus dem Bild …")
            try {
                val text = buildString {
                    for (uri in images) appendLine(ImageText.read(this@ShareImportActivity, uri))
                }
                binding.rawText.setText(text)
                parse(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showStatus("Bild konnte nicht gelesen werden: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    // ---------------- Auswerten ----------------

    private fun parse(text: String) {
        val parsed = ShareParser.parse(text)
        parsed.date?.let { date = it }
        updateButtons()
        rows.clear()
        binding.legs.removeAllViews()
        val usable = parsed.legs.filter { it.fromStation != null || it.train != null }
        if (usable.isEmpty()) {
            showStatus(
                if (text.contains("http") && !Regex("""\d{1,2}:\d{2}""").containsMatchIn(text))
                    "Der geteilte Text enthält nur einen Link, keine Zugdaten. Teile stattdessen einen Screenshot " +
                        "der Verbindung oder gib den Zug von Hand ein."
                else
                    "Keine Züge erkannt. Korrigiere den Text unten und tippe „Text neu auswerten“, oder gib den Zug von Hand ein."
            )
            binding.btnImport.isEnabled = false
            return
        }
        showStatus("${usable.size} ${if (usable.size == 1) "Zug" else "Züge"} erkannt:")
        binding.btnImport.isEnabled = true
        for (leg in usable) binding.legs.addView(rowView(leg))
    }

    private fun rowView(leg: ParsedLeg): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val check = CheckBox(this).apply {
            text = leg.label() + (if (leg.dayOffset > 0) "  (+${leg.dayOffset} Tag)" else "") +
                (if (leg.hasIntermediateStops) "\n${leg.stops.size} Halte im Text – wird ohne Internet übernommen" else "")
            isChecked = leg.fromStation != null && leg.departure != null
            setTypeface(typeface, Typeface.BOLD)
        }
        val manual = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Von Hand"
        }
        line.addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(manual)
        val state = TextView(this).apply {
            textSize = 13f
            setPadding(dp(32), 0, 0, 0)
            visibility = View.GONE
        }
        box.addView(line)
        box.addView(state)
        val row = Row(leg, check, state, manual)
        manual.setOnClickListener { openManual(row) }
        rows += row
        return box
    }

    // ---------------- Übernehmen ----------------

    private fun importSelected() {
        val selected = rows.filter { it.check.isChecked && !it.done }
        if (selected.isEmpty()) {
            showStatus("Bitte mindestens einen Zug anhaken.")
            return
        }
        lifecycleScope.launch {
            setLoading(true)
            binding.btnImport.isEnabled = false
            var failed = 0
            for (row in selected) {
                row.state.visibility = View.VISIBLE
                // 1. Stehen alle Halte im Text? Dann ohne Internet übernehmen.
                if (row.leg.hasIntermediateStops) {
                    row.state.text = "⏳ Übernehme Halte aus dem Text …"
                    val leg = offlineLeg(row.leg)
                    if (leg != null) {
                        store.addLeg(journeyId, leg)
                        markDone(row, "✓ ${leg.stops.size} Halte aus dem Text übernommen (ohne Internet)")
                        continue
                    }
                }
                // 2. Sonst Zug im Fahrplan suchen (mit allen Zwischenhalten und Gleisverlauf)
                row.state.text = "⏳ Suche Zug und Halte im Internet …"
                try {
                    val match = LegResolver.resolve(row.leg, date)
                    val alight = match.alight ?: askAlight(match)
                    if (alight == null) {
                        row.state.text = "Übersprungen"
                        continue
                    }
                    store.addLeg(journeyId, match.toLeg(alight))
                    markDone(row)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LegResolver.NotResolved) {
                    failed++
                    row.state.text = "✕ ${e.message} – tippe „Von Hand“"
                } catch (e: Exception) {
                    // Kein Internet: wenigstens Ein- und Ausstieg aus dem Text übernehmen
                    val leg = offlineLeg(row.leg)
                    if (leg != null) {
                        store.addLeg(journeyId, leg)
                        markDone(row, "✓ ohne Internet übernommen (nur Halte aus dem Text)")
                    } else {
                        failed++
                        row.state.text = "✕ ${e.message ?: "Keine Verbindung"} – tippe „Von Hand“"
                    }
                }
            }
            setLoading(false)
            binding.btnImport.isEnabled = true
            if (failed == 0 && rows.filter { it.check.isChecked }.all { it.done }) finishToMain()
            else showStatus("Nicht alles gefunden. Die markierten Züge kannst du „Von Hand“ ergänzen.")
        }
    }

    private suspend fun askAlight(match: LegResolver.Match): Int? = suspendCancellableCoroutine { cont ->
        val later = match.trip.stops.subList(match.board + 1, match.trip.stops.size)
        val labels = later.map { "${Times.withDelay(it.scheduledArrival, it.arrival, it.tz)}   ${it.name}" }
        var answered = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("${match.departure.name}: Wo steigst du aus?")
            .setItems(labels.toTypedArray()) { _, which ->
                answered = true
                cont.resume(match.board + 1 + which)
            }
            .setNegativeButton("Überspringen", null)
            .setOnDismissListener { if (!answered && cont.isActive) cont.resume(null) }
            .show()
        cont.invokeOnCancellation { dialog.dismiss() }
    }

    private suspend fun offlineLeg(p: ParsedLeg): Leg? = withContext(Dispatchers.Default) {
        try {
            LegResolver.fromText(p, date, StationDb.get(this@ShareImportActivity))
        } catch (e: Exception) {
            null
        }
    }

    private fun markDone(row: Row, message: String = "✓ hinzugefügt") {
        row.done = true
        row.check.isEnabled = false
        row.manual.isEnabled = false
        row.state.visibility = View.VISIBLE
        row.state.text = message
    }

    private fun openManual(row: Row?) {
        manualRow = row
        val i = Intent(this, AddLegActivity::class.java).putExtra(AddLegActivity.EXTRA_JOURNEY, journeyId)
        row?.leg?.let { leg ->
            leg.fromStation?.let { i.putExtra(AddLegActivity.EXTRA_STATION, it) }
            i.putExtra(AddLegActivity.EXTRA_DATE, date.plusDays(leg.dayOffset.toLong()).toString())
            leg.departure?.let { i.putExtra(AddLegActivity.EXTRA_TIME, it.toString()) }
            leg.train?.let { i.putExtra(AddLegActivity.EXTRA_TRAIN, it) }
            leg.toStation?.let { i.putExtra(AddLegActivity.EXTRA_EXIT, it) }
        }
        manualAdd.launch(i)
    }

    private fun finishToMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    // ---------------- Datum / Ziel-Reise ----------------

    private fun updateButtons() {
        binding.btnDate.text = date.format(DateTimeFormatter.ofPattern("EE, dd.MM.yyyy", Locale.GERMANY))
        binding.btnTarget.text = "Reise: " + (store.get(journeyId)?.name ?: "?")
    }

    private fun pickDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Reisedatum")
            .setSelection(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            updateButtons()
        }
        picker.show(supportFragmentManager, "date")
    }

    private fun pickTarget() {
        val all = store.all()
        val labels = all.map { (if (it.id == journeyId) "✓ " else "    ") + it.name } + "+ Neue Reise"
        MaterialAlertDialogBuilder(this)
            .setTitle("Zu welcher Reise hinzufügen?")
            .setItems(labels.toTypedArray()) { _, which ->
                journeyId = if (which < all.size) {
                    all[which].id.also { store.setCurrent(it) }
                } else {
                    store.create("Reise ${date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}").id
                }
                updateButtons()
            }
            .show()
    }

    // ---------------- Hilfen ----------------

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showStatus(text: String) {
        binding.status.text = text
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_TEXT = "import_text"
        const val EXTRA_IMAGE = "import_image"
    }
}
