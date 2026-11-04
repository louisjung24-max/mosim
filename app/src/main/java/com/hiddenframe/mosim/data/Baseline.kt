package com.hiddenframe.mosim.data

import android.content.Context

/**
 * 부모님의 "평소" 운행 패턴. 차량 안에만 저장되고 밖으로 나가지 않습니다.
 * 학습 완료 기준: 운행 10회 이상 (약 2주). 학습이 끝나기 전에는 "평소와 다름"을 판단하지 않습니다.
 */
class Baseline(context: Context) {
    private val prefs = context.getSharedPreferences("baseline", Context.MODE_PRIVATE)

    val tripCount: Int get() = prefs.getInt("trips", 0)
    val learned: Boolean get() = tripCount >= LEARN_TRIPS
    val avgBrakesPerTrip: Float get() = if (tripCount == 0) 0f else prefs.getInt("brakes", 0).toFloat() / tripCount
    /** 평소 한 번 운행 거리(km). 지수이동평균으로 운행마다 갱신합니다. */
    val usualTripKm: Float get() = prefs.getFloat("usual_km", 0f)

    /** 그 시간대(시)에 2회 이상 운행한 적이 있으면 "평소 다니는 시간" */
    fun usuallyDrivesAt(hour: Int): Boolean = prefs.getInt("h$hour", 0) >= 2

    fun recordTrip(hour: Int, brakes: Int, km: Float) {
        val e = prefs.edit()
            .putInt("trips", tripCount + 1)
            .putInt("brakes", prefs.getInt("brakes", 0) + brakes)
            .putInt("h$hour", prefs.getInt("h$hour", 0) + 1)
        if (km >= MIN_TRIP_KM) {
            val old = usualTripKm
            e.putFloat("usual_km", if (old <= 0f) km else old * (1 - EMA) + km * EMA)
        }
        e.apply()
    }

    fun reset() = prefs.edit().clear().apply()

    companion object {
        const val LEARN_TRIPS = 10
        private const val EMA = 0.2f
        private const val MIN_TRIP_KM = 0.3f
    }
}
