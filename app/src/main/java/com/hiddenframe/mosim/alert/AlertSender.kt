package com.hiddenframe.mosim.alert

import android.util.Log
import com.hiddenframe.mosim.BuildConfig
import com.hiddenframe.mosim.rules.Anomaly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 부모님이 허용한 이상 신호만 알림 서버(Cloudflare Workers)로 보냅니다.
 * 서버는 자녀 웹 페이지 갱신과 카카오톡 전송을 담당합니다. 원시 주행 데이터는 보내지 않습니다.
 */
object AlertSender {
    private const val TAG = "AlertSender"

    suspend fun send(familyCode: String, a: Anomaly): Boolean = withContext(Dispatchers.IO) {
        val endpoint = BuildConfig.ALERT_ENDPOINT
        if (endpoint.isBlank()) { Log.i(TAG, "endpoint not set; skip ${a.type}"); return@withContext false }
        val body = JSONObject()
            .put("code", familyCode)
            .put("type", a.type.name.lowercase())
            .put("title", a.title)
            .put("body", a.body)
            .put("ts", System.currentTimeMillis())
            .toString()
        try {
            val conn = (URL("$endpoint/api/event").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000; readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "send failed: ${t.message}"); false
        }
    }
}
