package de.standort.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.standort.tracker.databinding.ActivityMainBinding
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var map: TrackMap
    private lateinit var mapType: MapType
    private var mapReady = false

    /** Karte folgt der aktuellen Position, bis der Nutzer sie selbst verschiebt. */
    private var followPosition = true

    /** Was nach erteilter Standortberechtigung ausgeführt werden soll. */
    private var afterPermission: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = afterPermission
        afterPermission = null
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            action?.invoke()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrackRepository.init(this)
        PinRepository.init(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val savedType = getPreferences(MODE_PRIVATE).getString(PREF_MAP_TYPE, null)
        mapType = MapType.entries.firstOrNull { it.name == savedType } ?: MapType.OSM
        attachMap(savedInstanceState)
        binding.btnMapType.setOnClickListener { switchMapType() }

        binding.btnToggle.setOnClickListener {
            if (TrackRepository.isRecording.value) stopTracking() else withLocationPermission(::startTracking)
        }
        binding.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.clear_track_question)
                .setPositiveButton(R.string.delete) { _, _ -> TrackRepository.clear() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        binding.btnCenter.setOnClickListener {
            followPosition = true
            TrackRepository.points.value.lastOrNull()?.let { map.moveTo(it, animate = true) }
        }
        binding.btnPin.setOnClickListener { withLocationPermission(::setPin) }
        binding.btnPins.setOnClickListener { showPinList() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TrackRepository.points.collect(::showTrack) }
                launch {
                    PinRepository.pins.collect { pins ->
                        binding.btnPins.text = getString(R.string.pin_list, pins.size)
                        if (mapReady) map.showPins(pins)
                    }
                }
                launch {
                    TrackRepository.isRecording.collect { recording ->
                        binding.btnToggle.setText(if (recording) R.string.stop else R.string.start)
                    }
                }
            }
        }
    }

    // ---- Karte ----

    private fun attachMap(savedInstanceState: Bundle?) {
        mapReady = false
        map = mapType.create()
        map.onUserMoved = { followPosition = false }
        map.onPinClicked = ::showPinDetails
        binding.btnMapType.setText(
            if (mapType == MapType.OSM) R.string.map_type_osm else R.string.map_type_google
        )
        map.attach(this, binding.mapContainer, savedInstanceState) {
            mapReady = true
            TrackRepository.points.value.lastOrNull()?.let { map.moveTo(it, animate = false) }
            map.showTrack(TrackRepository.points.value)
            map.showPins(PinRepository.pins.value)
        }
    }

    /** Wechselt zwischen OpenStreetMap und Google Maps; die Wahl wird gespeichert. */
    private fun switchMapType() {
        map.detach(this, binding.mapContainer)
        mapType = if (mapType == MapType.OSM) MapType.GOOGLE else MapType.OSM
        getPreferences(MODE_PRIVATE).edit().putString(PREF_MAP_TYPE, mapType.name).apply()
        if (mapType == MapType.GOOGLE && !BuildConfig.HAS_MAPS_KEY) {
            Toast.makeText(this, R.string.google_no_key, Toast.LENGTH_LONG).show()
        }
        attachMap(null)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
    }

    private fun showTrack(points: List<TrackPoint>) {
        binding.stats.text = getString(
            R.string.stats,
            points.size,
            TrackRepository.formatDistance(TrackRepository.distanceMeters(points))
        )
        if (!mapReady) return
        map.showTrack(points)
        val last = points.lastOrNull()
        if (last != null && followPosition && TrackRepository.isRecording.value) {
            map.moveTo(last, animate = true)
        }
    }

    // ---- Berechtigungen & Aufzeichnung ----

    private fun withLocationPermission(action: () -> Unit) {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            afterPermission = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startTracking() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, R.string.gps_disabled, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        followPosition = true
        ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
    }

    private fun stopTracking() {
        startService(Intent(this, LocationService::class.java).setAction(LocationService.ACTION_STOP))
    }

    // ---- Pins ----

    private fun setPin() {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, R.string.locating, Toast.LENGTH_SHORT).show()
            val location = currentLocation()
            if (location == null) {
                Toast.makeText(this@MainActivity, R.string.location_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val now = System.currentTimeMillis()
            val pin = Pin(
                id = now,
                time = now,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                address = null,
                companies = null,
            )
            // Sofort speichern, damit der Pin auch ohne Internet erhalten bleibt.
            PinRepository.add(pin)
            map.moveTo(TrackPoint(pin.latitude, pin.longitude), animate = true)
            lookUp(pin)
        }
    }

    /** Sucht fehlende Adresse und Firmen, speichert sie und zeigt den Pin an. */
    private suspend fun lookUp(pin: Pin) {
        val progress = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.looking_up)
            .setCancelable(false)
            .show()
        try {
            val updated = coroutineScope {
                val address = async {
                    pin.address ?: NearbyLookup.address(applicationContext, pin.latitude, pin.longitude)
                }
                val companies = async {
                    pin.companies ?: NearbyLookup.companies(pin.latitude, pin.longitude)
                }
                pin.copy(address = address.await(), companies = companies.await())
            }
            PinRepository.update(updated)
        } finally {
            runCatching { progress.dismiss() }
        }
        showPinDetails(pin.id)
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val lastKnown = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        val now = System.currentTimeMillis()

        // Eine ganz frische, genaue Position (z. B. von der laufenden Aufzeichnung) direkt nehmen.
        lastKnown
            .filter { now - it.time < 15_000 && it.hasAccuracy() && it.accuracy <= 30f }
            .minByOrNull { it.accuracy }
            ?.let { return it }

        for (provider in providers) {
            val fresh = withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine { cont ->
                    val signal = CancellationSignal()
                    LocationManagerCompat.getCurrentLocation(
                        lm, provider, signal, ContextCompat.getMainExecutor(this@MainActivity)
                    ) { location -> if (cont.isActive) cont.resume(location) }
                    cont.invokeOnCancellation { signal.cancel() }
                }
            }
            if (fresh != null) return fresh
        }
        return lastKnown.maxByOrNull { it.time }
    }

    private fun showPinDetails(id: Long) {
        val pin = PinRepository.get(id) ?: return
        map.moveTo(TrackPoint(pin.latitude, pin.longitude), animate = true)
        followPosition = false

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.pin_title, formatDate(pin.time)))
            .setMessage(describe(pin))
            .setPositiveButton(R.string.close, null)
            .setNegativeButton(R.string.delete) { _, _ -> confirmDeletePin(pin.id) }
        if (!pin.isComplete) {
            builder.setNeutralButton(R.string.retry) { _, _ ->
                lifecycleScope.launch { lookUp(pin) }
            }
        }
        builder.show()
    }

    private fun describe(pin: Pin): String = buildString {
        appendLine(getString(R.string.pin_date, formatDate(pin.time)))
        appendLine(getString(R.string.pin_time, formatTime(pin.time)))
        appendLine(getString(R.string.pin_address, pin.address ?: getString(R.string.pin_address_unknown)))
        appendLine(
            getString(
                R.string.pin_coordinates,
                String.format(Locale.US, "%.6f, %.6f", pin.latitude, pin.longitude)
            )
        )
        pin.accuracyMeters?.let { appendLine(getString(R.string.pin_accuracy, it.toInt())) }
        appendLine()
        appendLine(getString(R.string.pin_companies, NearbyLookup.RADIUS_METERS))
        val companies = pin.companies
        when {
            companies == null -> append(getString(R.string.pin_companies_failed))
            companies.isEmpty() -> append(getString(R.string.pin_no_companies))
            else -> append(companies.joinToString("\n") {
                getString(R.string.pin_company_line, it.name, it.category, it.distanceMeters)
            })
        }
    }

    private fun confirmDeletePin(id: Long) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.pin_delete_question)
            .setPositiveButton(R.string.delete) { _, _ -> PinRepository.delete(id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPinList() {
        val pins = PinRepository.pins.value.sortedByDescending { it.time }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pins_title)
            .setPositiveButton(R.string.close, null)
        if (pins.isEmpty()) {
            builder.setMessage(R.string.pins_empty)
        } else {
            val labels = pins.map { pin ->
                val companies = pin.companies
                val summary = when {
                    companies == null -> pin.address ?: getString(R.string.pin_address_unknown)
                    companies.isEmpty() ->
                        "${pin.address ?: ""} · ${getString(R.string.pin_list_no_companies)}"
                    else ->
                        "${pin.address ?: ""} · ${getString(R.string.pin_list_companies, companies.size)}"
                }
                getString(R.string.pin_list_item, "${formatDate(pin.time)}, ${formatTime(pin.time)} Uhr", summary)
            }.toTypedArray<CharSequence>()
            builder.setItems(labels) { _, which -> showPinDetails(pins[which].id) }
            builder.setNeutralButton(R.string.pins_delete_all) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.pins_delete_all_question)
                    .setPositiveButton(R.string.delete) { _, _ -> PinRepository.deleteAll() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        builder.show()
    }

    private fun formatDate(time: Long): String =
        SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(time))

    private fun formatTime(time: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date(time))

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    companion object {
        private const val PREF_MAP_TYPE = "map_type"
    }
}
