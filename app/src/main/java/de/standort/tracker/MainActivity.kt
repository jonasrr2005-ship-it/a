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
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
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
import kotlinx.coroutines.flow.combine
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

    /** Die Strecke, die auf der Karte gezeigt wird (sofern nicht gerade aufgezeichnet wird). */
    private var selectedTrackId: Long? = null

    /** Pins, deren Adresse gerade gesucht wird. */
    private val addressLookups = mutableSetOf<Long>()

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
        TrackRepository.adoptOrphanPins()
        selectedTrackId = savedInstanceState?.getLong(STATE_TRACK, -1L)?.takeIf { it != -1L }

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
            if (TrackRepository.activeTrackId.value != null) stopTracking()
            else withLocationPermission(::startTracking)
        }
        binding.btnTracks.setOnClickListener { showTrackList() }
        binding.btnCenter.setOnClickListener {
            followPosition = true
            displayedTrack()?.points?.lastOrNull()?.let { map.moveTo(it, animate = true) }
        }
        binding.btnPin.setOnClickListener { withLocationPermission(::setPin) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    TrackRepository.tracks,
                    TrackRepository.activeTrackId,
                    PinRepository.pins
                ) { tracks, active, _ -> tracks to active }.collect { (tracks, active) ->
                    binding.btnTracks.text = getString(R.string.track_list, tracks.size)
                    binding.btnToggle.setText(if (active != null) R.string.stop else R.string.start)
                    showDisplayedTrack()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedTrackId?.let { outState.putLong(STATE_TRACK, it) }
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
            displayedTrack()?.points?.lastOrNull()?.let { map.moveTo(it, animate = false) }
            showDisplayedTrack()
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

    /** Laufende Aufnahme, sonst die ausgewählte, sonst die neueste Strecke. */
    private fun displayedTrack(): Track? {
        TrackRepository.activeTrackId.value?.let { id -> TrackRepository.get(id)?.let { return it } }
        selectedTrackId?.let { id -> TrackRepository.get(id)?.let { return it } }
        return TrackRepository.tracks.value.maxByOrNull { it.startTime }
    }

    private fun showDisplayedTrack() {
        val track = displayedTrack()
        val recording = track != null && track.id == TrackRepository.activeTrackId.value
        binding.stats.text = when {
            track == null -> getString(R.string.stats_none)
            recording -> getString(
                R.string.stats_recording,
                formatTime(track.startTime),
                TrackRepository.formatDistance(TrackRepository.distanceMeters(track.points))
            )
            else -> getString(
                R.string.stats_track,
                formatDay(track.startTime),
                TrackRepository.formatDistance(TrackRepository.distanceMeters(track.points))
            )
        }
        if (!mapReady) return
        val points = track?.points.orEmpty()
        map.showTrack(points)
        // Auf der Karte nur die Pins der angezeigten Strecke.
        map.showPins(track?.let { PinRepository.forTrack(it.id) }.orEmpty())
        val last = points.lastOrNull()
        if (recording && last != null && followPosition) map.moveTo(last, animate = true)
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
        selectedTrackId = null
        ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
    }

    private fun stopTracking() {
        startService(Intent(this, LocationService::class.java).setAction(LocationService.ACTION_STOP))
    }

    // ---- Strecken ----

    private fun showTrackList() {
        val tracks = TrackRepository.tracks.value.sortedByDescending { it.startTime }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tracks_title)
            .setPositiveButton(R.string.close, null)
        if (tracks.isEmpty()) {
            builder.setMessage(R.string.tracks_empty)
        } else {
            val activeId = TrackRepository.activeTrackId.value
            val labels = tracks.map { track ->
                getString(
                    R.string.track_list_item,
                    formatDay(track.startTime),
                    formatTime(track.startTime),
                    if (track.id == activeId) getString(R.string.track_running)
                    else formatTime(track.displayEndTime),
                    TrackRepository.formatDistance(TrackRepository.distanceMeters(track.points)),
                    PinRepository.forTrack(track.id).size
                ) as CharSequence
            }.toTypedArray()
            builder.setItems(labels) { _, which -> showTrackDetails(tracks[which].id) }
        }
        builder.show()
    }

    private fun showTrackDetails(id: Long) {
        val track = TrackRepository.get(id) ?: return
        val running = track.id == TrackRepository.activeTrackId.value
        val deliveries = PinRepository.forTrack(id).sortedBy { it.time }
        val message = buildString {
            appendLine(getString(R.string.track_start, formatTime(track.startTime)))
            if (!running) appendLine(getString(R.string.track_end, formatTime(track.displayEndTime)))
            appendLine(
                getString(
                    R.string.track_duration,
                    formatDuration((if (running) System.currentTimeMillis() else track.displayEndTime) - track.startTime)
                )
            )
            appendLine(
                getString(
                    R.string.track_distance,
                    TrackRepository.formatDistance(TrackRepository.distanceMeters(track.points))
                )
            )
            appendLine()
            if (deliveries.isEmpty()) {
                append(getString(R.string.track_no_deliveries))
            } else {
                appendLine(getString(R.string.track_deliveries, deliveries.size))
                append(deliveries.joinToString("\n") {
                    getString(
                        R.string.track_delivery_line,
                        formatTime(it.time),
                        it.delivery.ifBlank { getString(R.string.delivery_empty) },
                        it.address ?: getString(R.string.pin_address_unknown)
                    )
                })
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(formatDay(track.startTime))
            .setMessage(message)
            .setPositiveButton(R.string.track_show) { _, _ ->
                selectedTrackId = track.id
                followPosition = false
                showDisplayedTrack()
                track.points.lastOrNull()?.let { map.moveTo(it, animate = true) }
            }
            .setNegativeButton(R.string.delete) { _, _ ->
                if (running) {
                    Toast.makeText(this, R.string.track_delete_running, Toast.LENGTH_LONG).show()
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(formatDay(track.startTime))
                        .setMessage(getString(R.string.track_delete_question, deliveries.size))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            if (selectedTrackId == track.id) selectedTrackId = null
                            TrackRepository.deleteWithPins(track.id)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .setNeutralButton(R.string.close, null)
            .show()
    }

    // ---- Pins / Lieferungen ----

    private fun setPin() {
        val trackId = TrackRepository.activeTrackId.value
        if (trackId == null) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.pin_needs_recording)
                .setPositiveButton(R.string.start) { _, _ -> startTracking() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
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
                delivery = "",
                trackId = trackId,
            )
            // Sofort speichern, damit der Pin auch ohne Internet erhalten bleibt.
            PinRepository.add(pin)
            map.moveTo(TrackPoint(pin.latitude, pin.longitude), animate = true)
            lookUpAddress(pin)
            askDelivery(pin.id, isNew = true)
        }
    }

    /** Sucht im Hintergrund die Adresse eines Pins und speichert sie. */
    private fun lookUpAddress(pin: Pin) {
        if (!addressLookups.add(pin.id)) return
        lifecycleScope.launch {
            try {
                AddressLookup.address(applicationContext, pin.latitude, pin.longitude)
                    ?.let { PinRepository.setAddress(pin.id, it) }
            } finally {
                addressLookups.remove(pin.id)
            }
        }
    }

    /** Fragt, was abgeliefert wurde. Ein neuer Pin muss gespeichert werden. */
    private fun askDelivery(id: Long, isNew: Boolean) {
        val pin = PinRepository.get(id) ?: return
        val padding = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = getString(R.string.delivery_hint)
            setText(pin.delivery)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delivery_question)
            .setView(container)
            .setCancelable(!isNew)
            .setPositiveButton(R.string.save) { _, _ ->
                PinRepository.setDelivery(id, input.text.toString().trim())
                showPinDetails(id)
            }
        if (!isNew) builder.setNegativeButton(R.string.cancel, null)
        val dialog = builder.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
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
        if (pin.address == null) lookUpAddress(pin)
        map.moveTo(TrackPoint(pin.latitude, pin.longitude), animate = true)
        followPosition = false

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pin_title)
            .setMessage(describe(pin))
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.edit) { _, _ -> askDelivery(pin.id, isNew = false) }
            .show()
    }

    private fun describe(pin: Pin): String = buildString {
        appendLine(getString(R.string.pin_date, formatDay(pin.time)))
        appendLine(getString(R.string.pin_time, formatTimeSeconds(pin.time)))
        appendLine(
            getString(R.string.pin_delivery, pin.delivery.ifBlank { getString(R.string.delivery_empty) })
        )
        appendLine(getString(R.string.pin_address, addressText(pin)))
        appendLine(
            getString(
                R.string.pin_coordinates,
                String.format(Locale.US, "%.6f, %.6f", pin.latitude, pin.longitude)
            )
        )
        pin.accuracyMeters?.let { append(getString(R.string.pin_accuracy, it.toInt())) }
    }

    private fun addressText(pin: Pin): String = pin.address
        ?: getString(if (pin.id in addressLookups) R.string.pin_address_loading else R.string.pin_address_unknown)

    /** z. B. „Mittwoch, 04.11.2026“ */
    private fun formatDay(time: Long): String =
        SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMANY).format(Date(time))

    private fun formatTime(time: Long): String =
        SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(time))

    private fun formatTimeSeconds(time: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date(time))

    private fun formatDuration(millis: Long): String {
        val minutes = (millis / 60_000).coerceAtLeast(0)
        return if (minutes < 60) "$minutes Min." else "${minutes / 60} Std. ${minutes % 60} Min."
    }

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
        private const val STATE_TRACK = "selected_track"
    }
}
