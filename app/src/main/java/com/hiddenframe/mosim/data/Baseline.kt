package com.hiddenframe.mosim.data

import android.content.Context

/**
 * 부모님의 "평소" 운행 패턴. 차량 안에만 저장되고 밖으로 나가지 않습니다.
 * 학습 완료 기준: 운행 10회 이상 (약 2주).
 */
class Baseline(context: Context) {
    private val prefs = context.getSharedPreferences("baseline", Context.MODE_PRIVATE)

    val tripCount: Int get() = prefs.getInt("trips", 0)
    val learned: Boolean get() = tripCount >= LEARN_TRIPS
    val avgBrakesPerTrip: Float get() = if (tripCount == 0) 0f else prefs.getInt("brakes", 0).toFloat() / tripCount
    val usualTripKm: Float get() = prefs.getFloat("usual_km", 12f)

    fun usuallyDrivesAt(hour: Int): Boolean = prefs.getInt("h$hour", 0) >= 2

    fun recordTripStart() { /* 운행 시작 표시용. 통계는 종료 시 기록 */ }

    fun recordTrip(hour: Int, brakes: Int) {
        prefs.edit()
            .putInt("trips", tripCount + 1)
            .putInt("brakes", prefs.getInt("brakes", 0) + brakes)
            .putInt("h$hour", prefs.getInt("h$hour", 0) + 1)
            .apply()
    }

    fun reset() = prefs.edit().clear().apply()

    companion object { const val LEARN_TRIPS = 10 }
}
