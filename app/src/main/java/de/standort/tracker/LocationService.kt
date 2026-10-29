package de.standort.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Vordergrund-Dienst, der den Standort auch bei ausgeschaltetem Bildschirm
 * oder im Hintergrund weiter aufzeichnet.
 */
class LocationService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private var lastAccepted: Location? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TrackRepository.init(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                }
            )
        } catch (e: Exception) {
            // z. B. wenn das System den Dienst im Hintergrund neu startet und
            // Android den Start eines Standort-Dienstes dort nicht erlaubt.
            stopTracking()
            return START_NOT_STICKY
        }
        startLocationUpdates()
        TrackRepository.setRecording(true)
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            stopTracking()
            return
        }
        locationManager.removeUpdates(this)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { locationManager.allProviders.contains(it) }
        for (provider in providers) {
            locationManager.requestLocationUpdates(
                provider, MIN_TIME_MS, MIN_DISTANCE_M, this, Looper.getMainLooper()
            )
        }
    }

    override fun onLocationChanged(location: Location) {
        // Ungenaue Netzwerk-Positionen verwerfen, wenn sie den Strich verfälschen würden.
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        val last = lastAccepted
        if (last != null) {
            // GPS bevorzugen: eine ungenauere Position kurz nach einer genauen ignorieren.
            val newer = location.time - last.time > 10_000
            val moreAccurate = location.accuracy <= last.accuracy
            if (!newer && !moreAccurate) return
            if (location.distanceTo(last) < MIN_DISTANCE_M) return
        }
        lastAccepted = location
        TrackRepository.add(location.latitude, location.longitude, location.time)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun stopTracking() {
        locationManager.removeUpdates(this)
        TrackRepository.setRecording(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        TrackRepository.setRecording(false)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val points = TrackRepository.points.value
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                getString(
                    R.string.notification_text,
                    points.size,
                    TrackRepository.formatDistance(TrackRepository.distanceMeters(points))
                )
            )
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "de.standort.tracker.STOP"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val MIN_TIME_MS = 2_000L
        private const val MIN_DISTANCE_M = 3f
        private const val MAX_ACCURACY_M = 50f
    }
}
