package de.standort.tracker

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

/**
 * Die Karte, auf der Weg und Pins angezeigt werden. Jede App-Version (OpenStreetMap
 * bzw. Google Maps) liefert über `MapFactory` ihre eigene Umsetzung.
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

    fun showTrack(points: List<TrackPoint>)

    fun showPins(pins: List<Pin>)

    fun moveTo(point: TrackPoint, animate: Boolean)

    fun onResume() = Unit

    fun onPause() = Unit
}
