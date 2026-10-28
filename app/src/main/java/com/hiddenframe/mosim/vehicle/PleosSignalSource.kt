package com.hiddenframe.mosim.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.Calendar
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pleos Connect 실제 차량 신호 공급원 (Vehicle SDK 2.3.2 + FusedLocation SDK 1.1.3).
 *
 * 공개 API만 사용합니다.
 * - 운행 시작/종료: 기어 P ↔ D/R (Vehicle SDK에 시동 상태 API가 없어 기어로 판단)
 * - 비: 앞유리 와이퍼 상태
 * - 차량 관리: 연료 잔량 또는 전기차 배터리(SOC) 10% 이하
 * - 장거리: 이번 운행 주행 거리
 * - 급제동: 표준 AAOS 차속([CarApiSignalSource])이 기본. 차속이 없는 차량에서는 위치 변화로 계산한 감속도 ≥ 3.5 m/s² + 브레이크 페달로 대체
 * - 장시간 정차: 차속(표준 AAOS 또는 위치 계산)으로 서비스에서 판정
 *
 * 위치·속도는 차 안에서 판정에만 쓰고 밖으로 보내지 않습니다.
 * Pleos 차량 서비스가 없는 기기(일반 에뮬레이터)에서는 [connected]가 false로 남고 조용히 비활성화됩니다.
 */
class PleosSignalSource(private val context: Context) : VehicleSignalSource {
    override val name = "pleos-vehicle-sdk"

    private val _events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VehicleEvent> = _events

    /** Pleos 차량 서비스에서 한 번이라도 응답을 받았는지 */
    @Volatile var connected = false
        private set

