package com.hiddenframe.mosim

import android.app.Application
import com.hiddenframe.mosim.alert.AlertGate
import com.hiddenframe.mosim.data.Baseline
import com.hiddenframe.mosim.data.ShareSettings
import com.hiddenframe.mosim.data.WeeklyStore
import com.hiddenframe.mosim.voice.PleosVoiceOutput
import com.hiddenframe.mosim.voice.VoiceOutput

class MosimApp : Application() {
    lateinit var baseline: Baseline
    lateinit var share: ShareSettings
    lateinit var weekly: WeeklyStore
    lateinit var voice: VoiceOutput
    lateinit var gate: AlertGate
    val state = AppState()

    override fun onCreate() {
        super.onCreate()
        baseline = Baseline(this)
        share = ShareSettings(this)
        weekly = WeeklyStore(this)
        voice = PleosVoiceOutput(this)
        gate = AlertGate(share, isStopped = { state.ui.value.stopped }) { a, outcome ->
            if (outcome == AlertGate.Outcome.SENT) weekly.addAnomaly()
            state.onOutcome(a, outcome)
        }
    }
}
