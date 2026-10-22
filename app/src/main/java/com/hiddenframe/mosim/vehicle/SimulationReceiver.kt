package com.hiddenframe.mosim.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hiddenframe.mosim.service.DriveMonitorService

/** adb 브로드캐스트로 시연 시나리오를 재생합니다. 서비스가 꺼져 있으면 먼저 켭니다. */
class SimulationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scenario = intent.getStringExtra("scenario") ?: SimulatedSignalSource.SCENARIO_DAYTIME
        DriveMonitorService.start(context)
        SimulatedSignalSource.play(scenario)
    }
}
