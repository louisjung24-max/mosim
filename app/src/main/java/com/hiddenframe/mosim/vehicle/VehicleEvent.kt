package com.hiddenframe.mosim.vehicle

/**
 * 차량에서 올라오는 신호. 어떤 소스(시뮬레이터, AAOS Car API, Pleos Vehicle SDK)든 이 형태로 통일합니다.
 * 원시 주행 데이터는 차량 안에서만 쓰이고, 차 밖으로는 판정 결과(이상 신호)만 나갑니다.
 */
sealed class VehicleEvent {
    /** 운행 시작/종료(Pleos: 기어 P↔D). [hourOfDay]는 출발 시각(시). */
    data class Ignition(val on: Boolean, val hourOfDay: Int? = null) : VehicleEvent()
    data class Speed(val kmh: Float) : VehicleEvent()
    /** 급제동. [decel]은 m/s² 추정값. */
    data class HardBrake(val decel: Float) : VehicleEvent()
    data class Wiper(val on: Boolean) : VehicleEvent()
    data class FuelLow(val percent: Int) : VehicleEvent()
    /** 전기차 구동 배터리 잔량(%) 낮음 */
    data class BatteryLow(val percent: Int) : VehicleEvent()
    data class Odometer(val km: Float) : VehicleEvent()
    /** 운행 중(기어 D) 멈춰 있는 누적 시간(분). */
    data class IdleStop(val minutes: Int) : VehicleEvent()
}
