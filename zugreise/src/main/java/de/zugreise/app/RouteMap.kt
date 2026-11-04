package de.zugreise.app

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Zeichnet die Zugabschnitte einer Reise auf eine OpenStreetMap-Karte. */
class RouteMap(private val map: MapView) {

    init {
        Configuration.getInstance().userAgentValue = map.context.packageName
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isTilesScaledToDpi = true
        map.minZoomLevel = 3.0
        map.controller.setZoom(6.0)
        map.controller.setCenter(GeoPoint(51.1657, 10.4515)) // Mitte Deutschlands
    }

    private val density = map.resources.displayMetrics.density

    fun show(legs: List<Leg>) {
        map.overlays.clear()
        // erst alle Linien, dann alle Halte, damit die Punkte oben liegen
        legs.forEachIndexed { i, leg ->
            val color = colorFor(i)
            val points = (leg.geometry.takeIf { it.size >= 2 } ?: leg.stops.map { it.position })
                .map { GeoPoint(it.lat, it.lon) }
            map.overlays += Polyline(map).apply {
                setPoints(points)
                outlinePaint.color = Color.WHITE
                outlinePaint.strokeWidth = 11f * density / 2
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                infoWindow = null
            }
            map.overlays += Polyline(map).apply {
                setPoints(points)
                outlinePaint.color = color
                outlinePaint.strokeWidth = 7f * density / 2
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                title = "${leg.name} → ${leg.headsign ?: leg.to.name}"
                snippet = "${leg.from.name} → ${leg.to.name}"
            }
        }
        legs.forEachIndexed { i, leg ->
            val color = colorFor(i)
            leg.stops.forEachIndexed { k, stop ->
                val endpoint = k == 0 || k == leg.stops.lastIndex
                map.overlays += Marker(map).apply {
                    position = GeoPoint(stop.lat, stop.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = dot(color, if (endpoint) 18 else 10)
                    title = stop.name
                    snippet = stopSnippet(leg, k)
                    subDescription = leg.name
                }
            }
        }
        map.invalidate()
    }

    private fun stopSnippet(leg: Leg, k: Int): String {
        val s = leg.stops[k]
        val parts = mutableListOf<String>()
        if (k > 0) parts += "an " + Times.withDelay(s.scheduledArrival, s.arrival, s.tz)
        if (k < leg.stops.lastIndex) parts += "ab " + Times.withDelay(s.scheduledDeparture, s.departure, s.tz)
        s.track?.let { parts += "Gl. $it" }
        return parts.joinToString(" · ")
    }

    private fun dot(color: Int, sizeDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
        setStroke((3 * density).toInt(), color)
        val px = (sizeDp * density).toInt()
        setSize(px, px)
    }

    fun zoomTo(legs: List<Leg>) {
        val points = legs.flatMap { leg -> leg.geometry + leg.stops.map { it.position } }
        if (points.isEmpty()) return
        val box = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lon) })
        map.post {
            if (box.latitudeSpan < 0.01 && box.longitudeSpanWithDateLine < 0.01) {
                map.controller.setZoom(14.0)
                map.controller.setCenter(box.centerWithDateLine)
            } else {
                map.zoomToBoundingBox(box.increaseByScale(1.25f), false, (24 * density).toInt())
            }
        }
    }

    fun onResume() = map.onResume()
    fun onPause() = map.onPause()

    companion object {
        private val PALETTE = intArrayOf(
            Color.rgb(198, 40, 40),   // rot
            Color.rgb(21, 101, 192),  // blau
            Color.rgb(46, 125, 50),   // grün
            Color.rgb(239, 108, 0),   // orange
            Color.rgb(106, 27, 154),  // lila
            Color.rgb(0, 131, 143),   // petrol
            Color.rgb(173, 20, 87),   // pink
            Color.rgb(93, 64, 55),    // braun
        )

        fun colorFor(index: Int) = PALETTE[index % PALETTE.size]
    }
}
