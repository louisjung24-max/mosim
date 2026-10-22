package com.hiddenframe.mosim

import com.hiddenframe.mosim.rules.Anomaly
import com.hiddenframe.mosim.vehicle.VehicleEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면이 구독하는 현재 상태. */
data class UiState(
    val ignitionOn: Boolean = false,
    val speedKmh: Int = 0,
    val wiperOn: Boolean = false,
    val hour: Int? = null,
    val lastVoice: String = "시동을 켜면 모심이 함께 탑니다.",
    val log: List<String> = emptyList(),
)

class AppState {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun onEvent(e: VehicleEvent) {
        _ui.value = when (e) {
            is VehicleEvent.Ignition -> _ui.value.copy(ignitionOn = e.on, hour = e.hourOfDay, speedKmh = 0, wiperOn = if (e.on) _ui.value.wiperOn else false)
            is VehicleEvent.Speed -> _ui.value.copy(speedKmh = e.kmh.toInt())
            is VehicleEvent.Wiper -> _ui.value.copy(wiperOn = e.on)
            is VehicleEvent.HardBrake -> append("급제동 감지")
            is VehicleEvent.TirePressureLow -> append("타이어 공기압 낮음 (${e.wheel})")
            is VehicleEvent.FuelLow -> append("연료 ${e.percent}%")
            is VehicleEvent.IdleStop -> append("정차 ${e.minutes}분")
            is VehicleEvent.Odometer -> _ui.value
        }
    }

    fun onVoice(text: String) { _ui.value = append("음성: $text").copy(lastVoice = text) }

    fun onAnomaly(a: Anomaly, shared: Boolean) {
        _ui.value = append(if (shared) "가족에게 전달: ${a.title}" else "차 안에서만: ${a.title} (공유 꺼짐)")
    }

    private fun append(line: String): UiState {
        val l = (listOf("${fmt.format(Date())}  $line") + _ui.value.log).take(8)
        return _ui.value.copy(log = l)
    }
}
