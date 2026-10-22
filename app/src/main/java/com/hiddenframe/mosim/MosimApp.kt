package com.hiddenframe.mosim

import android.app.Application
import com.hiddenframe.mosim.data.Baseline
import com.hiddenframe.mosim.data.ShareSettings
import com.hiddenframe.mosim.data.WeeklyStore
import com.hiddenframe.mosim.voice.AndroidVoiceOutput
import com.hiddenframe.mosim.voice.VoiceOutput

class MosimApp : Application() {
    lateinit var baseline: Baseline
    lateinit var share: ShareSettings
    lateinit var weekly: WeeklyStore
    lateinit var voice: VoiceOutput
    val state = AppState()

    override fun onCreate() {
        super.onCreate()
        baseline = Baseline(this)
        share = ShareSettings(this)
        weekly = WeeklyStore(this)
        voice = AndroidVoiceOutput(this)
    }
}
