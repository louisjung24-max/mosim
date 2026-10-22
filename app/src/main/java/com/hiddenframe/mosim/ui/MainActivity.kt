package com.hiddenframe.mosim.ui

import android.os.Bundle
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import com.hiddenframe.mosim.MosimApp
import com.hiddenframe.mosim.UiState
import com.hiddenframe.mosim.data.ShareSettings
import com.hiddenframe.mosim.databinding.ActivityMainBinding
import com.hiddenframe.mosim.service.DriveMonitorService
import com.hiddenframe.mosim.vehicle.SimulatedSignalSource
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val app get() = application as MosimApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        DriveMonitorService.start(this)

        b.familyCode.text = app.share.familyCode
        buildShareSwitches()
        b.switchMaster.isChecked = app.share.sharingEnabled
        b.switchMaster.setOnCheckedChangeListener { _, on ->
            app.share.sharingEnabled = on
            app.voice.speak(if (on) "가족 공유를 켰습니다." else "가족 공유를 모두 껐습니다. 차 안 안내는 계속됩니다.")
            app.state.onVoice(if (on) "가족 공유를 켰습니다." else "가족 공유를 모두 껐습니다. 차 안 안내는 계속됩니다.")
            renderShareState(on)
        }
        renderShareState(app.share.sharingEnabled)

        b.btnDemoNight.setOnClickListener { SimulatedSignalSource.play(SimulatedSignalSource.SCENARIO_RAINY_NIGHT) }
        b.btnDemoTire.setOnClickListener { SimulatedSignalSource.play(SimulatedSignalSource.SCENARIO_TIRE) }
        b.btnDemoStop.setOnClickListener { SimulatedSignalSource.play(SimulatedSignalSource.SCENARIO_LONG_STOP) }
        b.btnDemoDay.setOnClickListener { SimulatedSignalSource.play(SimulatedSignalSource.SCENARIO_DAYTIME) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { app.state.ui.collect { render(it) } }
        }
    }

    private fun buildShareSwitches() {
        b.shareList.removeAllViews()
        for (key in ShareSettings.KEYS) {
            val sw = MaterialSwitch(this).apply {
                text = ShareSettings.LABELS[key]
                textSize = 22f
                setPadding(0, 18, 0, 18)
                isChecked = app.share.isShared(key)
                setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
                    app.share.setShared(key, on)
                    val label = ShareSettings.LABELS[key]
                    val said = if (on) "$label 공유를 켰습니다." else "$label 공유를 껐습니다."
                    app.voice.speak(said); app.state.onVoice(said)
                }
            }
            b.shareList.addView(sw)
        }
    }

    private fun renderShareState(enabled: Boolean) {
        b.shareList.alpha = if (enabled) 1f else 0.35f
        b.shareHint.text = if (enabled) "보여주고 싶은 항목만 켜 두세요. 언제든 끌 수 있습니다."
        else "가족 공유가 꺼져 있습니다. 차 안 음성 안내는 계속됩니다."
    }

    private fun render(s: UiState) {
        b.statusTitle.text = when {
            !s.ignitionOn -> "주차 중"
            s.hour != null && (s.hour >= 21 || s.hour < 5) -> "밤길 주행 중"
            else -> "주행 중"
        }
        b.statusMeta.text = buildString {
            append(if (s.ignitionOn) "${s.speedKmh} km/h" else "시동 꺼짐")
            if (s.wiperOn) append("  ·  비")
            s.hour?.let { append("  ·  ${String.format("%02d", it)}시") }
        }
        b.lastVoice.text = s.lastVoice
        b.log.text = s.log.joinToString("\n")
        b.weekly.text = "이번 주 운행 ${app.weekly.trips}회  ·  야간 ${app.weekly.nightTrips}회  ·  이상 신호 ${app.weekly.anomalies}건"
    }
}
