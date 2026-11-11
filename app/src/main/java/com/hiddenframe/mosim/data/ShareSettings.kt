package com.hiddenframe.mosim.data

import android.content.Context
import com.hiddenframe.mosim.rules.Anomaly
import java.security.SecureRandom

/**
 * 가족과 나누는 범위. 처음 한 번 부모님이 "좋아요"를 누르면 그다음부터는 자동입니다.
 * - 매달 운전 요약: 매달 1일 첫 출발 때 지난달 요약 한 장
 * - 배터리·연료 부족: 그때그때
 * 보낸 뒤에는 차가 음성으로 알려 드리고, 끄는 것은 이 설정에서 한 번이면 됩니다. 위치를 보내는 항목은 없습니다.
 */
class ShareSettings(context: Context) {
    private val prefs = context.getSharedPreferences("share", Context.MODE_PRIVATE)

    val familyCode: String
        get() = prefs.getString("code", null) ?: renewFamilyCode()

    /** 부모님이 "좋아요, 매달 보내 주세요"를 눌렀는지 */
    var consentDone: Boolean
        get() = prefs.getBoolean("consent_done", false)
        set(v) {
            prefs.edit().putBoolean("consent_done", v).apply()
            if (v) KEYS.forEach { setShared(it, true) }
        }

    fun isShared(key: String): Boolean = consentDone && prefs.getBoolean("k_$key", false)
    fun setShared(key: String, on: Boolean) = prefs.edit().putBoolean("k_$key", on).apply()

    val monthlyOn: Boolean get() = isShared(KEY_MONTHLY)
    fun allows(a: Anomaly): Boolean = isShared(a.type.shareKey)

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
        const val KEY_MONTHLY = "monthly"
        const val KEY_CARE = "care"
        val KEYS = listOf(KEY_MONTHLY, KEY_CARE)
        val LABELS = mapOf(
            KEY_MONTHLY to "매달 운전 요약",
            KEY_CARE to "배터리·연료 부족 알림",
        )
    }
}
