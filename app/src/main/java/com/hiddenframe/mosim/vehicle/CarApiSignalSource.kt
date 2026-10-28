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
 * Android Automotive OS 기본 차량 속성 공급원 (대체 경로).
 * Pleos 차량 서비스가 응답하지 않는 기기(일반 AAOS 에뮬레이터)에서만 켜집니다. 실제 차량 경로는 [PleosSignalSource].
 * 에뮬레이터에서는 속도·시동 권한이 시스템 전용이라 등록이 거부될 수 있으며, 그 경우 조용히 비활성화됩니다.
 */
class CarApiSignalSource(private val context: Context) : VehicleSignalSource {
    override val name = "aaos-car-api"

    private val _events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VehicleEvent> = _events

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
                        }
                    }
                    lastSpeed = mps; lastSpeedAt = now
                    _events.tryEmit(VehicleEvent.Speed(kmh))
                }
                VehiclePropertyIds.IGNITION_STATE -> {
                    val state = (value.value as? Int) ?: return
                    _events.tryEmit(VehicleEvent.Ignition(on = state >= IGNITION_ON))
                }
                VehiclePropertyIds.FUEL_LEVEL_LOW -> {
                    if ((value.value as? Boolean) == true) _events.tryEmit(VehicleEvent.FuelLow(10))
                }
                VehiclePropertyIds.PERF_ODOMETER -> {
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
        private const val HARD_BRAKE_MPS2 = 3.5f
        private const val IGNITION_ON = 4 // VehicleIgnitionState.ON
    }
}
