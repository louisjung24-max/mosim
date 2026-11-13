package com.hiddenframe.mosim

import com.hiddenframe.mosim.alert.Delivery
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
    val rev: Int = 0,
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
            is VehicleEvent.HardAccel -> _ui.value
            is VehicleEvent.Direction -> _ui.value
        }
    }

    fun onVoice(text: String) { _ui.value = append("안내: $text").copy(lastVoice = text) }

    fun note(text: String) { _ui.value = append(text) }

    /** 공유하지 않는 항목: 차 안에서만 안내 */
    fun onInCarOnly(a: Anomaly) { _ui.value = append("차 안에서만: ${a.title}") }

    /** '가족에게 보냄'은 서버 저장이 확인됐을 때만 표시합니다. */
    fun onDelivery(title: String, d: Delivery) {
        val line = when (d) {
            Delivery.SENT -> "가족에게 보냄: $title"
            Delivery.NOT_CONFIGURED -> "보내지 못함(서버 미설정): $title"
            Delivery.REJECTED -> "보내지 못함(서버 거부): $title"
            Delivery.FAILED -> "보내지 못함(연결 실패): $title"
        }
        _ui.value = append(line)
    }

    /** 요약 화면을 다시 그리게 하는 신호 */
    fun touch() { _ui.value = _ui.value.copy(rev = _ui.value.rev + 1) }

    private fun append(line: String): UiState {
        val l = (listOf(fmt.format(Date()) to line) + _ui.value.log).take(8)
        return _ui.value.copy(log = l)
    }
}
