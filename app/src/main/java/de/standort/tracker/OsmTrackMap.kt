package de.standort.tracker

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Karte mit OpenStreetMap (osmdroid), kein API-Schlüssel nötig. */
class OsmTrackMap : TrackMap {

    override var onUserMoved: () -> Unit = {}
    override var onPinClicked: (Long) -> Unit = {}

    private lateinit var map: MapView
    private lateinit var track: Polyline
    private lateinit var positionMarker: Marker
    private val pinMarkers = mutableListOf<Marker>()

    @SuppressLint("ClickableViewAccessibility")
    override fun attach(
        activity: AppCompatActivity,
        container: ViewGroup,
        savedInstanceState: Bundle?,
        onReady: () -> Unit,
    ) {
        Configuration.getInstance().userAgentValue = activity.packageName
        map = MapView(activity)
        container.addView(
            map,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(6.0)
        map.controller.setCenter(GeoPoint(51.1657, 10.4515)) // Mitte Deutschlands

        track = Polyline(map).apply {
            outlinePaint.color = Color.rgb(229, 57, 53)
            outlinePaint.strokeWidth = 12f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
        }
        positionMarker = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(activity, R.drawable.ic_position)
            setInfoWindow(null)
            isEnabled = false
        }
        map.overlays.add(track)
        map.overlays.add(positionMarker)

        map.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE) onUserMoved()
            false
        }
        onReady()
    }

    override fun detach(activity: AppCompatActivity, container: ViewGroup) {
        map.onPause()
        container.removeView(map)
        map.onDetach()
    }

    override fun showTrack(points: List<TrackPoint>) {
        track.setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
        val last = points.lastOrNull()
        positionMarker.isEnabled = last != null
        if (last != null) positionMarker.position = GeoPoint(last.latitude, last.longitude)
        map.invalidate()
    }

    override fun showPins(pins: List<Pin>) {
        map.overlays.removeAll(pinMarkers)
        pinMarkers.clear()
        for (pin in pins) {
            pinMarkers += Marker(map).apply {
                position = GeoPoint(pin.latitude, pin.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ ->
                    onPinClicked(pin.id)
                    true
                }
            }
        }
        map.overlays.addAll(pinMarkers)
        map.invalidate()
    }

    override fun moveTo(point: TrackPoint, animate: Boolean) {
        val target = GeoPoint(point.latitude, point.longitude)
        if (map.zoomLevelDouble < 16.0) map.controller.setZoom(17.0)
        if (animate) map.controller.animateTo(target) else map.controller.setCenter(target)
    }

    override fun onResume() = map.onResume()

    override fun onPause() = map.onPause()
}
