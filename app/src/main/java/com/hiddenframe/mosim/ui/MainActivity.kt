package com.hiddenframe.mosim.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import com.hiddenframe.mosim.R
import com.hiddenframe.mosim.UiState
import com.hiddenframe.mosim.alert.AlertSender
import com.hiddenframe.mosim.alert.Delivery
import com.hiddenframe.mosim.data.MonthlySummary
import com.hiddenframe.mosim.data.ShareSettings
import com.hiddenframe.mosim.databinding.ViewStatBinding
import java.util.Calendar
import com.hiddenframe.mosim.databinding.ActivityMainBinding
import com.hiddenframe.mosim.databinding.ViewLogRowBinding
import com.hiddenframe.mosim.databinding.ViewShareRowBinding
import com.hiddenframe.mosim.service.DriveMonitorService
import com.hiddenframe.mosim.vehicle.PleosSignalSource
import com.hiddenframe.mosim.vehicle.SimulatedSignalSource
import kotlinx.coroutines.launch

/**
 * 대화면 한 장짜리 화면.
 * - 왼쪽: 지금 상태, 지금 안내, 오늘의 기록
 * - 오른쪽: 나의 운전 기록(가족이 받는 요약과 같은 내용), 가족과 나누기(한 번 동의 → 매달 자동, 끄기·연결 해제)
 * 설정과 연결 해제는 운행 중(기어 P가 아님)에는 잠깁니다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val app get() = application as MosimApp
    private val shareRows = mutableListOf<Pair<String, ViewShareRowBinding>>()
    private var lastUi = UiState()
    private var demoMode = BuildConfig.DEBUG

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        DriveMonitorService.start(this)
        requestPermissionsIfNeeded()

        b.statTrips.statLabel.text = "운행"
        b.statNight.statLabel.text = "밤 운전"
        b.statFast.statLabel.text = "주차 때 앞뒤로 고쳐 대기 (운행당)"
        b.statBrake.statLabel.text = "급제동 (100km당)"
        buildShareRows()
        renderFamily()
        renderLocks()

        b.btnConsent.setOnClickListener {
            app.share.consentDone = true
            buildShareRows()
            say("고맙습니다. 매달 1일 첫 출발 때 지난달 운전 요약을 가족에게 보내고, 보낸 뒤 알려 드릴게요.")
            renderLocks()
        }
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
            }
        }
    }

    // 디버그(CI 시연) 빌드 전용: 스크립트가 화면 버튼을 누른 것과 같은 동작을 재현합니다. 릴리스에는 등록되지 않습니다.
    private val demoUi = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (val t = i.getStringExtra("click") ?: return) {
                "consent" -> b.btnConsent.performClick()
                "unlink" -> b.btnUnlink.performClick()
                else -> if (t.startsWith("share:")) shareRows.firstOrNull { it.first == t.removePrefix("share:") }?.second?.root?.performClick()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (BuildConfig.DEBUG) ContextCompat.registerReceiver(this, demoUi, IntentFilter("com.hiddenframe.mosim.UI"), ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStop() {
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(demoUi) }
        super.onStop()
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
        if (!app.share.consentDone) return
        val inflater = LayoutInflater.from(this)
        ShareSettings.KEYS.forEachIndexed { i, key ->
            val row = ViewShareRowBinding.inflate(inflater, b.shareList, false)
            row.rowLabel.text = ShareSettings.LABELS[key]
            row.rowSwitch.isSaveEnabled = false
            row.rowSwitch.isChecked = app.share.isShared(key)
            row.rowDivider.visibility = if (i == ShareSettings.KEYS.lastIndex) View.INVISIBLE else View.VISIBLE
            row.rowSwitch.setOnCheckedChangeListener { _, on ->
                app.share.setShared(key, on)
                val label = ShareSettings.LABELS[key]
                say(if (on) "${label}을 다시 보냅니다." else "${label}을 보내지 않습니다. 언제든 다시 켤 수 있어요.")
                renderLocks()
            }
            row.root.setOnClickListener { if (row.rowSwitch.isEnabled) row.rowSwitch.toggle() }
            b.shareList.addView(row.root)
            shareRows.add(key to row)
        }
    }

    /** 공유 설정은 주차(P) 중에만 바꿀 수 있습니다. 운전 중 화면 조작을 줄이기 위해서입니다. */
    private fun renderLocks() {
        val parked = !lastUi.driving
        val consent = app.share.consentDone
        shareRows.forEach { (_, r) -> r.rowSwitch.isEnabled = parked }
        b.btnUnlink.isEnabled = parked
        b.btnConsent.isEnabled = parked
        b.consentCard.visibility = if (consent) View.GONE else View.VISIBLE
        b.familyCard.visibility = if (consent) View.VISIBLE else View.GONE
        b.shareHint.text = when {
            !consent -> "아직 아무것도 보내지 않습니다."
            !parked -> "운행 중에는 바꿀 수 없어요. 주차(P) 후 바꿔 주세요."
            app.share.monthlyOn -> "다음 요약: ${nextMonthLabel()} 1일 첫 출발 때 보내고, 보낸 뒤 알려 드려요."
            else -> "매달 요약을 보내지 않습니다."
        }
    }

    private fun nextMonthLabel(): String = "${Calendar.getInstance().apply { add(Calendar.MONTH, 1) }.get(Calendar.MONTH) + 1}월"

    /** 나의 운전 기록: 가족에게 가는(또는 갈) 지난달 요약을 그대로 보여 드립니다. */
    private fun renderMirror() {
        val last = app.trips.lastMonth()
        val m: MonthlySummary = if (last.trips > 0) last else app.trips.thisMonth()
        val base = m.base
        b.mirrorTitle.text = "나의 운전 기록 · ${m.label}"
        val now = app.trips.thisMonth()
        b.mirrorHint.text = "가족이 받는 요약과 같은 내용입니다" + if (m !== last || now.trips == 0) "" else " · 이번 달 지금까지 ${now.trips}회"
        fun tile(t: ViewStatBinding, value: String, delta: String?) { t.statValue.text = value; t.statDelta.text = delta ?: "" }
        tile(b.statTrips, "${m.trips}회", base?.let { "평소 ${Math.round(it.trips)}회" } ?: "${Math.round(m.now.km)}km")
        tile(b.statNight, "${Math.round(m.now.night)}회", base?.let { "평소 ${Math.round(it.night)}회" })
        tile(b.statFast, "%.1f번".format(m.now.flipsPerTrip), base?.let { "평소 %.1f번".format(it.flipsPerTrip) })
        tile(b.statBrake, "%.1f번".format(m.now.brakesPer100Km), base?.let { "평소 %.1f번".format(it.brakesPer100Km) })
        val ch = m.changes()
        b.mirrorLine.text = buildString {
            append(m.verdict())
            ch.forEach { append("\n· ${it.text} (${it.detail})") }
        }
        b.mirrorLine.setTextColor(getColor(if (ch.isEmpty()) R.color.informative_active else R.color.text_primary))
        b.mirrorNote.text = if (ch.isEmpty()) "남과 비교하지 않고, 지난 석 달의 나와만 비교합니다." else "남과 비교하지 않고 지난 석 달의 나와만 비교합니다. 진단이 아니라 달라진 점입니다."
    }

    private fun renderFamily() {
        val code = app.share.familyCode
        if (BuildConfig.DEBUG) android.util.Log.i("MosimDemo", "family code $code")
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

    private fun render(s: UiState) {
        val drivingChanged = s.driving != lastUi.driving
        lastUi = s
        if (drivingChanged) renderLocks()
        renderMirror()
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
