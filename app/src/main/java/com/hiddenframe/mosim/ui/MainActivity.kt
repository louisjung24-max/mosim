package com.hiddenframe.mosim.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hiddenframe.mosim.MosimApp
import com.hiddenframe.mosim.UiState
import com.hiddenframe.mosim.data.ShareSettings
import com.hiddenframe.mosim.databinding.ActivityMainBinding
import com.hiddenframe.mosim.databinding.ViewLogRowBinding
import com.hiddenframe.mosim.databinding.ViewShareRowBinding
import com.hiddenframe.mosim.service.DriveMonitorService
import com.hiddenframe.mosim.vehicle.PleosSignalSource
import com.hiddenframe.mosim.vehicle.SimulatedSignalSource
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val app get() = application as MosimApp
    private val shareRows = mutableListOf<ViewShareRowBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        DriveMonitorService.start(this)
        requestLocationIfNeeded()

        b.familyCode.text = app.share.familyCode
        b.statTrips.statLabel.text = "이번 주 운행"
        b.statNight.statLabel.text = "야간"
        b.statAlerts.statLabel.text = "전달한 신호"
        buildShareRows()

        b.switchMaster.isChecked = app.share.sharingEnabled
        b.switchMaster.setOnCheckedChangeListener { _, on ->
            app.share.sharingEnabled = on
            val said = if (on) "가족 공유를 켰습니다." else "가족 공유를 모두 껐습니다. 차 안 안내는 계속됩니다."
            app.voice.speak(said); app.state.onVoice(said)
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

    /** 급제동·장시간 정차 판정용. 위치는 차 안에서만 쓰고 가족에게 보내지 않습니다. */
    private fun requestLocationIfNeeded() {
        val perm = PleosSignalSource.PERM_FUSED_LOCATION
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return
        runCatching { requestPermissions(arrayOf(perm), REQ_LOCATION) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            DriveMonitorService.start(this, DriveMonitorService.ACTION_LOCATION_GRANTED)
        }
    }

    private fun buildShareRows() {
        b.shareList.removeAllViews(); shareRows.clear()
        val inflater = LayoutInflater.from(this)
        ShareSettings.KEYS.forEachIndexed { i, key ->
            val row = ViewShareRowBinding.inflate(inflater, b.shareList, false)
            row.rowLabel.text = ShareSettings.LABELS[key]
            row.rowSwitch.isSaveEnabled = false
            row.rowSwitch.isChecked = app.share.isShared(key)
            row.rowDivider.visibility = if (i == ShareSettings.KEYS.lastIndex) android.view.View.INVISIBLE else android.view.View.VISIBLE
            row.rowSwitch.setOnCheckedChangeListener { _, on ->
                app.share.setShared(key, on)
                val label = ShareSettings.LABELS[key]
                val said = if (on) "$label 공유를 켰습니다." else "$label 공유를 껐습니다."
                app.voice.speak(said); app.state.onVoice(said)
            }
            row.root.setOnClickListener { row.rowSwitch.toggle() }
            b.shareList.addView(row.root)
            shareRows.add(row)
        }
    }

    private fun renderShareState(enabled: Boolean) {
        b.shareList.alpha = if (enabled) 1f else 0.4f
        shareRows.forEach { it.rowSwitch.isEnabled = enabled }
        b.shareHint.text = if (enabled) "보여주고 싶은 항목만 켜 두세요. 언제든 끌 수 있습니다."
        else "공유가 꺼져 있습니다. 차 안 안내는 계속됩니다."
    }

    private fun render(s: UiState) {
        val night = s.hour != null && (s.hour >= 21 || s.hour < 5)
        val title = when {
            !s.ignitionOn -> "주차 중"
            night -> "밤길 주행 중"
            else -> "주행 중"
        }
        b.statusTitle.text = title
        b.headerStatus.text = title
        b.statusMeta.text = buildString {
            append(if (s.ignitionOn) "${s.speedKmh} km/h" else "시동 꺼짐")
            if (s.wiperOn) append("  ·  비")
            s.hour?.let { append("  ·  ${String.format("%02d", it)}시") }
        }
        b.lastVoice.text = s.lastVoice

        b.logList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        s.log.forEach { (t, text) ->
            val row = ViewLogRowBinding.inflate(inflater, b.logList, false)
            row.logTime.text = t
            row.logText.text = text
            b.logList.addView(row.root)
        }

        b.statTrips.statValue.text = "${app.weekly.trips}회"
        b.statNight.statValue.text = "${app.weekly.nightTrips}회"
        b.statAlerts.statValue.text = "${app.weekly.anomalies}건"
    }

    companion object {
        private const val REQ_LOCATION = 41
    }
}
