package com.hiddenframe.mosim.alert

import android.util.Log
import com.hiddenframe.mosim.BuildConfig
import com.hiddenframe.mosim.rules.Anomaly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 전송 결과. 화면에는 [SENT]일 때만 "가족에게 전송됨"이라고 표시합니다. */
enum class Delivery { SENT, NOT_CONFIGURED, REJECTED, FAILED }

/**
 * 부모님이 허용하고 확인한 이상 신호만 알림 서버(Cloudflare Workers)로 보냅니다.
 * 보내는 것은 {가족 코드, 유형, 제목, 한 줄 설명, 시각}뿐이며 속도·위치 같은 원시 데이터는 없습니다.
 * 서버 인증 토큰은 빌드 시 GitHub Secret(MOSIM_DEVICE_TOKEN)으로만 주입합니다.
 */
object AlertSender {
    private const val TAG = "AlertSender"

    val configured: Boolean get() = BuildConfig.ALERT_ENDPOINT.isNotBlank()
    fun familyPageUrl(code: String): String? = if (configured) "${BuildConfig.ALERT_ENDPOINT}/f/$code" else null

    suspend fun send(familyCode: String, a: Anomaly): Delivery = post("/api/event", JSONObject()
        .put("code", familyCode)
        .put("type", a.type.name.lowercase())
        .put("title", a.title)
        .put("body", a.body)
        .put("ts", System.currentTimeMillis()))

    /** 가족 연결 해제: 서버에 저장된 이 가족 코드의 기록을 지웁니다. */
    suspend fun unlink(familyCode: String): Delivery = post("/api/unlink", JSONObject().put("code", familyCode))

    private suspend fun post(path: String, body: JSONObject): Delivery = withContext(Dispatchers.IO) {
        if (!configured) { Log.i(TAG, "endpoint not set; skip $path"); return@withContext Delivery.NOT_CONFIGURED }
        try {
            val conn = (URL("${BuildConfig.ALERT_ENDPOINT}$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000; readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (BuildConfig.DEVICE_TOKEN.isNotBlank()) setRequestProperty("x-device-token", BuildConfig.DEVICE_TOKEN)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            Log.i(TAG, "$path -> $code")
            when (code) {
                in 200..299 -> Delivery.SENT
                in 400..499 -> Delivery.REJECTED
                else -> Delivery.FAILED
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$path failed: ${t.message}"); Delivery.FAILED
        }
    }
}
