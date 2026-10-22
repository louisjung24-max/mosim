package com.hiddenframe.mosim.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** 차 안 음성 출력. Pleos TextToSpeech SDK 승인 후 같은 인터페이스로 교체합니다. */
interface VoiceOutput {
    fun speak(text: String)
    fun shutdown()
}

/** Android 기본 TTS 구현. 한국어 음성이 없는 기기(일부 에뮬레이터)에서는 화면 자막만 표시됩니다. */
class AndroidVoiceOutput(context: Context) : VoiceOutput {
    private var ready = false
    private val pending = ArrayDeque<String>()
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = try { tts.setLanguage(Locale.KOREAN) } catch (t: Throwable) { TextToSpeech.LANG_NOT_SUPPORTED }
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                tts.setSpeechRate(0.92f)
                while (ready && pending.isNotEmpty()) sayNow(pending.removeFirst())
            } else Log.w("Voice", "TTS init failed")
        }
    }

    override fun speak(text: String) {
        if (ready) sayNow(text) else pending.addLast(text)
    }

    private fun sayNow(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "mosim-${System.nanoTime()}")
    }

    override fun shutdown() { if (::tts.isInitialized) tts.shutdown() }
}
