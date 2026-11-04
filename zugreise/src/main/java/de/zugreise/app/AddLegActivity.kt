package de.zugreise.app

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import de.zugreise.app.databinding.ActivityAddLegBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class AddLegActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddLegBinding
    private lateinit var store: JourneyStore
    private lateinit var journeyId: String

    private var station: Station? = null
    private var date: LocalDate = LocalDate.now()
    private var time: LocalTime = LocalTime.now().withSecond(0).withNano(0)

    private var suggestJob: Job? = null
    private var searchJob: Job? = null
    private var ignoreTextChange = false
    private lateinit var suggestions: NoFilterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddLegBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        suggestions = NoFilterAdapter(this)
        store = JourneyStore(this)
        journeyId = intent.getStringExtra(EXTRA_JOURNEY) ?: store.current().id
        prefillFromPreviousLeg()
        val prefilled = prefillFromIntent()

        setupStationInput()
        binding.btnDate.setOnClickListener { pickDate() }
        binding.btnTime.setOnClickListener { pickTime() }
        binding.btnSearch.setOnClickListener { searchDepartures() }
        binding.train.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { searchDepartures(); true } else false
        }
        updateDateTimeButtons()
        if (prefilled) searchDepartures()
    }

    /** Vorgaben aus einem Import (geteilter Text / Bild). */
    private fun prefillFromIntent(): Boolean {
        val name = intent.getStringExtra(EXTRA_STATION) ?: return false
        station = null
        setStationText(name)
        intent.getStringExtra(EXTRA_DATE)?.let { runCatching { date = LocalDate.parse(it) } }
        intent.getStringExtra(EXTRA_TIME)?.let { runCatching { time = LocalTime.parse(it) } }
        intent.getStringExtra(EXTRA_TRAIN)?.let { binding.train.setText(it) }
        return intent.getStringExtra(EXTRA_TIME) != null
    }

    /** Für Umstiege: Einstieg = letzter Ausstieg, Zeit = letzte Ankunft. */
    private fun prefillFromPreviousLeg() {
        val last = store.get(journeyId)?.legs?.lastOrNull() ?: return
        val stop = last.to
        val arrival = Times.parse(stop.arrival ?: stop.scheduledArrival) ?: return
        val local = arrival.atZone(ZoneId.systemDefault())
        date = local.toLocalDate()
        time = local.toLocalTime().withSecond(0).withNano(0)
        if (stop.stopId != null) {
            station = Station(stop.stopId, stop.name, stop.lat, stop.lon, null)
            setStationText(stop.name)
        }
    }

    // ---------------- Bahnhofssuche mit Vorschlägen ----------------

    private fun setStationText(text: String) {
        ignoreTextChange = true
        binding.station.setText(text, false)
        ignoreTextChange = false
    }

    private fun setupStationInput() {
        binding.station.setAdapter(suggestions)
        binding.station.setOnItemClickListener { _, _, position, _ ->
            suggestJob?.cancel()
            station = suggestions.stations.getOrNull(position)
            station?.let { setStationText(it.toString()) }
            binding.train.requestFocus()
        }
        binding.station.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (ignoreTextChange) return
                station = null
                val text = s?.toString()?.trim().orEmpty()
                suggestJob?.cancel()
                if (text.length < 2) return
                suggestJob = lifecycleScope.launch {
                    delay(350)
                    try {
                        val found = TransitApi.searchStations(text, binding.trainsOnly.isChecked)
                        suggestions.setStations(found)
                        if (found.isNotEmpty() && station == null && binding.station.hasFocus()) binding.station.showDropDown()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        showStatus("Bahnhofssuche fehlgeschlagen: ${e.message ?: "keine Verbindung"}")
                    }
                }
            }
        })
    }

    /** Adapter, der die Vorschläge der API unverändert anzeigt (kein lokales Filtern). */
    private class NoFilterAdapter(context: Context) :
        ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, ArrayList()) {
        var stations: List<Station> = emptyList()
            private set

        fun setStations(list: List<Station>) {
            stations = list
            clear()
            addAll(list.map { it.toString() })
            notifyDataSetChanged()
        }

        private val noFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?) =
                FilterResults().apply { values = stations; count = stations.size }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
        }

        override fun getFilter(): Filter = noFilter
    }

    // ---------------- Datum / Uhrzeit ----------------

    private fun updateDateTimeButtons() {
        binding.btnDate.text = date.format(DateTimeFormatter.ofPattern("EE, dd.MM.yyyy", Locale.GERMANY))
        binding.btnTime.text = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)) + " Uhr"
    }

    private fun pickDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Reisedatum")
            .setSelection(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            updateDateTimeButtons()
        }
        picker.show(supportFragmentManager, "date")
    }

    private fun pickTime() {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(time.hour)
            .setMinute(time.minute)
            .setTitleText("Abfahrtszeit (ungefähr)")
            .build()
        picker.addOnPositiveButtonClickListener {
            time = LocalTime.of(picker.hour, picker.minute)
            updateDateTimeButtons()
        }
        picker.show(supportFragmentManager, "time")
    }

    // ---------------- Abfahrten ----------------

    private fun searchDepartures() {
        hideKeyboard()
        val st = station
        if (st == null) {
            val typed = binding.station.text.toString().trim()
            if (typed.length < 2) {
                showStatus("Bitte zuerst den Einstiegsbahnhof eingeben.")
                return
            }
            // Nutzer hat keinen Vorschlag angetippt: besten Treffer nehmen
            runSearch {
                val found = TransitApi.searchStations(typed, binding.trainsOnly.isChecked)
                val best = (if (binding.trainsOnly.isChecked) found.firstOrNull { it.hasTrains == true } else null)
                    ?: found.firstOrNull() ?: run {
                    showStatus("Kein Bahnhof „$typed“ gefunden.")
                    return@runSearch
                }
                station = best
                setStationText(best.toString())
                loadDepartures(best)
            }
            return
        }
        runSearch { loadDepartures(st) }
    }

    private fun runSearch(block: suspend () -> Unit) {
        searchJob?.cancel()
        binding.results.removeAllViews()
        searchJob = lifecycleScope.launch {
            setLoading(true)
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showStatus("Fehler: ${e.message ?: "keine Internetverbindung?"}")
            } finally {
                setLoading(false)
            }
        }
    }

    private suspend fun loadDepartures(st: Station) {
        val query = binding.train.text.toString().trim()
        // 15 Minuten früher anfangen, damit der Zug sicher dabei ist
        val start = LocalDateTime.of(date, time).minusMinutes(15).atZone(ZoneId.systemDefault()).toInstant()
        showStatus("Suche Abfahrten in ${st.label} …")
        val trainsOnly = binding.trainsOnly.isChecked
        var all = TransitApi.departures(st.id, start, if (query.isNotEmpty()) 4 else null, trainsOnly)
        if (all.isEmpty()) {
            // Nichts in der Nähe der Uhrzeit: einen ganzen Tag durchsuchen
            showStatus("Keine Abfahrten zur gewählten Zeit – suche im ganzen Tag …")
            all = TransitApi.departures(st.id, start, 24, trainsOnly)
        }
        val matching = all.filter { TransitApi.matches(it, query) }
        val shown = when {
            query.isEmpty() -> all
            matching.isNotEmpty() -> matching
            else -> all
        }
        showStatus(
            when {
                all.isEmpty() && trainsOnly ->
                    "An „${st.label}“ fahren in den nächsten 24 Stunden keine Züge. Wähle einen Vorschlag mit 🚆 " +
                        "oder schalte „Nur Züge“ aus."
                all.isEmpty() -> "Keine Abfahrten gefunden. Anderer Bahnhof oder andere Uhrzeit?"
                query.isNotEmpty() && matching.isEmpty() ->
                    "„$query“ nicht gefunden – hier alle Abfahrten. Tippe deinen Zug an:"
                else -> "Tippe deinen Zug an:"
            }
        )
        for (dep in shown.take(80)) binding.results.addView(departureRow(dep))
    }

    private fun departureRow(dep: Departure): View {
        val s = dep.stop
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = attrs.getDrawable(0)
            attrs.recycle()
            setOnClickListener { chooseDeparture(dep) }
        }
        view.addView(TextView(this).apply {
            text = "${Times.withDelay(s.scheduledDeparture, s.departure, s.tz)}   ${dep.name}  → ${dep.headsign ?: "?"}"
            setTypeface(typeface, Typeface.BOLD)
            textSize = 16f
        })
        view.addView(TextView(this).apply {
            text = listOfNotNull(
                Times.shortDate(s.scheduledDeparture ?: s.departure, s.tz),
                s.track?.let { "Gl. $it" },
                dep.operator,
                if (dep.cancelled) "✕ fällt aus" else null,
            ).joinToString(" · ")
            textSize = 13f
            alpha = 0.75f
        })
        return view
    }

    /** Zug gewählt: kompletten Laufweg laden und nach dem Ausstieg fragen. */
    private fun chooseDeparture(dep: Departure) {
        runSearch {
            showStatus("Lade Laufweg von ${dep.name} …")
            val trip = TransitApi.trip(dep.tripId)
            val board = LegResolver.boardingIndex(trip.stops, dep.stop)
            if (board >= trip.stops.lastIndex) {
                showStatus("${dep.name} endet hier – bitte einen anderen Zug wählen.")
                return@runSearch
            }
            // Ausstieg schon bekannt (z. B. aus geteiltem Text)? Dann direkt speichern.
            val exitName = intent.getStringExtra(EXTRA_EXIT)
            if (exitName != null) {
                LegResolver.alightIndex(trip.stops, board, exitName, null)?.let {
                    saveLeg(trip, dep, board, it)
                    return@runSearch
                }
            }
            showStatus("${dep.name} → ${trip.headsign ?: trip.stops.last().name}: Wo steigst du aus?")
            val later = trip.stops.subList(board + 1, trip.stops.size)
            val labels = later.map { st ->
                "${Times.withDelay(st.scheduledArrival, st.arrival, st.tz)}   ${st.name}" +
                    (st.track?.let { "  (Gl. $it)" } ?: "")
            }
            MaterialAlertDialogBuilder(this@AddLegActivity)
                .setTitle("${dep.name}: Wo steigst du aus?")
                .setItems(labels.toTypedArray()) { _, which -> saveLeg(trip, dep, board, board + 1 + which) }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun saveLeg(trip: Trip, dep: Departure, from: Int, to: Int) {
        store.addLeg(journeyId, LegResolver.buildLeg(trip, dep, from, to))
        setResult(RESULT_OK)
        finish()
    }

    // ---------------- Hilfen ----------------

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSearch.isEnabled = !loading
    }

    private fun showStatus(text: String) {
        binding.status.text = text
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.station.dismissDropDown()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_JOURNEY = "journey"
        const val EXTRA_STATION = "station"
        const val EXTRA_DATE = "date"
        const val EXTRA_TIME = "time"
        const val EXTRA_TRAIN = "train"
        const val EXTRA_EXIT = "exit"
    }
}
