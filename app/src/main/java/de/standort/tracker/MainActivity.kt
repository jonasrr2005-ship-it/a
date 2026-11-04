package de.standort.tracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.RoundCap
import de.standort.tracker.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var map: GoogleMap? = null
    private var track: Polyline? = null
    private var positionMarker: Marker? = null

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
        TrackRepository.init(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(::setupMap)

        binding.btnToggle.setOnClickListener {
            if (TrackRepository.isRecording.value) stopTracking() else requestPermissionsAndStart()
        }
        binding.btnClear.setOnClickListener { TrackRepository.clear() }
        binding.btnCenter.setOnClickListener {
            followPosition = true
            TrackRepository.points.value.lastOrNull()?.let {
                map?.animateCamera(CameraUpdateFactory.newLatLng(it))
            }
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

    private fun setupMap(googleMap: GoogleMap) {
        map = googleMap
        googleMap.uiSettings.isZoomControlsEnabled = false
        googleMap.uiSettings.isMyLocationButtonEnabled = false

        track = googleMap.addPolyline(
            PolylineOptions()
                .color(Color.rgb(229, 57, 53))
                .width(14f)
                .startCap(RoundCap())
                .endCap(RoundCap())
                .jointType(JointType.ROUND)
        )
        positionMarker = googleMap.addMarker(
            MarkerOptions()
                .position(LatLng(0.0, 0.0))
                .anchor(0.5f, 0.5f)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                .visible(false)
        )

        googleMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                followPosition = false
            }
        }

        val last = TrackRepository.points.value.lastOrNull()
        if (last != null) {
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(last, 17f))
        } else {
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(51.1657, 10.4515), 6f))
        }
        showTrack(TrackRepository.points.value)
    }

    private fun showTrack(points: List<LatLng>) {
        binding.stats.text = getString(
            R.string.stats,
            points.size,
            TrackRepository.formatDistance(TrackRepository.distanceMeters(points))
        )
        val googleMap = map ?: return
        track?.points = points
        val last = points.lastOrNull()
        positionMarker?.isVisible = last != null
        if (last != null) {
            positionMarker?.position = last
            if (followPosition) {
                val zoom = maxOf(googleMap.cameraPosition.zoom, 16f)
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(last, zoom))
            }
        }
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
}
