package com.hiddenframe.mosim

import android.app.Application
import com.hiddenframe.mosim.data.ShareSettings
import com.hiddenframe.mosim.data.TripLog
import com.hiddenframe.mosim.data.WeeklyStore
import com.hiddenframe.mosim.voice.PleosVoiceOutput
import com.hiddenframe.mosim.voice.VoiceOutput

class MosimApp : Application() {
    lateinit var share: ShareSettings
    lateinit var trips: TripLog
    lateinit var weekly: WeeklyStore
    lateinit var voice: VoiceOutput
    val state = AppState()

    override fun onCreate() {
        super.onCreate()
        share = ShareSettings(this)
        trips = TripLog(this)
        weekly = WeeklyStore(this)
        voice = PleosVoiceOutput(this)
    }
}
