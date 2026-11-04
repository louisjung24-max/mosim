package com.hiddenframe.mosim.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hiddenframe.mosim.data.Baseline
import com.hiddenframe.mosim.service.DriveMonitorService

/**
 * 디버그 빌드 전용(src/debug 매니페스트에만 등록): adb 브로드캐스트로 시연 시나리오를 재생합니다.
 * scenario=seed_baseline 은 "2주 학습을 마친 상태"를 시연용으로 채웁니다(낮 운행 10회, 평균 12km).
 */
class SimulationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scenario = intent.getStringExtra("scenario") ?: SimulatedSignalSource.SCENARIO_DAYTIME
        if (scenario == "seed_baseline") {
            Baseline(context).apply {
                reset()
                repeat(Baseline.LEARN_TRIPS) { recordTrip(hour = 9 + it % 8, brakes = 1, km = 12f) }
            }
            return
        }
        DriveMonitorService.start(context)
        SimulatedSignalSource.play(scenario)
    }
}
