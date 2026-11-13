package com.hiddenframe.mosim.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** 운행 한 번의 기록. 차량 안에만 저장되고, 밖으로는 월 단위 합계와 '달라진 점'만 나갑니다. */
data class Trip(
    val start: Long,
    val km: Float,
    val night: Boolean,
    /** 시속 80km 이상으로 달린 시간(분) */
    val fastMin: Float,
    val brakes: Int,
    /** 전진↔후진을 바꾼 횟수(주차할 때 앞뒤로 고쳐 대기) */
    val flips: Int = 0,
    /** 저속에서 급가속한 직후 급제동한 횟수(페달 혼동 의심) */
    val panic: Int = 0,
)

/** 한 달의 지표 묶음. 석 달 평균(기준)도 같은 모양으로 담습니다. */
data class Metrics(
    val trips: Float,
    val km: Float,
    val night: Float,
    val maxKm: Float,
    val shortTrips: Float,
    val lateTrips: Float,
    val brakesPer100Km: Float,
    val panic: Float,
    val flipsPerTrip: Float,
)

/** 지난 석 달의 본인과 달라진 점 하나. [group]: range(운전 범위) · control(조작) · sense(길·시간 감각) */
data class Change(val key: String, val group: String, val text: String, val detail: String)

/**
 * 한 달 운전 요약 — "변화 신호".
 * 남과 비교하거나 점수를 매기지 않고, 지난 석 달의 본인과 달라진 점만 찾습니다.
 * 가족이 받는 내용과 차 화면에 보이는 내용은 같습니다. 의학적 진단이 아닙니다.
 */
data class MonthlySummary(
    val year: Int,
    val month: Int,
    val now: Metrics,
    val fastMin: Float,
    /** 지난 석 달 평균. 비교할 기록이 모자라면 null */
    val base: Metrics?,
) {
    val label: String get() = "${month}월"
    val trips: Int get() = now.trips.toInt()

    private fun f1(v: Float) = "%.1f".format(v)
    private fun n0(v: Float) = Math.round(v).toString()

    /** 달라진 점 찾기. 기준은 실험 규칙이며 실차 데이터로 조정합니다. */
    fun changes(): List<Change> {
        val b = base ?: return emptyList()
        val n = now
        val out = ArrayList<Change>()
        if (n.brakesPer100Km >= b.brakesPer100Km * 1.5f && n.brakesPer100Km - b.brakesPer100Km >= 0.7f)
            out += Change("brake", "control", "급하게 멈추는 일이 늘었어요", "100km당 ${f1(b.brakesPer100Km)}번 → ${f1(n.brakesPer100Km)}번")
        if (n.flipsPerTrip >= b.flipsPerTrip * 1.5f && n.flipsPerTrip - b.flipsPerTrip >= 0.7f)
            out += Change("park", "control", "주차할 때 앞뒤로 고쳐 대는 일이 늘었어요", "운행당 ${f1(b.flipsPerTrip)}번 → ${f1(n.flipsPerTrip)}번")
        if (n.panic >= 2f && n.panic > b.panic + 1f)
            out += Change("panic", "control", "급하게 밟았다가 바로 멈춘 일이 있었어요", "한 달 ${n0(b.panic)}번 → ${n0(n.panic)}번")
        if (n.shortTrips >= 3f && n.shortTrips >= b.shortTrips * 2f + 1f)
            out += Change("short", "sense", "나갔다가 금방 돌아온 운행이 늘었어요", "한 달 ${n0(b.shortTrips)}번 → ${n0(n.shortTrips)}번")
        if (n.lateTrips >= 2f && b.lateTrips < 1f)
            out += Change("late", "sense", "한밤중(자정~새벽 4시)에 나선 운행이 있었어요", "한 달 ${n0(b.lateTrips)}번 → ${n0(n.lateTrips)}번")
        if (b.night >= 3f && n.night <= b.night * 0.4f)
            out += Change("night", "range", "밤 운전이 줄었어요", "한 달 ${n0(b.night)}번 → ${n0(n.night)}번")
        if (b.trips >= 8f && n.trips <= b.trips * 0.65f)
            out += Change("trips", "range", "운전하는 날이 줄었어요", "한 달 ${n0(b.trips)}번 → ${n0(n.trips)}번")
        if (b.maxKm >= 10f && n.maxKm <= b.maxKm * 0.6f)
            out += Change("far", "range", "멀리 가는 일이 줄었어요", "가장 먼 운행 ${n0(b.maxKm)}km → ${n0(n.maxKm)}km")
        return out
    }

    /** 맨 위 한 줄 결론. */
    fun verdict(): String {
        val c = changes().size
        return when {
            trips == 0 -> "${label}에는 운행 기록이 없습니다."
            base == null -> "비교할 기록을 모으는 중입니다. 석 달이 쌓이면 달라진 점을 알려 드려요."
            c == 0 -> "지난 석 달과 비슷하게 운전하고 계십니다."
            else -> "지난 석 달과 달라진 점이 ${c}가지 있습니다."
        }
    }

    /** 가족이 할 수 있는 일 한 줄. 진단하지 않고, 확인할 계기만 줍니다. */
    fun advice(): String {
        val g = changes().map { it.group }.toSet()
        return when {
            g.isEmpty() -> ""
            "control" in g -> "한번 옆자리에 함께 타 보시고, 시력·건강 검진을 권해 보세요."
            "sense" in g -> "요즘 어떠신지 안부를 여쭙고, 한번 함께 타 보세요."
            else -> "스스로 조심하고 계신 것일 수 있어요. 불편한 점이 있는지 여쭤 보세요."
        }
    }

    /** 차가 말할 때 쓰는 한 줄 */
    fun spoken(): String {
        val c = changes().size
        return if (c == 0) verdict() else "지난 석 달과 달라진 점 ${c}가지를 함께 보냈어요. 화면에서 같은 내용을 보실 수 있습니다."
    }

    fun toJson(): JSONObject {
        val ch = JSONArray()
        changes().forEach { ch.put(JSONObject().put("t", it.text).put("d", it.detail)) }
        return JSONObject()
            .put("month", "%d-%02d".format(year, month))
            .put("trips", trips)
            .put("km", Math.round(now.km))
            .put("night", Math.round(now.night))
            .put("brakes100", Math.round(now.brakesPer100Km * 10) / 10.0)
            .put("flips", Math.round(now.flipsPerTrip * 10) / 10.0)
            .put("baseTrips", base?.let { Math.round(it.trips) } ?: JSONObject.NULL)
            .put("baseNight", base?.let { Math.round(it.night) } ?: JSONObject.NULL)
            .put("baseBrakes100", base?.let { Math.round(it.brakesPer100Km * 10) / 10.0 } ?: JSONObject.NULL)
            .put("baseFlips", base?.let { Math.round(it.flipsPerTrip * 10) / 10.0 } ?: JSONObject.NULL)
            .put("verdict", verdict())
            .put("changes", ch)
            .put("advice", advice())
    }
}

