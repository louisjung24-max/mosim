package com.hiddenframe.mosim.vehicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 시연용 신호 공급원. 시나리오 이름을 받으면 정해진 순서로 이벤트를 흘려보냅니다.
 * adb: adb shell am broadcast -a com.hiddenframe.mosim.SIM --es scenario rainy_night
 */
object SimulatedSignalSource : VehicleSignalSource {
    override val name = "simulator"

    private val _events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VehicleEvent> = _events

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null

    override fun start() { /* 시나리오가 들어올 때까지 대기 */ }
    override fun stop() { running?.cancel() }

    fun play(scenario: String) {
        running?.cancel()
        running = scope.launch {
            delay(400) // 서비스가 막 시작된 경우 구독이 붙을 시간을 줍니다
            when (scenario) {
                SCENARIO_RAINY_NIGHT -> rainyNight()
                SCENARIO_TIRE, SCENARIO_BATTERY -> batteryLow()
                SCENARIO_LONG_STOP -> longStop()
                SCENARIO_DAYTIME -> daytimeTrip()
                else -> daytimeTrip()
            }
        }
    }

    private suspend fun emit(e: VehicleEvent, afterMs: Long = 0) {
        if (afterMs > 0) delay(afterMs)
        _events.emit(e)
    }

    /** 시나리오 B: 비 오는 밤길. 밤 10시 40분 시동 → 와이퍼 → 급제동 2회 */
    private suspend fun rainyNight() {
        emit(VehicleEvent.Ignition(on = true, hourOfDay = 22))
        emit(VehicleEvent.Wiper(on = true), 1200)
        emit(VehicleEvent.Speed(28f), 1500)
        emit(VehicleEvent.HardBrake(4.2f), 3000)
        emit(VehicleEvent.Speed(12f), 500)
        emit(VehicleEvent.HardBrake(4.8f), 2500)
        emit(VehicleEvent.Speed(25f), 1500)
    }

    /** 차량 관리: 전기차 배터리 잔량 낮음 (실차에서는 Vehicle SDK EvBattery·Fuel로 감지) */
    private suspend fun batteryLow() {
        emit(VehicleEvent.Ignition(on = true, hourOfDay = 9))
        emit(VehicleEvent.BatteryLow(8), 1500)
    }

    private suspend fun longStop() {
        emit(VehicleEvent.Ignition(on = true, hourOfDay = 15))
        emit(VehicleEvent.Speed(0f), 1000)
        emit(VehicleEvent.IdleStop(minutes = 22), 1500)
    }

    private suspend fun daytimeTrip() {
        emit(VehicleEvent.Ignition(on = true, hourOfDay = 10))
        emit(VehicleEvent.Speed(35f), 1500)
        emit(VehicleEvent.Odometer(0.8f), 2000)
        emit(VehicleEvent.Ignition(on = false), 2500)
    }

    const val SCENARIO_RAINY_NIGHT = "rainy_night"
    const val SCENARIO_TIRE = "tire_low" // 이전 이름(호환용)
    const val SCENARIO_BATTERY = "battery_low"
    const val SCENARIO_LONG_STOP = "long_stop"
    const val SCENARIO_DAYTIME = "daytime"
}
