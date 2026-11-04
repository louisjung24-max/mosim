package com.hiddenframe.mosim.data

import android.content.Context
import com.hiddenframe.mosim.rules.Anomaly
import java.security.SecureRandom

/**
 * 부모님이 차 안에서 직접 고르는 공유 범위.
 * - 처음 설치하면 모든 항목이 꺼져 있습니다. 부모님이 켠 항목만, 그것도 차 화면에 먼저 보여 드린 뒤 보냅니다.
 * - 위치·속도 같은 원시 데이터를 보내는 항목은 아예 없습니다.
 * - 가족 연결 코드는 추측하기 어려운 10자리(약 50비트)이며, '연결 해제' 시 새 코드로 바뀝니다.
 */
class ShareSettings(context: Context) {
    private val prefs = context.getSharedPreferences("share", Context.MODE_PRIVATE)

    val familyCode: String
        get() = prefs.getString("code", null) ?: renewFamilyCode()

    /** 부모님이 첫 설정(공유 항목 고르기)을 마쳤는지 */
    var consentDone: Boolean
        get() = prefs.getBoolean("consent_done", false)
        set(v) = prefs.edit().putBoolean("consent_done", v).apply()

    var sharingEnabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    fun isShared(key: String): Boolean = prefs.getBoolean("k_$key", false)
    fun setShared(key: String, on: Boolean) = prefs.edit().putBoolean("k_$key", on).apply()

    /** 지금 이 순간 이 신호를 보내도 되는지. 보내기 직전에 한 번 더 확인합니다. */
    fun allows(a: Anomaly): Boolean = consentDone && sharingEnabled && isShared(a.type.shareKey)

    /** 가족 연결 해제: 새 코드 발급. 이전 코드로는 더 이상 아무것도 보내지 않습니다. */
    fun renewFamilyCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        val code = (1..CODE_LEN).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        prefs.edit().putString("code", code).apply()
        return code
    }

    companion object {
        const val CODE_LEN = 10
        val KEYS = listOf("night", "care", "stop", "distance", "score")
        val LABELS = mapOf(
            "night" to "평소와 다른 밤 외출",
            "care" to "배터리·연료 부족",
            "stop" to "운행 중 오래 멈춤",
            "distance" to "평소보다 먼 길",
            "score" to "급제동이 잦은 날",
        )
    }
}
