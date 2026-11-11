package com.hiddenframe.mosim.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** 운행 한 번의 기록. 차량 안에만 저장되고, 밖으로는 월 단위 합계만 나갑니다. */
data class Trip(
    val start: Long,
    val km: Float,
    val night: Boolean,
    /** 시속 80km 이상으로 달린 시간(분) — 조건부 면허의 '고속도로 운전'에 대응하는 지표 */
    val fastMin: Float,
    val brakes: Int,
)

/**
 * 한 달 운전 요약 — "운전 거울".
 * 조건부 운전면허에서 논의되는 조건(야간·고속도로)과 같은 축으로 정리해, 부모님이 스스로 확인하고
 * 가족에게 보여 줄 근거가 되게 합니다. 가족이 받는 내용과 차 화면에 보이는 내용은 같습니다.
 */
data class MonthlySummary(
    val year: Int,
    val month: Int,
    val trips: Int,
    val km: Float,
    val nightTrips: Int,
    val fastMin: Float,
    val brakesPer100Km: Float,
    val prev: MonthlySummary? = null,
) {
    val label: String get() = "${month}월"

    /** 잘하신 점부터 한 줄로. 평가하거나 꾸짖지 않습니다. */
    fun highlight(): String {
        val p = prev
        return when {
            trips == 0 -> "${label}에는 운행 기록이 없습니다."
            p != null && p.trips > 0 && nightTrips < p.nightTrips -> "밤 운전이 지난달보다 ${p.nightTrips - nightTrips}번 줄었어요."
            p != null && p.trips > 0 && brakesPer100Km < p.brakesPer100Km - 0.1f -> "급하게 멈추는 일이 지난달보다 줄었어요."
            nightTrips == 0 -> "밤 운전 없이 낮에만 다니셨어요."
            fastMin < 1f -> "고속 주행 없이 다니셨어요."
            else -> "${label}도 꾸준히 다니셨어요."
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("month", "%d-%02d".format(year, month))
        .put("trips", trips)
        .put("km", Math.round(km))
        .put("night", nightTrips)
        .put("fastMin", Math.round(fastMin))
        .put("brakes100", (Math.round(brakesPer100Km * 10) / 10.0))
        .put("prevNight", prev?.nightTrips ?: JSONObject.NULL)
        .put("prevBrakes100", prev?.let { Math.round(it.brakesPer100Km * 10) / 10.0 } ?: JSONObject.NULL)
        .put("highlight", highlight())
}

class TripLog(context: Context) {
    private val prefs = context.getSharedPreferences("trips", Context.MODE_PRIVATE)

    fun all(): List<Trip> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Trip(o.getLong("s"), o.getDouble("km").toFloat(), o.getBoolean("n"), o.getDouble("f").toFloat(), o.getInt("b"))
        }
    }

    fun add(t: Trip) {
        val cutoff = System.currentTimeMillis() - 400L * 24 * 3600 * 1000 // 약 13개월만 보관
        val list = (all() + t).filter { it.start >= cutoff }
        save(list)
    }

    fun replaceAll(list: List<Trip>) = save(list)

    private fun save(list: List<Trip>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("s", it.start).put("km", it.km.toDouble()).put("n", it.night).put("f", it.fastMin.toDouble()).put("b", it.brakes)) }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    /** 마지막으로 가족에게 보낸 달("2026-10") */
    var lastSentMonth: String?
        get() = prefs.getString("last_sent", null)
        set(v) = prefs.edit().putString("last_sent", v).apply()

    fun summary(year: Int, month: Int, withPrev: Boolean = true): MonthlySummary {
        val ts = all().filter { cal(it.start).let { c -> c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) + 1 == month } }
        val km = ts.sumOf { it.km.toDouble() }.toFloat()
        val brakes = ts.sumOf { it.brakes }
        val prev = if (withPrev) {
            val c = Calendar.getInstance().apply { clear(); set(year, month - 1, 1); add(Calendar.MONTH, -1) }
            summary(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, withPrev = false)
        } else null
        return MonthlySummary(
            year, month, ts.size, km, ts.count { it.night }, ts.sumOf { it.fastMin.toDouble() }.toFloat(),
            if (km > 1f) brakes * 100f / km else 0f, prev,
        )
    }

    fun thisMonth(): MonthlySummary = Calendar.getInstance().let { summary(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1) }

    fun lastMonth(): MonthlySummary = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
        .let { summary(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1) }

    companion object {
        fun cal(ms: Long): Calendar = Calendar.getInstance().apply { timeInMillis = ms }
        fun monthKey(c: Calendar = Calendar.getInstance()) = "%d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }
}
