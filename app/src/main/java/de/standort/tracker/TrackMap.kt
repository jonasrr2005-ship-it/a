package de.standort.tracker

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

/** Die Kartenarten, zwischen denen in der App umgeschaltet werden kann. */
enum class MapType {
    OSM,
    GOOGLE;

    fun create(): TrackMap = when (this) {
        OSM -> OsmTrackMap()
        GOOGLE -> GoogleTrackMap()
    }
}

/**
 * Die Karte, auf der Weg und Pins angezeigt werden – umgesetzt mit OpenStreetMap
 * ([OsmTrackMap]) oder Google Maps ([GoogleTrackMap]).
 */
interface TrackMap {

    /** Wird aufgerufen, wenn der Nutzer die Karte selbst verschiebt. */
    var onUserMoved: () -> Unit

    /** Wird aufgerufen, wenn ein Pin auf der Karte angetippt wird. */
    var onPinClicked: (Long) -> Unit

    /** Baut die Karte in [container] auf; [onReady] wird gerufen, sobald sie bereit ist. */
    fun attach(
        activity: AppCompatActivity,
        container: ViewGroup,
        savedInstanceState: Bundle?,
        onReady: () -> Unit,
    )

    /** Entfernt die Karte wieder aus dem Container (beim Umschalten der Kartenart). */
    fun detach(activity: AppCompatActivity, container: ViewGroup)

    fun showTrack(points: List<TrackPoint>)

    fun showPins(pins: List<Pin>)

    fun moveTo(point: TrackPoint, animate: Boolean)

    fun onResume() = Unit

    fun onPause() = Unit
}
