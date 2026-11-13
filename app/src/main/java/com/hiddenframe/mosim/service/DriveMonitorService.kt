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
import com.hiddenframe.mosim.alert.Delivery
import com.hiddenframe.mosim.data.Trip
import com.hiddenframe.mosim.data.TripLog
import java.util.Calendar
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
 * 운행 중 차량 신호를 듣고, 동승 안내를 말하고, 운행 기록(거리·밤·급제동·앞뒤로 고쳐 대기 등)을 차 안에 쌓습니다.
 * 매달 첫 출발 때 지난달 요약을 가족에게 보내고(동의한 경우), 보낸 뒤 차가 알려 드립니다.
 */
class DriveMonitorService : LifecycleService() {

    private lateinit var sources: List<VehicleSignalSource>
    private lateinit var pleos: PleosSignalSource
    private lateinit var carApi: CarApiSignalSource
    private lateinit var detector: AnomalyDetector

    override fun onCreate() {
        super.onCreate()
        val app = application as MosimApp
        detector = AnomalyDetector()
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

    // 운행 상태 추적: 거리(차속 적분), 고속 주행 시간, 급제동, 장시간 정차, 정차 시 말하기
    private var driving = false
    private var tripStart = 0L
    private var tripNight = false
    private var tripBrakes = 0
    private var tripFlips = 0
    private var tripPanic = 0
    private var reverse: Boolean? = null
    private var hardAccelAt = 0L
    private var fastMin = 0f
    private var stoppedSince = 0L
    private var idleReported = false
    private var lastSpeedKmh = 0f
    private var lastSpeedAt = 0L
    private var tripKm = 0f
    private var sdkTripKm = 0f
    private val voiceAtStop = ArrayDeque<String>()

    private suspend fun track(e: VehicleEvent) {
        when (e) {
            is VehicleEvent.Ignition -> if (e.on) onTripStart(e) else onTripEnd()
            is VehicleEvent.HardBrake -> if (driving) {
                tripBrakes++
                // 저속 급가속 직후(4초 안) 급제동: 페달을 헷갈렸을 수 있는 순간으로 셉니다.
                if (hardAccelAt > 0 && System.currentTimeMillis() - hardAccelAt <= PANIC_WINDOW_MS) { tripPanic++; hardAccelAt = 0L }
            }
            is VehicleEvent.HardAccel -> if (driving) hardAccelAt = System.currentTimeMillis()
            is VehicleEvent.Direction -> if (driving) {
                if (reverse != null && reverse != e.reverse) tripFlips++
                reverse = e.reverse
            }
            is VehicleEvent.Odometer -> if (driving && e.km > sdkTripKm) sdkTripKm = e.km
            is VehicleEvent.Speed -> if (driving) {
                val now = System.currentTimeMillis()
                if (lastSpeedAt > 0) {
                    val sec = (now - lastSpeedAt) / 1000f
                    if (sec in 0f..10f) {
                        tripKm += (lastSpeedKmh + e.kmh) / 2f * sec / 3600f
                        if (lastSpeedKmh >= AnomalyDetector.FAST_KMH && e.kmh >= AnomalyDetector.FAST_KMH) fastMin += sec / 60f
                    }
                }
                lastSpeedKmh = e.kmh; lastSpeedAt = now
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

    private fun onTripStart(e: VehicleEvent.Ignition) {
        driving = true
        tripStart = System.currentTimeMillis()
        tripNight = AnomalyDetector.isNight(e.hourOfDay ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        tripBrakes = 0; tripFlips = 0; tripPanic = 0; reverse = null; hardAccelAt = 0L; fastMin = 0f; tripKm = 0f; sdkTripKm = 0f
        stoppedSince = 0L; idleReported = false; lastSpeedAt = 0L; lastSpeedKmh = 0f
        android.util.Log.i("MosimDemo", "trip start")
        maybeSendMonthlySummary()
    }

    private fun onTripEnd() {
        if (!driving) return
        driving = false
        flushVoiceAtStop()
        val app = application as MosimApp
        val km = maxOf(tripKm, sdkTripKm)
        app.trips.add(Trip(tripStart, km, tripNight, fastMin, tripBrakes, tripFlips, tripPanic))
        android.util.Log.i("MosimDemo", "trip end km=%.1f brakes=%d flips=%d panic=%d".format(km, tripBrakes, tripFlips, tripPanic))
        app.state.note("이번 운행 기록: %.1fkm · 급제동 %d번 · 앞뒤로 고쳐 대기 %d번".format(km, tripBrakes, tripFlips))
        app.state.touch()
    }

    /** 매달 첫 출발 때 지난달 요약을 한 번 보내고, 보낸 뒤 알려 드립니다. */
    private fun maybeSendMonthlySummary() {
        val app = application as MosimApp
        val thisMonth = TripLog.monthKey()
        if (!app.share.monthlyOn || app.trips.lastSentMonth == thisMonth) return
        val m = app.trips.lastMonth()
        if (m.trips == 0) { app.trips.lastSentMonth = thisMonth; return }
        lifecycleScope.launch {
            val r = AlertSender.sendSummary(app.share.familyCode, m)
            app.state.onDelivery("${m.label} 운전 요약", r)
            if (r == Delivery.SENT) {
                app.trips.lastSentMonth = thisMonth
                app.weekly.addAnomaly()
                say("${m.label} 운전 요약을 가족에게 보내 드렸어요. ${m.spoken()}")
            } else if (r == Delivery.NOT_CONFIGURED) {
                app.trips.lastSentMonth = thisMonth
            }
        }
    }

    private fun say(text: String) {
        val app = application as MosimApp
        app.voice.speak(text); app.state.onVoice(text)
    }

    private fun flushVoiceAtStop() { while (voiceAtStop.isNotEmpty()) say(voiceAtStop.removeFirst()) }

    private suspend fun handle(e: VehicleEvent) {
        val app = application as MosimApp
        if (e !is VehicleEvent.IdleStop) track(e)
        app.state.onEvent(e)
        if (e is VehicleEvent.Ignition && e.on) app.weekly.addTrip(night = tripNight)
        val r = detector.onEvent(e)
        r.voice?.let { v ->
            // 운전 중 말 아끼기: 급하지 않은 말은 다음에 멈췄을 때
            if (v.atStop && driving && lastSpeedKmh >= STOPPED_KMH) voiceAtStop.addLast(v.text) else say(v.text)
        }
        r.anomaly?.let { a ->
            if (app.share.allows(a)) lifecycleScope.launch {
                val d = AlertSender.send(app.share.familyCode, a)
                app.state.onDelivery(a.title, d)
                if (d == Delivery.SENT) { app.weekly.addAnomaly(); say("${a.title} 소식을 가족에게 알려 드렸어요.") }
            } else app.state.onInCarOnly(a)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "모심 동승", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mosim)
            .setContentTitle("모심이 함께 타고 있어요")
            .setContentText("동의하신 매달 요약과 차량 관리 소식만 가족에게 전합니다")
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
        private const val PANIC_WINDOW_MS = 4000L
        const val ACTION_PERMISSIONS_GRANTED = "com.hiddenframe.mosim.PERMISSIONS_GRANTED"
        fun start(context: Context, action: String? = null) {
            val i = Intent(context, DriveMonitorService::class.java).setAction(action)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
    }
}
