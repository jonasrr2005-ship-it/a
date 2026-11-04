package de.standort.tracker

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
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

/** Karte mit Google Maps (benötigt einen API-Schlüssel, siehe README). */
class GoogleTrackMap : TrackMap {

    override var onUserMoved: () -> Unit = {}
    override var onPinClicked: (Long) -> Unit = {}

    private var map: GoogleMap? = null
    private var track: Polyline? = null
    private var positionMarker: Marker? = null
    private val pinMarkers = mutableListOf<Marker>()

    override fun attach(
        activity: AppCompatActivity,
        container: ViewGroup,
        savedInstanceState: Bundle?,
        onReady: () -> Unit,
    ) {
        val fm = activity.supportFragmentManager
        val fragment = fm.findFragmentByTag(TAG) as? SupportMapFragment
            ?: SupportMapFragment.newInstance().also {
                fm.beginTransaction().replace(container.id, it, TAG).commitNow()
            }
        fragment.getMapAsync { googleMap ->
            map = googleMap
            googleMap.uiSettings.isZoomControlsEnabled = false
            googleMap.uiSettings.isMyLocationButtonEnabled = false
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(51.1657, 10.4515), 6f))

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
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) onUserMoved()
            }
            googleMap.setOnMarkerClickListener { marker ->
                val id = marker.tag as? Long ?: return@setOnMarkerClickListener false
                onPinClicked(id)
                true
            }
            onReady()
        }
    }

    override fun detach(activity: AppCompatActivity, container: ViewGroup) {
        val fm = activity.supportFragmentManager
        fm.findFragmentByTag(TAG)?.let { fm.beginTransaction().remove(it).commitNow() }
        map = null
    }

    override fun showTrack(points: List<TrackPoint>) {
        track?.points = points.map { LatLng(it.latitude, it.longitude) }
        val last = points.lastOrNull()
        positionMarker?.isVisible = last != null
        if (last != null) positionMarker?.position = LatLng(last.latitude, last.longitude)
    }

    override fun showPins(pins: List<Pin>) {
        val googleMap = map ?: return
        pinMarkers.forEach { it.remove() }
        pinMarkers.clear()
        for (pin in pins) {
            googleMap.addMarker(
                MarkerOptions().position(LatLng(pin.latitude, pin.longitude))
            )?.let {
                it.tag = pin.id
                pinMarkers += it
            }
        }
    }

    override fun moveTo(point: TrackPoint, animate: Boolean) {
        val googleMap = map ?: return
        val zoom = maxOf(googleMap.cameraPosition.zoom, 16f)
        val update = CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), zoom)
        if (animate) googleMap.animateCamera(update) else googleMap.moveCamera(update)
    }

    companion object {
        private const val TAG = "google_map"
    }
}
