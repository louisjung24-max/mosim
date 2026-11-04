package com.hiddenframe.mosim.rules

import com.hiddenframe.mosim.data.Baseline
import com.hiddenframe.mosim.vehicle.VehicleEvent

/** 가족에게 보낼 수 있는 "평소와 다름" 신호. 원시 주행 데이터가 아니라 판정 결과 한 줄만 담습니다. */
data class Anomaly(
    val type: Type,
    val title: String,
    val body: String,
) {
    enum class Type(val shareKey: String) {
        NIGHT_DEPARTURE("night"),
        LONG_DISTANCE("distance"),
        LONG_STOP("stop"),
        HARD_BRAKE_SPIKE("score"),
        VEHICLE_CARE("care"),
    }
}

/**
 * 차 안에서 들려줄 말. 비난하지 않고, 짧고, 존댓말.
 * [atStop]이 true면 달리는 중에는 말하지 않고 다음에 차가 멈췄을 때 말합니다(운전 중 말 아끼기).
 */
data class VoiceCue(val text: String, val atStop: Boolean = false)

/**
 * 규칙 기반 판정기. 판정은 차량 안에서 끝나고, 결과(Anomaly)만 밖으로 나갈 수 있습니다.
 * 운행 10회(약 2주) 학습 전에는 "평소"를 모르므로 가족에게 보낼 이상 신호를 만들지 않습니다(차량 관리 경고 제외).
 * 기준값은 안전·의학 지표가 아닌 실험 규칙이며, 실차 데이터로 조정해야 합니다.
 */
class AnomalyDetector(private val baseline: Baseline) {

    data class Result(val voice: VoiceCue? = null, val anomaly: Anomaly? = null)

    private var hour: Int = 12
    private var wiperOn = false
    private var brakeCountThisTrip = 0
    private var spokeBrakeOnce = false
    private var reportedDistanceOnce = false
    private var tripKm = 0f

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
        is VehicleEvent.IdleStop -> if (e.minutes >= LONG_STOP_MIN) Result(
            voice = VoiceCue("${e.minutes}분째 멈춰 계세요. 천천히 쉬어 가셔도 괜찮습니다."),
            anomaly = Anomaly(Anomaly.Type.LONG_STOP, "운행 중 오래 멈춤", "운행 중에 ${e.minutes}분째 멈춰 있습니다."),
        ) else Result()
        is VehicleEvent.Odometer -> onDistance(e.km)
        is VehicleEvent.Speed -> Result()
    }

    private fun onDistance(km: Float): Result {
        if (km > tripKm) tripKm = km
        val usual = baseline.usualTripKm
        if (reportedDistanceOnce || !baseline.learned || usual <= 0f || tripKm < usual * LONG_DISTANCE_RATIO) return Result()
        reportedDistanceOnce = true
        return Result(anomaly = Anomaly(Anomaly.Type.LONG_DISTANCE, "평소보다 먼 길",
            "이번 운행이 ${tripKm.toInt()}km를 넘었습니다. 평소(약 ${usual.toInt()}km)보다 먼 길이에요."))
    }

    private fun onIgnition(e: VehicleEvent.Ignition): Result {
        if (!e.on) {
            baseline.recordTrip(hour, brakeCountThisTrip, tripKm)
            brakeCountThisTrip = 0; spokeBrakeOnce = false; reportedDistanceOnce = false; wiperOn = false; tripKm = 0f
            return Result()
        }
        hour = e.hourOfDay ?: java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isNight = hour >= NIGHT_FROM || hour < NIGHT_UNTIL
        if (!isNight) return Result(voice = weeklyGreetingIfSunday())
        val label = when {
            hour >= NIGHT_FROM -> "밤 ${hour - 12}시"
            hour == 0 -> "밤 12시"
            else -> "새벽 ${hour}시"
        }
        val voice = VoiceCue("지금 ${label}입니다. 밤길은 천천히 가셔도 괜찮아요.")
        // 밤 외출 자체는 위험의 근거가 아닙니다. 학습을 마친 뒤, 평소 그 시간에 운행한 적이 없을 때만 "평소와 다름"입니다.
        val unusual = baseline.learned && !baseline.usuallyDrivesAt(hour)
        val anomaly = if (unusual) Anomaly(
            Anomaly.Type.NIGHT_DEPARTURE, "평소와 다른 밤 외출",
            "평소 운행하지 않던 ${String.format("%02d", hour)}시에 출발했습니다.",
        ) else null
        return Result(voice, anomaly)
    }

    private fun onHardBrake(): Result {
        brakeCountThisTrip++
        val usual = baseline.avgBrakesPerTrip
        val spike = brakeCountThisTrip >= 2 && (!baseline.learned || brakeCountThisTrip >= usual * BRAKE_SPIKE_RATIO)
        if (!spike || spokeBrakeOnce) return Result()
        spokeBrakeOnce = true
        val weather = if (wiperOn) "비가 와서 길이 미끄러워요. " else ""
        // 급제동 직후가 아니라 다음에 멈췄을 때, 평가하지 않는 말로 짧게.
        val voice = VoiceCue("${weather}급하게 멈출 일이 몇 번 있었어요. 천천히 가셔도 괜찮습니다.", atStop = true)
        // '급제동이 잦은 날' 공유는 기본 꺼짐. 학습 전에는 가족에게 보낼 신호를 만들지 않습니다.
        val anomaly = if (baseline.learned) Anomaly(Anomaly.Type.HARD_BRAKE_SPIKE, "급제동이 잦은 날", "오늘 운행에서 급제동이 평소보다 잦았습니다.") else null
        return Result(voice, anomaly)
    }

    private fun weeklyGreetingIfSunday(): VoiceCue? {
        val cal = java.util.Calendar.getInstance()
        if (cal.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.SUNDAY) return null
        return VoiceCue("이번 주도 잘 다니셨어요. 오늘도 안전 운전하세요.")
    }

    companion object {
        const val NIGHT_FROM = 21
        const val NIGHT_UNTIL = 5
        const val LONG_STOP_MIN = 20
        const val LONG_DISTANCE_RATIO = 3f
        const val BRAKE_SPIKE_RATIO = 2f
    }
}