    private var vehicle: Vehicle? = null
    private var location: FusedLocation? = null
    private val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)

    // 운행 상태
    private var driving = false
    private var pedalSeenThisTrip = false
    private var pedalPressedAt = 0L
    private var fuelWarned = false
    private var lastFix: LocationData? = null
    private var lastSpeedMps = -1f

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { gear ?: return; connected = true; onGear(gear) }
        override fun onFailed(e: Exception) = warn("gear", e)
    }
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) {
            state ?: return
            connected = true
            _events.tryEmit(VehicleEvent.Wiper(on = state == WiperState.ON))
        }
        override fun onFailed(e: Exception) = warn("wiper", e)
    }
    private val fuelListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(percent: Int?) {
            val p = percent ?: return
            connected = true
            if (p <= FUEL_LOW_PERCENT && driving && !fuelWarned) {
                fuelWarned = true
                _events.tryEmit(VehicleEvent.FuelLow(p))
            }
        }
        override fun onFailed(e: Exception) = warn("fuel", e)
    }
    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(percent: Int?) {
            val p = percent ?: return
            connected = true
            if (p <= FUEL_LOW_PERCENT && driving && !fuelWarned) {
                fuelWarned = true
                _events.tryEmit(VehicleEvent.BatteryLow(p))
            }
        }
        override fun onFailed(e: Exception) = warn("soc", e)
    }
    private val tripDistanceListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(km: Float?) {
            val d = km ?: return
            if (driving) _events.tryEmit(VehicleEvent.Odometer(d))
        }
        override fun onFailed(e: Exception) = warn("distance", e)
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
            if (pressed == true) { pedalSeenThisTrip = true; pedalPressedAt = System.currentTimeMillis() }
        }
        override fun onFailed(e: Exception) = warn("brake", e)
    }
    private val locationListener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) { data?.let { onFix(it) } }
        override fun onError(e: Exception) = warn("location", e)
    }

    override fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        val v = try {
            Vehicle(context.applicationContext).also { it.initialize() }
        } catch (t: Throwable) {
            Log.i(TAG, "Pleos Vehicle SDK unavailable: ${t.message}"); null
        }
        if (v != null) {
            vehicle = v
            // 차종마다 지원 항목이 달라(내연기관/전기차 등) 항목별로 따로 등록하고, 실패한 항목만 건너뜁니다.
            reg("gear-now") { v.getGear().getCurrentGearSelectionState({ g -> g?.let { connected = true; onGear(it) } }, { warn("gear-now", it) }) }
            reg("gear") { v.getGear().registerGearSelection(gearListener) }
            reg("wiper") { v.getWiper().registerWiperState(windshield, wiperListener) }
            reg("fuel") { v.getFuel().registerFuelPercent(fuelListener) }
            reg("soc") { v.getEvBattery().registerEvBatteryStateOfCharge(socListener) }
            reg("distance") { v.getOdometer().registerTripCurrentDrivingDistance(tripDistanceListener) }
            reg("brake") { v.getBrake().registerBrakePedalStatus(brakeListener) }
        }
        startLocation()
    }

    /** 위치 권한(pleos.car.permission.FUSED_LOCATION)이 허용된 뒤 다시 불러도 안전합니다. */
    fun startLocation() {
        if (location != null) return
        if (context.checkSelfPermission(PERM_FUSED_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val l = FusedLocation(context.applicationContext)
            l.initialize()
            if (!l.isSupported()) { l.release(); return }
            l.registerFusedLocationCallback(locationListener)
            location = l
        } catch (t: Throwable) {
            Log.i(TAG, "Pleos FusedLocation unavailable: ${t.message}")
        }
    }

    private fun onGear(gear: VehicleGear) {
        val moving = gear != VehicleGear.GEAR_PARK && gear != VehicleGear.GEAR_UNKNOWN && gear != VehicleGear.GEAR_NEUTRAL
        if (moving && !driving) {
            driving = true
            fuelWarned = false; pedalSeenThisTrip = false; lastFix = null; lastSpeedMps = -1f
            _events.tryEmit(VehicleEvent.Ignition(on = true, hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)))
        } else if (gear == VehicleGear.GEAR_PARK && driving) {
            driving = false
            _events.tryEmit(VehicleEvent.Ignition(on = false))
        }
    }

    private fun onFix(fix: LocationData) {
        val prev = lastFix
        lastFix = fix
        if (!driving || prev == null) return
        val dt = (fix.timestampMillis - prev.timestampMillis) / 1000f
        if (dt < MIN_DT_S || dt > MAX_DT_S) { lastSpeedMps = -1f; return }
        // 표준 AAOS 차속이 들어오는 차량이면 그쪽을 쓰고, 위치 기반 계산은 대체 경로로만 둡니다.
        if (CarApiSignalSource.vehicleSpeedFresh()) { lastSpeedMps = -1f; return }
        val mps = distanceMeters(prev, fix) / dt
        Log.d(TAG, "fix dt=${"%.2f".format(dt)}s speed=${"%.1f".format(mps * 3.6f)}km/h")
        _events.tryEmit(VehicleEvent.Speed(mps * 3.6f))

        if (lastSpeedMps >= 0f) {
            val decel = (lastSpeedMps - mps) / dt
            val fromSpeedKmh = lastSpeedMps * 3.6f
            // GPS 오차로 인한 오탐을 줄이기 위해, 이 차에서 페달 신호가 들어온다면 최근 3초 안에 밟혔는지 함께 확인합니다.
            val pedalOk = !pedalSeenThisTrip || System.currentTimeMillis() - pedalPressedAt <= PEDAL_WINDOW_MS
            if (decel >= HARD_BRAKE_MPS2 && fromSpeedKmh >= MIN_BRAKE_FROM_KMH && pedalOk) {
                _events.tryEmit(VehicleEvent.HardBrake(decel))
            }
        }
        lastSpeedMps = mps
    }

    private fun distanceMeters(a: LocationData, b: LocationData): Float {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
        return (2 * r * atan2(sqrt(h), sqrt(1 - h))).toFloat()
    }

    private inline fun reg(what: String, block: () -> Unit) {
        try { block(); Log.i(TAG, "registered $what") } catch (t: Throwable) { Log.w(TAG, "skip $what: ${t.message}") }
    }

    private fun warn(what: String, e: Exception) { Log.w(TAG, "$what: ${e.message}") }

    override fun stop() {
        vehicle?.let { v ->
            runCatching { v.getGear().unregisterGearSelection(gearListener) }
            runCatching { v.getWiper().unregisterWiperState(windshield, wiperListener) }
            runCatching { v.getFuel().unregisterFuelPercent(fuelListener) }
            runCatching { v.getEvBattery().unregisterEvBatteryStateOfCharge(socListener) }
            runCatching { v.getOdometer().unregisterTripCurrentDrivingDistance(tripDistanceListener) }
            runCatching { v.getBrake().unregisterBrakePedalStatus(brakeListener) }
            runCatching { v.release() }
        }
        location?.let { l ->
            runCatching { l.unregisterFusedLocationCallback(locationListener) }
            runCatching { l.release() }
        }
        vehicle = null; location = null
    }

    companion object {
        private const val TAG = "PleosSignalSource"
        const val PERM_FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        private const val FUEL_LOW_PERCENT = 10
        private const val HARD_BRAKE_MPS2 = 3.5f
        private const val MIN_BRAKE_FROM_KMH = 15f
        private const val MIN_DT_S = 0.3f
        private const val MAX_DT_S = 3f
        private const val PEDAL_WINDOW_MS = 3000L
    }
}
