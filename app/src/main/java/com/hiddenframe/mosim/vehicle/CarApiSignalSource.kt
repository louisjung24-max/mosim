package com.hiddenframe.mosim.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Android Automotive OS 표준 차량 속성 공급원 (CarPropertyManager).
 * - 차속(PERF_VEHICLE_SPEED): Pleos Vehicle SDK에 차속 API가 없어 표준 AAOS API로 받아 급제동을 판정합니다.
 * - 시동·연료·주행거리: Pleos 차량 서비스가 없는 기기(일반 AAOS 에뮬레이터)에서만 씁니다([speedOnly]=false).
 * 에뮬레이터에서는 속도·시동 권한이 시스템 전용이라 등록이 거부될 수 있으며, 그 경우 조용히 비활성화됩니다.
 */
class CarApiSignalSource(private val context: Context) : VehicleSignalSource {
    override val name = "aaos-car-api"

    private val _events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VehicleEvent> = _events

    /** true면 차속(급제동)만 내보냅니다. Pleos Vehicle SDK가 기어·에너지 등을 맡을 때 씁니다. */
    @Volatile var speedOnly = true

    private var car: Car? = null
    private var pm: CarPropertyManager? = null
    private var lastSpeed = 0f
    private var lastSpeedAt = 0L

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    val mps = (value.value as? Float) ?: return
                    val now = System.currentTimeMillis()
                    val kmh = mps * 3.6f
                    if (lastSpeedAt > 0) {
                        val dt = (now - lastSpeedAt) / 1000f
                        if (dt > 0) {
                            val decel = (lastSpeed - mps) / dt
                            if (decel >= HARD_BRAKE_MPS2) _events.tryEmit(VehicleEvent.HardBrake(decel))
                            if (-decel >= HARD_ACCEL_MPS2 && lastSpeed * 3.6f < ACCEL_FROM_KMH) _events.tryEmit(VehicleEvent.HardAccel(lastSpeed * 3.6f))
                        }
                    }
                    lastSpeed = mps; lastSpeedAt = now
                    lastVehicleSpeedAt = now
                    _events.tryEmit(VehicleEvent.Speed(kmh))
                }
                VehiclePropertyIds.IGNITION_STATE -> {
                    if (speedOnly) return
                    val state = (value.value as? Int) ?: return
                    _events.tryEmit(VehicleEvent.Ignition(on = state >= IGNITION_ON))
                }
                VehiclePropertyIds.FUEL_LEVEL_LOW -> {
                    if (speedOnly) return
                    if ((value.value as? Boolean) == true) _events.tryEmit(VehicleEvent.FuelLow(10))
                }
                VehiclePropertyIds.PERF_ODOMETER -> {
                    if (speedOnly) return
                    (value.value as? Float)?.let { _events.tryEmit(VehicleEvent.Odometer(it)) }
                }
            }
        }
        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "property error $propId zone $zone")
        }
    }

    override fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        try {
            car = Car.createCar(context)
            pm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            val m = pm ?: return
            register(m, VehiclePropertyIds.IGNITION_STATE, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            register(m, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            register(m, VehiclePropertyIds.FUEL_LEVEL_LOW, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            register(m, VehiclePropertyIds.PERF_ODOMETER, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        } catch (t: Throwable) {
            Log.w(TAG, "car api unavailable: ${t.message}")
        }
    }

    private fun register(m: CarPropertyManager, prop: Int, rate: Float) {
        try { m.registerCallback(callback, prop, rate) } catch (t: Throwable) { Log.w(TAG, "skip $prop: ${t.message}") }
    }

    override fun stop() {
        try { pm?.unregisterCallback(callback) } catch (_: Throwable) {}
        try { car?.disconnect() } catch (_: Throwable) {}
        car = null; pm = null
    }

    companion object {
        private const val TAG = "CarApiSignalSource"
        /** 표준 AAOS 차속을 마지막으로 받은 시각. 있으면 위치 기반 속도 계산을 쉽니다. */
        @Volatile var lastVehicleSpeedAt = 0L
        fun vehicleSpeedFresh() = System.currentTimeMillis() - lastVehicleSpeedAt < 5_000L
        private const val HARD_BRAKE_MPS2 = 3.5f
        /** 저속(시속 12km 미만)에서 이만큼 급하게 속도가 붙으면 급가속으로 봅니다. */
        private const val HARD_ACCEL_MPS2 = 3.0f
        private const val ACCEL_FROM_KMH = 12f
        private const val IGNITION_ON = 4 // VehicleIgnitionState.ON
    }
}
