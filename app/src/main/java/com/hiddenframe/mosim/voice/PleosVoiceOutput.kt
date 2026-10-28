package com.hiddenframe.mosim.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * Pleos TextToSpeech SDK(3.3.1) 음성 출력.
 *
 * ON_DEVICE 모드를 써서 앱 등록(Client ID/Secret)이 필요 없고, 차 밖으로 문장이 나가지 않습니다.
 * Pleos TTS 서비스가 없는 기기(일반 에뮬레이터 등)에서는 초기화 실패 또는 3초 무응답 시
 * Android 기본 TTS([AndroidVoiceOutput])로 자동 전환합니다.
 * 기기에 온디바이스 음성 엔진이 없어 말하기 오류가 나면, 그 문장부터 기본 TTS로 이어서 말합니다.
 */
class PleosVoiceOutput(context: Context) : VoiceOutput {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<String>()

    private var tts: TextToSpeech? = null
    private var fallback: VoiceOutput? = null
    private var ready = false
    private var lastText: String? = null

    private val events = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onDone() = Unit
        override fun onStop() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "speak error: $errorType")
            main.post { useFallback("speak $errorType", force = true)?.let { f -> lastText?.let { f.speak(it) } } }
        }
    }

    init {
        try {
            val t = TextToSpeech(appContext, Mode.ON_DEVICE)
            tts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        if (fallback != null) return@post
                        runCatching { t.setLocale(Locale.KOREA); t.addEventListener(events) }
                        ready = true
                        Log.i(TAG, "Pleos TTS ready")
                        while (pending.isNotEmpty()) sayNow(pending.removeFirst())
                    }
                }
                override fun onFailure(errorType: ErrorType) {
                    main.post { useFallback("init $errorType") }
                }
            })
            main.postDelayed({ if (!ready) useFallback("init timeout") }, INIT_TIMEOUT_MS)
        } catch (t: Throwable) {
            useFallback("unavailable: ${t.message}")
        }
    }

    private fun useFallback(reason: String, force: Boolean = false): VoiceOutput? {
        if (fallback != null || (ready && !force)) return null
        Log.i(TAG, "Pleos TTS → Android TTS ($reason)")
        runCatching { tts?.removeEventListener(events); tts?.release() }
        tts = null; ready = false
        val f = AndroidVoiceOutput(appContext)
        fallback = f
        while (pending.isNotEmpty()) f.speak(pending.removeFirst())
        return f
    }

    override fun speak(text: String) {
        main.post {
            when {
                fallback != null -> fallback?.speak(text)
                ready -> sayNow(text)
                else -> pending.addLast(text)
            }
        }
    }

    private fun sayNow(text: String) {
        lastText = text
        try { tts?.speak(text, true) } catch (t: Throwable) { Log.w(TAG, "speak failed: ${t.message}") }
    }

    override fun shutdown() {
        runCatching { tts?.removeEventListener(events); tts?.release() }
        fallback?.shutdown()
    }

    companion object {
        private const val TAG = "Voice"
        private const val INIT_TIMEOUT_MS = 3000L
    }
}
