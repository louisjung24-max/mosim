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
import com.hiddenframe.mosim.rules.AnomalyDetector
import com.hiddenframe.mosim.vehicle.CarApiSignalSource
import com.hiddenframe.mosim.vehicle.PleosSignalSource
import com.hiddenframe.mosim.vehicle.SimulatedSignalSource
import com.hiddenframe.mosim.vehicle.VehicleEvent
import com.hiddenframe.mosim.vehicle.VehicleSignalSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * 시동이 켜져 있는 동안 차량 신호를 듣고, 판정하고, 말하고, 허용된 신호만 자녀에게 보냅니다.
 */
class DriveMonitorService : LifecycleService() {

    private lateinit var sources: List<VehicleSignalSource>
    private lateinit var pleos: PleosSignalSource
    private lateinit var carApi: CarApiSignalSource
    private lateinit var detector: AnomalyDetector

    override fun onCreate() {
        super.onCreate()
        val app = application as MosimApp
        detector = AnomalyDetector(app.baseline)
        pleos = PleosSignalSource(this)
        carApi = CarApiSignalSource(this)
        // 우선순위: Pleos Vehicle SDK → (없으면) AAOS 기본 차량 속성. 시뮬레이터는 시연용으로 항상 켜 둡니다.
        sources = listOf(SimulatedSignalSource, pleos, carApi)
        startForeground(NOTIF_ID, buildNotification())

        lifecycleScope.launch {
            merge(*sources.map { it.events }.toTypedArray()).collect { handle(it) }
        }
        SimulatedSignalSource.start()
        pleos.start()
        carApi.start() // 차속은 처음부터
        lifecycleScope.launch {
            delay(PLEOS_PROBE_MS)
            if (!pleos.connected) carApi.speedOnly = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_PERMISSIONS_GRANTED) {
            pleos.startLocation()
            carApi.stop(); carApi.start() // 차속 권한을 받은 뒤 다시 등록
        }
        return START_STICKY
    }

    // 운행 상태 추적: 장시간 정차(차속 2 km/h 미만 20분), 이번 운행 거리(차속 적분), 정차 시 말하기
    private var driving = false
    private var stoppedSince = 0L
    private var idleReported = false
    private var lastSpeedKmh = 0f
    private var lastSpeedAt = 0L
    private var tripKm = 0f
    private var reportedKm = 0f
    private val voiceAtStop = ArrayDeque<String>()

    private suspend fun track(e: VehicleEvent) {
        when (e) {
            is VehicleEvent.Ignition -> {
                driving = e.on; stoppedSince = 0L; idleReported = false
                tripKm = 0f; reportedKm = 0f; lastSpeedAt = 0L
                if (!e.on) flushVoiceAtStop()
            }
            is VehicleEvent.Speed -> if (driving) {
                val now = System.currentTimeMillis()
                if (lastSpeedAt > 0) {
                    val h = (now - lastSpeedAt) / 3_600_000f
                    if (h in 0f..(10f / 3600f)) tripKm += (lastSpeedKmh + e.kmh) / 2f * h
                }
                lastSpeedKmh = e.kmh; lastSpeedAt = now
                if (tripKm - reportedKm >= 0.5f) { reportedKm = tripKm; handle(VehicleEvent.Odometer(tripKm)) }
                if (e.kmh < STOPPED_KMH) {
                    flushVoiceAtStop()
                    if (stoppedSince == 0L) stoppedSince = now
                    val minutes = ((now - stoppedSince) / 60_000L).toInt()
                    if (minutes >= AnomalyDetector.LONG_STOP_MIN && !idleReported) {
                        idleReported = true
                        handle(VehicleEvent.IdleStop(minutes))
                    }
                } else { stoppedSince = 0L; idleReported = false }
            }
            else -> {}
        }
    }

    private fun say(text: String) {
        val app = application as MosimApp
        app.voice.speak(text); app.state.onVoice(text)
    }

    private fun flushVoiceAtStop() { while (voiceAtStop.isNotEmpty()) say(voiceAtStop.removeFirst()) }

    private suspend fun handle(e: VehicleEvent) {
        val app = application as MosimApp
        if (e !is VehicleEvent.IdleStop && e !is VehicleEvent.Odometer) track(e)
        app.state.onEvent(e)
        when (e) {
            is VehicleEvent.Ignition -> if (e.on) app.weekly.addTrip(night = (e.hourOfDay ?: 12).let { it >= AnomalyDetector.NIGHT_FROM || it < AnomalyDetector.NIGHT_UNTIL })
            is VehicleEvent.HardBrake -> app.weekly.addHardBrake()
            else -> {}
        }
        val r = detector.onEvent(e)
        r.voice?.let { v ->
            // 운전 중 말 아끼기: 급하지 않은 말은 다음에 멈췄을 때
            if (v.atStop && driving && lastSpeedKmh >= STOPPED_KMH) voiceAtStop.addLast(v.text) else say(v.text)
        }
        r.anomaly?.let { a ->
            // 부모님이 켠 항목만, 차 화면에 먼저 보여 드린 뒤(30초) 보냅니다.
            if (app.share.allows(a)) app.gate.submit(a) else app.state.onInCarOnly(a)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "모심 동승", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mosim)
            .setContentTitle("모심이 함께 타고 있어요")
            .setContentText("부모님이 고르고 확인한 신호만 가족에게 전합니다")
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
        private const val PLEOS_PROBE_MS = 15000L
        private const val STOPPED_KMH = 2f
        const val ACTION_PERMISSIONS_GRANTED = "com.hiddenframe.mosim.PERMISSIONS_GRANTED"
        fun start(context: Context, action: String? = null) {
            val i = Intent(context, DriveMonitorService::class.java).setAction(action)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
    }
}
