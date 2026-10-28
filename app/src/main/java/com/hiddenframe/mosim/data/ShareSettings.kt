package com.hiddenframe.mosim.data

import android.content.Context
import com.hiddenframe.mosim.rules.Anomaly
import kotlin.random.Random

/**
 * 부모님이 직접 고르는 공유 범위. 기본값: 야간 외출·장거리·장시간 정차·차량 돌봄은 켬, 실시간 위치·운전 점수는 끔.
 * 가족 코드는 자녀가 알림 페이지를 열 때 쓰는 6자리 코드입니다.
 */
class ShareSettings(context: Context) {
    private val prefs = context.getSharedPreferences("share", Context.MODE_PRIVATE)

    val familyCode: String
        get() = prefs.getString("code", null) ?: newCode().also { prefs.edit().putString("code", it).apply() }

    var sharingEnabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    fun isShared(key: String): Boolean = prefs.getBoolean("k_$key", DEFAULTS[key] ?: false)
    fun setShared(key: String, on: Boolean) = prefs.edit().putBoolean("k_$key", on).apply()

    fun allows(a: Anomaly): Boolean = sharingEnabled && isShared(a.type.shareKey)

    fun revokeAll() = prefs.edit().putBoolean("enabled", false).apply()

    private fun newCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    companion object {
        val KEYS = listOf("night", "distance", "stop", "care", "location", "score")
        val LABELS = mapOf(
            "night" to "야간 외출",
            "distance" to "장거리 운행",
            "stop" to "장시간 정차",
            "care" to "배터리·연료 알림",
            "location" to "실시간 위치",
            "score" to "운전 점수(급제동 등)",
        )
        val DEFAULTS = mapOf(
            "night" to true, "distance" to true, "stop" to true, "care" to true,
            "location" to false, "score" to false,
        )
    }
}
