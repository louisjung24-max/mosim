package com.hiddenframe.mosim.rules

import com.hiddenframe.mosim.vehicle.VehicleEvent

/** 그때그때 가족에게 갈 수 있는 신호. 지금은 차량 관리(배터리·연료 부족)만 있습니다. 나머지는 매달 요약으로 갑니다. */
data class Anomaly(val type: Type, val title: String, val body: String) {
    enum class Type(val shareKey: String) { VEHICLE_CARE("care") }
}

/**
 * 차 안에서 들려줄 말. 비난하지 않고, 짧고, 존댓말.
 * [atStop]이 true면 달리는 중에는 말하지 않고 다음에 차가 멈췄을 때 말합니다(운전 중 말 아끼기).
 */
data class VoiceCue(val text: String, val atStop: Boolean = false)

/**
 * 동승 안내 규칙. 판정은 차 안에서 끝나며, 운행 기록은 월 합계로만 밖에 나갑니다.
 * 기준값은 안전·의학 지표가 아닌 실험 규칙입니다.
 */
class AnomalyDetector {

    data class Result(val voice: VoiceCue? = null, val anomaly: Anomaly? = null)

    private var wiperOn = false
    private var brakeCountThisTrip = 0
    private var spokeBrakeOnce = false

    fun onEvent(e: VehicleEvent): Result = when (e) {
        is VehicleEvent.Ignition -> onIgnition(e)
        is VehicleEvent.Wiper -> { wiperOn = e.on; Result() }
        is VehicleEvent.HardBrake -> onHardBrake()
        is VehicleEvent.FuelLow -> Result(
            voice = VoiceCue("연료가 ${e.percent}퍼센트 남았습니다. 가까운 주유소에 들르시면 좋겠어요."),
            anomaly = Anomaly(Anomaly.Type.VEHICLE_CARE, "연료 부족", "연료가 ${e.percent}% 남았습니다."),
        )
        is VehicleEvent.BatteryLow -> Result(
            voice = VoiceCue("배터리가 ${e.percent}퍼센트 남았습니다. 가까운 충전소에 들르시면 좋겠어요."),
            anomaly = Anomaly(Anomaly.Type.VEHICLE_CARE, "배터리 부족", "배터리가 ${e.percent}% 남았습니다."),
        )
        is VehicleEvent.IdleStop -> if (e.minutes >= LONG_STOP_MIN)
            Result(voice = VoiceCue("${e.minutes}분째 멈춰 계세요. 천천히 쉬어 가셔도 괜찮습니다.")) else Result()
        is VehicleEvent.Odometer, is VehicleEvent.Speed -> Result()
    }

    private fun onIgnition(e: VehicleEvent.Ignition): Result {
        if (!e.on) { brakeCountThisTrip = 0; spokeBrakeOnce = false; wiperOn = false; return Result() }
        val hour = e.hourOfDay ?: java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (!isNight(hour)) return Result()
        val label = when {
            hour >= NIGHT_FROM -> "밤 ${hour - 12}시"
            hour == 0 -> "밤 12시"
            else -> "새벽 ${hour}시"
        }
        return Result(voice = VoiceCue("지금 ${label}입니다. 밤길은 천천히 가셔도 괜찮아요."))
    }

    private fun onHardBrake(): Result {
        brakeCountThisTrip++
        if (brakeCountThisTrip < 2 || spokeBrakeOnce) return Result()
        spokeBrakeOnce = true
        val weather = if (wiperOn) "비가 와서 길이 미끄러워요. " else ""
        return Result(voice = VoiceCue("${weather}급하게 멈출 일이 몇 번 있었어요. 천천히 가셔도 괜찮습니다.", atStop = true))
    }

    companion object {
        const val NIGHT_FROM = 21
        const val NIGHT_UNTIL = 5
        const val LONG_STOP_MIN = 20
        const val FAST_KMH = 80f
        fun isNight(hour: Int) = hour >= NIGHT_FROM || hour < NIGHT_UNTIL
    }
}
