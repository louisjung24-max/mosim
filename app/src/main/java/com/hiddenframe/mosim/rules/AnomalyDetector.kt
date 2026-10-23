package com.hiddenframe.mosim.rules

import com.hiddenframe.mosim.data.Baseline
import com.hiddenframe.mosim.vehicle.VehicleEvent

/** 자녀에게 전달될 수 있는 "평소와 다름" 신호. 원시 주행 데이터가 아니라 판정 결과만 담습니다. */
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

/** 운전자에게 차 안에서 들려줄 음성. 비난하지 않고, 짧고, 존댓말. */
data class VoiceCue(val text: String)

/**
 * 규칙 기반 판정기. 첫 2주는 [Baseline]을 학습하고, 이후 평소 패턴과의 차이만 봅니다.
 * 판정은 차량 안에서 끝나고, 결과(Anomaly)만 밖으로 나갑니다.
 */
class AnomalyDetector(private val baseline: Baseline) {

    data class Result(val voice: VoiceCue? = null, val anomaly: Anomaly? = null)

    private var hour: Int = 12
    private var wiperOn = false
    private var brakeCountThisTrip = 0
    private var spokeNightOnce = false
    private var spokeBrakeOnce = false

    fun onEvent(e: VehicleEvent): Result = when (e) {
        is VehicleEvent.Ignition -> onIgnition(e)
        is VehicleEvent.Wiper -> { wiperOn = e.on; Result() }
        is VehicleEvent.HardBrake -> onHardBrake()
        is VehicleEvent.TirePressureLow -> Result(
            voice = VoiceCue("${e.wheel} 타이어 공기압이 낮습니다. 가까운 정비소에서 점검받으시면 좋겠어요."),
            anomaly = Anomaly(Anomaly.Type.VEHICLE_CARE, "타이어 공기압 낮음", "${e.wheel} 타이어 공기압이 낮습니다. 정비 예약을 도와드릴까요?"),
        )
        is VehicleEvent.FuelLow -> Result(
            voice = VoiceCue("연료가 ${e.percent}퍼센트 남았습니다. 주유소를 들르시면 좋겠어요."),
            anomaly = Anomaly(Anomaly.Type.VEHICLE_CARE, "연료 부족", "연료가 ${e.percent}% 남았습니다."),
        )
        is VehicleEvent.IdleStop -> if (e.minutes >= LONG_STOP_MIN) Result(
            voice = VoiceCue("시동을 켜 둔 채 ${e.minutes}분째 멈춰 계세요. 괜찮으시면 말씀해 주세요."),
            anomaly = Anomaly(Anomaly.Type.LONG_STOP, "장시간 정차", "시동이 켜진 채 ${e.minutes}분째 정차 중입니다. 평소보다 긴 정차예요."),
        ) else Result()
        is VehicleEvent.Odometer -> if (e.km >= baseline.usualTripKm * LONG_DISTANCE_RATIO && baseline.learned) Result(
            anomaly = Anomaly(Anomaly.Type.LONG_DISTANCE, "장거리 운행", "오늘 ${e.km.toInt()}km를 달리고 있어요. 평소보다 먼 길입니다."),
        ) else Result()
        is VehicleEvent.Speed -> Result()
    }

    private fun onIgnition(e: VehicleEvent.Ignition): Result {
        if (!e.on) {
            baseline.recordTrip(hour, brakeCountThisTrip)
            brakeCountThisTrip = 0; spokeNightOnce = false; spokeBrakeOnce = false; wiperOn = false
            return Result()
        }
        hour = e.hourOfDay ?: java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        baseline.recordTripStart()
        val isNight = hour >= NIGHT_FROM || hour < NIGHT_UNTIL
        if (!isNight) return Result(voice = weeklyGreetingIfSunday())
        spokeNightOnce = true
        val label = if (hour >= NIGHT_FROM) "밤 ${hour - 12}시" else "새벽 ${if (hour == 0) 12 else hour}시"
        val voice = VoiceCue("지금 ${label}입니다. 밤길은 천천히 가셔도 괜찮아요.")
        val unusual = !baseline.learned || !baseline.usuallyDrivesAt(hour)
        val anomaly = if (unusual) Anomaly(
            Anomaly.Type.NIGHT_DEPARTURE, "야간 외출",
            "차량이 ${String.format("%02d", hour)}시에 출발했습니다. 평소 야간 운행이 없던 시간대예요.",
        ) else null
        return Result(voice, anomaly)
    }

    private fun onHardBrake(): Result {
        brakeCountThisTrip++
        val usual = baseline.avgBrakesPerTrip
        val spike = brakeCountThisTrip >= 2 && (!baseline.learned || brakeCountThisTrip >= usual * BRAKE_SPIKE_RATIO)
        if (!spike || spokeBrakeOnce) return Result()
        spokeBrakeOnce = true
        val weather = if (wiperOn) "비 오는 밤이라 길이 미끄럽습니다. " else ""
        val voice = VoiceCue("${weather}오늘은 급제동이 평소보다 잦았어요. 조금 천천히 가셔도 괜찮습니다.")
        // 급제동 횟수는 부모님이 '운전 점수' 공유를 켠 경우에만 자녀에게 전달됩니다.
        val anomaly = Anomaly(Anomaly.Type.HARD_BRAKE_SPIKE, "급제동 증가", "오늘 운행에서 급제동이 평소보다 잦습니다.")
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
