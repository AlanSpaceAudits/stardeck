package com.spaceaudits.stardeck

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Phone GNSS state. The telescope has no receiver of its own. */
class Gps(context: Context) {
    var fix by mutableStateOf<Location?>(null)
    var satellitesUsed by mutableIntStateOf(0)
    var satellitesSeen by mutableIntStateOf(0)

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val executor = context.mainExecutor
    private val listener = LocationListener { fix = it }
    private val status = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            satellitesSeen = s.satelliteCount
            satellitesUsed = (0 until s.satelliteCount).count { s.usedInFix(it) }
        }
    }

    @SuppressLint("MissingPermission") // caller starts this only after the permission is granted
    fun start() {
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
        manager.registerGnssStatusCallback(executor, status)
    }

    fun stop() {
        manager.removeUpdates(listener)
        manager.unregisterGnssStatusCallback(status)
    }

    /** A fix older than this is shown as lost. */
    fun locked(): Boolean = fix?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 5_000_000_000L } ?: false

    /** UTC now in ms: GPS time of the last fix advanced by the monotonic clock, else the phone clock. */
    fun utcMillis(): Long = fix?.let { it.time + (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
        ?: System.currentTimeMillis()
}
