package de.zugreise.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Öffnet die Bildschirme wirklich, damit Abstürze beim Start schon im Build auffallen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScreensTest {

    private fun stop(name: String, lat: Double, lon: Double, arr: String?, dep: String?) =
        Stop(name, "id-$name", lat, lon, "Europe/Berlin", arr, arr, dep, dep, "5", false)

    @Test
    fun mainScreenOpensAddScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnAdd).performClick()
                val next = shadowOf(activity).nextStartedActivity
                assertEquals(AddLegActivity::class.java.name, next.component?.className)
            }
        }
    }

    @Test
    fun addScreenStarts() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val journey = JourneyStore(context).current()
        val intent = Intent(context, AddLegActivity::class.java).putExtra(AddLegActivity.EXTRA_JOURNEY, journey.id)
        ActivityScenario.launch<AddLegActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.widget.TextView>(R.id.station).text = "München"
            }
        }
    }

    @Test
    fun mainScreenShowsSavedTrains() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = JourneyStore(context)
        val journey = store.current()
        val leg = Leg(
            id = "1", tripId = "t1", name = "ICE 578", headsign = "Hamburg-Altona", operator = "DB Fernverkehr",
            mode = "HIGHSPEED_RAIL",
            stops = listOf(
                stop("München Hbf", 48.140, 11.558, null, "2026-11-05T09:24:00Z"),
                stop("Nürnberg Hbf", 49.445, 11.082, "2026-11-05T10:30:00Z", "2026-11-05T10:33:00Z"),
                stop("Hamburg Hbf", 53.553, 10.006, "2026-11-05T13:31:00Z", null),
            ),
            geometry = listOf(LatLon(48.140, 11.558), LatLon(49.445, 11.082), LatLon(53.553, 10.006)),
        )
        store.addLeg(journey.id, leg)
        // Umstieg prüfen: zweiter Bildschirm wird mit letzter Ankunft vorausgefüllt
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<android.widget.LinearLayout>(R.id.legList)
                assertEquals(1, list.childCount)
                list.getChildAt(0).performClick() // Halte-Dialog
            }
        }
        val intent = Intent(context, AddLegActivity::class.java).putExtra(AddLegActivity.EXTRA_JOURNEY, journey.id)
        ActivityScenario.launch<AddLegActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val text = activity.findViewById<android.widget.TextView>(R.id.station).text.toString()
                assertEquals("Hamburg Hbf", text)
            }
        }
    }

    @Test
    fun sharedTextShowsRecognizedTrains() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, ShareImportActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "05.11.2026\n10:24 München Hbf\nICE 578\n14:31 Hamburg Hbf\n14:45 Hamburg Hbf\nRE 5\n15:40 Stade")
        ActivityScenario.launch<ShareImportActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val legs = activity.findViewById<android.widget.LinearLayout>(R.id.legs)
                assertEquals(2, legs.childCount)
            }
        }
    }

    @Test
    fun importMenuOpens() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnImport).performClick()
            }
        }
    }
}
