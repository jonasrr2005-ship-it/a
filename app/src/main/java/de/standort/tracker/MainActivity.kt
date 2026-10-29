package de.standort.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.standort.tracker.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var track: Polyline
    private lateinit var positionMarker: Marker

    /** Karte folgt der aktuellen Position, bis der Nutzer sie selbst verschiebt. */
    private var followPosition = true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startTracking()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        TrackRepository.init(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupMap()

        binding.btnToggle.setOnClickListener {
            if (TrackRepository.isRecording.value) stopTracking() else requestPermissionsAndStart()
        }
        binding.btnClear.setOnClickListener { TrackRepository.clear() }
        binding.btnCenter.setOnClickListener {
            followPosition = true
            TrackRepository.points.value.lastOrNull()?.let { binding.map.controller.animateTo(it) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TrackRepository.points.collect(::showTrack) }
                launch {
                    TrackRepository.isRecording.collect { recording ->
                        binding.btnToggle.setText(if (recording) R.string.stop else R.string.start)
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        val map = binding.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(17.0)
        map.controller.setCenter(GeoPoint(51.1657, 10.4515)) // Mitte Deutschlands

        track = Polyline(map).apply {
            outlinePaint.color = Color.rgb(229, 57, 53)
            outlinePaint.strokeWidth = 12f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
        }
        positionMarker = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_position)
            setInfoWindow(null)
        }
        map.overlays.add(track)
        map.overlays.add(positionMarker)

        map.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE) followPosition = false
            false
        }

        TrackRepository.points.value.lastOrNull()?.let { map.controller.setCenter(it) }
    }

    private fun showTrack(points: List<GeoPoint>) {
        track.setPoints(points)
        val last = points.lastOrNull()
        positionMarker.isEnabled = last != null
        if (last != null) {
            positionMarker.position = last
            if (followPosition) binding.map.controller.animateTo(last)
        }
        binding.stats.text = getString(
            R.string.stats,
            points.size,
            TrackRepository.formatDistance(TrackRepository.distanceMeters(points))
        )
        binding.map.invalidate()
    }

    private fun requestPermissionsAndStart() {
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
        if (missing.isEmpty()) startTracking() else permissionLauncher.launch(missing.toTypedArray())
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

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }
}
