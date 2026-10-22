package com.hiddenframe.mosim.data

import android.content.Context

/** 주간 요약 카운터. 매주 일요일 요약 음성과 자녀 페이지에 쓰입니다. */
class WeeklyStore(context: Context) {
    private val prefs = context.getSharedPreferences("weekly", Context.MODE_PRIVATE)

    val trips: Int get() = prefs.getInt("trips", 0)
    val anomalies: Int get() = prefs.getInt("anomalies", 0)
    val hardBrakes: Int get() = prefs.getInt("brakes", 0)
    val nightTrips: Int get() = prefs.getInt("night", 0)

    fun addTrip(night: Boolean) = bump("trips").also { if (night) bump("night") }
    fun addAnomaly() = bump("anomalies")
    fun addHardBrake() = bump("brakes")
    fun reset() = prefs.edit().clear().apply()

    private fun bump(k: String) = prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
}