class TripLog(context: Context) {
    private val prefs = context.getSharedPreferences("trips", Context.MODE_PRIVATE)

    fun all(): List<Trip> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Trip(o.getLong("s"), o.getDouble("km").toFloat(), o.getBoolean("n"), o.getDouble("f").toFloat(), o.getInt("b"), o.optInt("r", 0), o.optInt("p", 0))
        }
    }

    fun add(t: Trip) {
        val cutoff = System.currentTimeMillis() - 400L * 24 * 3600 * 1000 // 약 13개월만 보관
        save((all() + t).filter { it.start >= cutoff })
    }

    fun replaceAll(list: List<Trip>) = save(list)

    private fun save(list: List<Trip>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("s", it.start).put("km", it.km.toDouble()).put("n", it.night).put("f", it.fastMin.toDouble())
                .put("b", it.brakes).put("r", it.flips).put("p", it.panic))
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    /** 마지막으로 가족에게 보낸 달("2026-10") */
    var lastSentMonth: String?
        get() = prefs.getString("last_sent", null)
        set(v) = prefs.edit().putString("last_sent", v).apply()

    private fun tripsOf(year: Int, month: Int, all: List<Trip>) =
        all.filter { cal(it.start).let { c -> c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) + 1 == month } }

    private fun metrics(ts: List<Trip>): Metrics {
        val km = ts.sumOf { it.km.toDouble() }.toFloat()
        return Metrics(
            trips = ts.size.toFloat(),
            km = km,
            night = ts.count { it.night }.toFloat(),
            maxKm = ts.maxOfOrNull { it.km } ?: 0f,
            shortTrips = ts.count { it.km < SHORT_KM }.toFloat(),
            lateTrips = ts.count { cal(it.start).get(Calendar.HOUR_OF_DAY) < LATE_UNTIL }.toFloat(),
            brakesPer100Km = if (km > 1f) ts.sumOf { it.brakes } * 100f / km else 0f,
            panic = ts.sumOf { it.panic }.toFloat(),
            flipsPerTrip = if (ts.isNotEmpty()) ts.sumOf { it.flips }.toFloat() / ts.size else 0f,
        )
    }

    fun summary(year: Int, month: Int): MonthlySummary {
        val all = all()
        val ts = tripsOf(year, month, all)
        // 기준: 앞선 석 달 가운데 운행이 5번 이상인 달의 평균. 그런 달이 두 달 이상 있어야 비교합니다.
        val prior = (1..BASE_MONTHS).map { back ->
            val c = Calendar.getInstance().apply { clear(); set(year, month - 1, 1); add(Calendar.MONTH, -back) }
            metrics(tripsOf(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, all))
        }.filter { it.trips >= MIN_TRIPS }
        val base = if (prior.size >= 2) {
            fun avg(sel: (Metrics) -> Float) = prior.map(sel).average().toFloat()
            Metrics(avg { it.trips }, avg { it.km }, avg { it.night }, avg { it.maxKm }, avg { it.shortTrips }, avg { it.lateTrips },
                avg { it.brakesPer100Km }, avg { it.panic }, avg { it.flipsPerTrip })
        } else null
        return MonthlySummary(year, month, metrics(ts), ts.sumOf { it.fastMin.toDouble() }.toFloat(), base)
    }

    fun thisMonth(): MonthlySummary = Calendar.getInstance().let { summary(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1) }

    fun lastMonth(): MonthlySummary = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -1) }
        .let { summary(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1) }

    companion object {
        const val BASE_MONTHS = 3
        const val MIN_TRIPS = 5f
        /** 이보다 짧으면 '나갔다가 금방 돌아온 운행' */
        const val SHORT_KM = 2f
        /** 자정부터 이 시각 전까지 출발하면 '한밤중 운행' */
        const val LATE_UNTIL = 4
        fun cal(ms: Long): Calendar = Calendar.getInstance().apply { timeInMillis = ms }
        fun monthKey(c: Calendar = Calendar.getInstance()) = "%d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }
}
