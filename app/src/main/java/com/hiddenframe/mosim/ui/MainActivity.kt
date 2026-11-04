package com.hiddenframe.mosim.ui

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hiddenframe.mosim.BuildConfig
import com.hiddenframe.mosim.MosimApp
import com.hiddenframe.mosim.UiState
import com.hiddenframe.mosim.alert.AlertGate
import com.hiddenframe.mosim.alert.AlertSender
import com.hiddenframe.mosim.alert.Delivery
import com.hiddenframe.mosim.data.ShareSettings
import com.hiddenframe.mosim.databinding.ActivityMainBinding
import com.hiddenframe.mosim.databinding.ViewLogRowBinding
import com.hiddenframe.mosim.databinding.ViewShareRowBinding
import com.hiddenframe.mosim.service.DriveMonitorService
import com.hiddenframe.mosim.vehicle.PleosSignalSource
import com.hiddenframe.mosim.vehicle.SimulatedSignalSource
import kotlinx.coroutines.launch

/**
 * 대화면 한 장짜리 화면.
 * - 왼쪽: 지금 상태, "부모님 먼저" 확인 카드(보내지 않기), 지금 안내, 오늘의 기록
 * - 오른쪽: 가족 공유 항목(처음엔 모두 꺼짐), 가족 연결(QR·해제), 이번 주 통계
 * 공유 설정·연결 해제는 운행 중(기어 P가 아님)에는 잠깁니다. "보내지 않기"는 멈춰 있을 때 누를 수 있습니다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val app get() = application as MosimApp
    private val shareRows = mutableListOf<Pair<String, ViewShareRowBinding>>()
    private var lastUi = UiState()
    private var pendingTop: AlertGate.Pending? = null
    private var demoMode = BuildConfig.DEBUG

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        DriveMonitorService.start(this)
        requestPermissionsIfNeeded()

        b.statTrips.statLabel.text = "이번 주 운행"
        b.statNight.statLabel.text = "밤 운행"
        b.statAlerts.statLabel.text = "가족에게 보낸 신호"
        buildShareRows()
        renderFamily()
        renderLocks()

        b.switchMaster.isChecked = app.share.sharingEnabled
        b.switchMaster.setOnCheckedChangeListener { _, on ->
            app.share.sharingEnabled = on
            if (!on) app.gate.cancelAll()
            say(if (on) "가족 공유를 켰습니다." else "가족 공유를 모두 껐습니다. 차 안 안내는 계속됩니다.")
            renderLocks()
        }
        b.btnConsent.setOnClickListener {
            app.share.consentDone = true
            val chosen = ShareSettings.KEYS.filter { app.share.isShared(it) }.mapNotNull { ShareSettings.LABELS[it] }
            say(if (chosen.isEmpty()) "아무것도 보내지 않도록 시작합니다. 언제든 바꾸실 수 있어요."
                else "${chosen.joinToString(", ")}만 가족에게 보여 드릴게요. 보내기 전에 이 화면에 먼저 띄워 드립니다.")
            renderLocks()
        }
        b.btnVeto.setOnClickListener { pendingTop?.let { app.gate.veto(it.id) } }
        b.btnUnlink.setOnClickListener { unlinkFamily() }

        // 시연 모드: 디버그 빌드는 기본 표시, 릴리스는 앱 이름을 길게 누르면 켜고 끕니다(심사·PT용).
        b.appTitle.setOnLongClickListener {
            demoMode = !demoMode
            b.demoRow.visibility = if (demoMode) View.VISIBLE else View.GONE
            Toast.makeText(this, if (demoMode) "시연 모드" else "시연 모드 끔", Toast.LENGTH_SHORT).show()
            true
        }
        b.demoRow.visibility = if (demoMode) View.VISIBLE else View.GONE
        b.btnDemoNight.setOnClickListener { SimulatedSignalSource.play(SimulatedSignalSource.SCENARIO_RAINY_NIGHT) }
        b.btnDemoTire.setOnClickListener { SimulatedSignalSource.play(SimulatedSignalSource.SCENARIO_BATTERY) }
        b.btnDemoStop.setOnClickListener { SimulatedSignalSource.play(SimulatedSignalSource.SCENARIO_LONG_STOP) }
        b.btnDemoDay.setOnClickListener { SimulatedSignalSource.play(SimulatedSignalSource.SCENARIO_DAYTIME) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.state.ui.collect { render(it) } }
                launch { app.gate.pending.collect { renderPending(it) } }
            }
        }
    }

    private fun say(text: String) { app.voice.speak(text); app.state.onVoice(text) }

    /** 급제동 판정에 쓰는 차속, 차속이 없는 차량용 위치. 둘 다 차 안에서만 쓰고 가족에게 보내지 않습니다. */
    private fun requestPermissionsIfNeeded() {
        val need = listOf(PleosSignalSource.PERM_FUSED_LOCATION, PERM_CAR_SPEED)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) runCatching { requestPermissions(need.toTypedArray(), REQ_PERMS) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            DriveMonitorService.start(this, DriveMonitorService.ACTION_PERMISSIONS_GRANTED)
        }
        if (permissions.indexOf(PERM_CAR_SPEED).let { it >= 0 && grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED }) {
            app.state.note("차속 권한이 없어 급제동·오래 멈춤 판단이 제한됩니다.")
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
            row.rowDivider.visibility = if (i == ShareSettings.KEYS.lastIndex) View.INVISIBLE else View.VISIBLE
            row.rowSwitch.setOnCheckedChangeListener { _, on ->
                app.share.setShared(key, on)
                if (app.share.consentDone) {
                    val label = ShareSettings.LABELS[key]
                    say(if (on) "$label 공유를 켰습니다." else "$label 공유를 껐습니다. 이제부터 보내지 않습니다.")
                }
            }
            row.root.setOnClickListener { if (row.rowSwitch.isEnabled) row.rowSwitch.toggle() }
            b.shareList.addView(row.root)
            shareRows.add(key to row)
        }
    }

    /** 공유 설정은 주차(P) 중에만 바꿀 수 있습니다. 운전 중 화면 조작을 줄이기 위해서입니다. */
    private fun renderLocks() {
        val parked = !lastUi.driving
        val enabled = app.share.sharingEnabled
        b.switchMaster.isEnabled = parked
        shareRows.forEach { (_, r) -> r.rowSwitch.isEnabled = parked && enabled }
        b.shareList.alpha = if (enabled) 1f else 0.4f
        b.btnUnlink.isEnabled = parked
        b.btnConsent.isEnabled = parked
        b.consentCard.visibility = if (app.share.consentDone) View.GONE else View.VISIBLE
        b.shareHint.text = when {
            !parked -> "운행 중에는 바꿀 수 없어요. 주차(P) 후 바꿔 주세요."
            !enabled -> "가족 공유가 꺼져 있습니다. 차 안 안내는 계속됩니다."
            else -> "켜신 항목만, 이 화면에 먼저 보여 드린 뒤 보냅니다."
        }
    }

    private fun renderFamily() {
        val code = app.share.familyCode
        b.familyCode.text = code.chunked(5).joinToString(" ")
        val url = AlertSender.familyPageUrl(code)
        if (url == null) {
            b.familyQr.visibility = View.GONE
            b.familyHint.text = "알림 서버가 설정되지 않아 가족 연결을 쓸 수 없습니다"
        } else {
            b.familyQr.setImageBitmap(qr(url, 336))
            b.familyQr.visibility = View.VISIBLE
            b.familyHint.text = "자녀 휴대폰으로 QR을 찍으면 연결됩니다"
        }
    }

    private fun unlinkFamily() {
        if (lastUi.driving) return
        app.gate.cancelAll()
        val old = app.share.familyCode
        lifecycleScope.launch {
            val r = AlertSender.unlink(old)
            app.share.renewFamilyCode()
            renderFamily()
            say(when (r) {
                Delivery.SENT -> "가족 연결을 해제하고, 그동안 보낸 기록을 지웠습니다. 새 QR로 다시 연결할 수 있어요."
                Delivery.NOT_CONFIGURED -> "가족 연결을 해제했습니다. 새 QR로 다시 연결할 수 있어요."
                else -> "가족 연결은 해제했지만 서버 기록을 지우지 못했습니다. 연결되면 다시 시도해 주세요."
            })
        }
    }

    private fun renderPending(list: List<AlertGate.Pending>) {
        val p = list.firstOrNull()
        pendingTop = p
        if (p == null) { b.pendingCard.visibility = View.GONE; return }
        b.pendingCard.visibility = View.VISIBLE
        b.pendingTitle.text = p.anomaly.title
        val more = if (list.size > 1) " · 대기 ${list.size - 1}건 더" else ""
        b.pendingMeta.text = "${p.secondsLeft}초 뒤 가족에게 보냅니다$more"
        renderVetoButton()
    }

    private fun renderVetoButton() {
        val canTouch = lastUi.stopped
        b.btnVeto.isEnabled = canTouch
        b.btnVeto.text = if (canTouch) "보내지 않기" else "멈추면 누를 수 있어요"
    }

    private fun render(s: UiState) {
        val drivingChanged = s.driving != lastUi.driving
        lastUi = s
        if (drivingChanged) renderLocks()
        renderVetoButton()
        val night = s.hour != null && (s.hour >= 21 || s.hour < 5)
        val title = when {
            !s.driving -> "주차 중"
            night -> "밤길 운행 중"
            else -> "운행 중"
        }
        b.statusTitle.text = title
        b.headerStatus.text = title
        b.statusMeta.text = buildString {
            append(if (s.driving) "${s.speedKmh} km/h" else "P (주차)")
            if (s.wiperOn) append("  ·  비")
            s.hour?.let { append("  ·  ${String.format("%02d", it)}시 출발") }
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

    private fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val REQ_PERMS = 41
        private const val PERM_CAR_SPEED = "android.car.permission.CAR_SPEED"
    }
}
