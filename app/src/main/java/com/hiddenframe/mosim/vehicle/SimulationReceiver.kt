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
 * scenario=seed_month 는 지난 넉 달의 운행 기록을 시연용으로 채웁니다(실제 기록이 아님).
 *  - 앞선 석 달(기준): 한 달 22회 안팎, 밤 운전 5회, 급제동 100km당 1번쯤, 앞뒤로 고쳐 대기 운행당 1.2번
 *  - 지난달: 밤 운전 1회, 급제동 100km당 2번대, 앞뒤로 고쳐 대기 운행당 2.8번 → '달라진 점 3가지'
 */
class SimulationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scenario = intent.getStringExtra("scenario") ?: SimulatedSignalSource.SCENARIO_DAYTIME
        if (scenario == "seed_month") {
            val app = context.applicationContext as MosimApp
            val rnd = Random(7)
            fun month(back: Int, trips: Int, night: Int, brakes: Int, flips: (Int) -> Int): List<Trip> {
                val base = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -back) }
                return (0 until trips).map { i ->
                    val c = (base.clone() as Calendar).apply {
                        set(Calendar.DAY_OF_MONTH, 1 + (i * 27 / trips))
                        set(Calendar.HOUR_OF_DAY, if (i < night) 22 else 9 + i % 8)
                        set(Calendar.MINUTE, rnd.nextInt(60))
                    }
                    val km = if (i == trips / 2) 36f + rnd.nextInt(4) else 8f + rnd.nextInt(12)
                    Trip(c.timeInMillis, km, i < night, if (i % 6 == 0) 9f else 0f, if (i < brakes) 1 else 0, flips(i), 0)
                }
            }
            val usual = { i: Int -> if (i % 5 == 0) 2 else 1 }
            app.trips.replaceAll(
                month(4, 22, 5, 3, usual) + month(3, 21, 6, 3, usual) + month(2, 23, 5, 4, usual) +
                    month(1, 21, 1, 7) { i -> if (i % 4 == 0) 2 else 3 }
            )
            app.trips.lastSentMonth = null
            app.state.touch()
            return
        }
        DriveMonitorService.start(context)
        SimulatedSignalSource.play(scenario)
    }
}
