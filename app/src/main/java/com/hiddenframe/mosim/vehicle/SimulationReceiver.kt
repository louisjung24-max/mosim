package com.hiddenframe.mosim.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hiddenframe.mosim.MosimApp
import com.hiddenframe.mosim.data.Trip
import com.hiddenframe.mosim.data.TripLog
import com.hiddenframe.mosim.service.DriveMonitorService
import java.util.Calendar
import kotlin.random.Random

/**
 * 디버그 빌드 전용(src/debug 매니페스트에만 등록): adb 브로드캐스트로 시연 시나리오를 재생합니다.
 * scenario=seed_month 는 지난 두 달의 운행 기록을 시연용으로 채웁니다(실제 기록이 아님).
 *  - 지난달: 운행 23회, 밤 2회, 고속 주행 약 40분, 급제동 3회
 *  - 그 전달: 운행 21회, 밤 5회, 급제동 5회
 */
class SimulationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scenario = intent.getStringExtra("scenario") ?: SimulatedSignalSource.SCENARIO_DAYTIME
        if (scenario == "seed_month") {
            val app = context.applicationContext as MosimApp
            val rnd = Random(7)
            fun month(back: Int, trips: Int, night: Int, fastTotal: Float, brakes: Int): List<Trip> {
                val base = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -back) }
                return (0 until trips).map { i ->
                    val c = (base.clone() as Calendar).apply {
                        set(Calendar.DAY_OF_MONTH, 1 + (i * 27 / trips))
                        set(Calendar.HOUR_OF_DAY, if (i < night) 22 else 9 + i % 8)
                        set(Calendar.MINUTE, rnd.nextInt(60))
                    }
                    Trip(c.timeInMillis, 8f + rnd.nextInt(12), i < night, if (i % 6 == 0) fastTotal / ((trips + 5) / 6) else 0f, if (i < brakes) 1 else 0)
                }
            }
            app.trips.replaceAll(month(2, 21, 5, 55f, 5) + month(1, 23, 2, 40f, 3))
            app.trips.lastSentMonth = null
            app.state.touch()
            return
        }
        DriveMonitorService.start(context)
        SimulatedSignalSource.play(scenario)
    }
}
