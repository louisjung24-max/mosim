package com.hiddenframe.mosim

import com.hiddenframe.mosim.alert.AlertGate
import com.hiddenframe.mosim.rules.Anomaly
import com.hiddenframe.mosim.vehicle.VehicleEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면이 구독하는 현재 상태. */
data class UiState(
    /** 운행 중(Pleos: 기어가 P가 아님) */
    val driving: Boolean = false,
    val speedKmh: Int = 0,
    val wiperOn: Boolean = false,
    val hour: Int? = null,
    val lastVoice: String = "출발하시면 모심이 함께 탑니다.",
    val log: List<Pair<String, String>> = emptyList(),
) {
    /** 설정 변경·버튼 조작이 안전한 상태: 주차 중이거나 멈춰 있을 때 */
    val stopped: Boolean get() = !driving || speedKmh < 5
}

class AppState {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private val fmt = SimpleDateFormat("HH:mm", Locale.KOREA)

    fun onEvent(e: VehicleEvent) {
        _ui.value = when (e) {
            is VehicleEvent.Ignition -> _ui.value.copy(driving = e.on, hour = e.hourOfDay, speedKmh = 0, wiperOn = if (e.on) _ui.value.wiperOn else false)
            is VehicleEvent.Speed -> _ui.value.copy(speedKmh = e.kmh.toInt())
            is VehicleEvent.Wiper -> _ui.value.copy(wiperOn = e.on)
            is VehicleEvent.HardBrake -> append("급제동 감지")
            is VehicleEvent.FuelLow -> append("연료 ${e.percent}%")
            is VehicleEvent.BatteryLow -> append("배터리 ${e.percent}%")
            is VehicleEvent.IdleStop -> append("${e.minutes}분째 멈춤")
            is VehicleEvent.Odometer -> _ui.value
        }
    }

    fun onVoice(text: String) { _ui.value = append("안내: $text").copy(lastVoice = text) }

    fun note(text: String) { _ui.value = append(text) }

    /** 공유하지 않는 항목: 차 안에서만 안내 */
    fun onInCarOnly(a: Anomaly) { _ui.value = append("차 안에서만: ${a.title} (공유 안 함)") }

    fun onOutcome(a: Anomaly, o: AlertGate.Outcome) {
        val line = when (o) {
            AlertGate.Outcome.SENT -> "가족에게 전송됨: ${a.title}"
            AlertGate.Outcome.VETOED -> "부모님이 보내지 않음: ${a.title}"
            AlertGate.Outcome.SHARING_OFF -> "보내지 않음(공유 꺼짐): ${a.title}"
            AlertGate.Outcome.NOT_CONFIGURED -> "보내지 못함(서버 미설정): ${a.title}"
            AlertGate.Outcome.REJECTED -> "보내지 못함(서버 거부): ${a.title}"
            AlertGate.Outcome.FAILED -> "보내지 못함(연결 실패): ${a.title}"
        }
        _ui.value = append(line)
    }

    private fun append(line: String): UiState {
        val l = (listOf(fmt.format(Date()) to line) + _ui.value.log).take(8)
        return _ui.value.copy(log = l)
    }
}
