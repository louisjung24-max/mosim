package com.hiddenframe.mosim.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.hiddenframe.mosim.MosimApp
import com.hiddenframe.mosim.R
import com.hiddenframe.mosim.alert.AlertSender
import com.hiddenframe.mosim.rules.AnomalyDetector
import com.hiddenframe.mosim.vehicle.CarApiSignalSource
import com.hiddenframe.mosim.vehicle.SimulatedSignalSource
import com.hiddenframe.mosim.vehicle.VehicleEvent
import com.hiddenframe.mosim.vehicle.VehicleSignalSource
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * 시동이 켜져 있는 동안 차량 신호를 듣고, 판정하고, 말하고, 허용된 신호만 자녀에게 보냅니다.
 */
class DriveMonitorService : LifecycleService() {

    private lateinit var sources: List<VehicleSignalSource>
    private lateinit var detector: AnomalyDetector

    override fun onCreate() {
        super.onCreate()
        val app = application as MosimApp
        detector = AnomalyDetector(app.baseline)
        sources = listOf(SimulatedSignalSource, CarApiSignalSource(this))
        startForeground(NOTIF_ID, buildNotification())
        sources.forEach { it.start() }

        lifecycleScope.launch {
            merge(*sources.map { it.events }.toTypedArray()).collect { handle(it) }
        }
    }

    private suspend fun handle(e: VehicleEvent) {
        val app = application as MosimApp
        app.state.onEvent(e)
        when (e) {
            is VehicleEvent.Ignition -> if (e.on) app.weekly.addTrip(night = (e.hourOfDay ?: 12).let { it >= AnomalyDetector.NIGHT_FROM || it < AnomalyDetector.NIGHT_UNTIL })
            is VehicleEvent.HardBrake -> app.weekly.addHardBrake()
            else -> {}
        }
        val r = detector.onEvent(e)
        r.voice?.let { app.voice.speak(it.text); app.state.onVoice(it.text) }
        r.anomaly?.let { a ->
            app.weekly.addAnomaly()
            val allowed = app.share.allows(a)
            app.state.onAnomaly(a, allowed)
            if (allowed) AlertSender.send(app.share.familyCode, a)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "모심 동승", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mosim)
            .setContentTitle("모심이 함께 타고 있어요")
            .setContentText("부모님이 허용한 신호만 가족에게 전합니다")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        sources.forEach { it.stop() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "mosim_drive"
        private const val NOTIF_ID = 1001
        fun start(context: Context) {
            val i = Intent(context, DriveMonitorService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
    }
}
