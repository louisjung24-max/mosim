package com.hiddenframe.mosim.vehicle

import kotlinx.coroutines.flow.Flow

/**
 * 차량 신호 공급원. 구현체를 바꾸면 나머지 앱은 그대로입니다.
 *  - [SimulatedSignalSource] : 에뮬레이터·시연용 시나리오
 *  - [CarApiSignalSource]    : Android Automotive OS CarPropertyManager
 *  - PleosSignalSource       : Pleos Connect Vehicle SDK (Playground 승인 후 교체 예정, 동일 인터페이스)
 */
interface VehicleSignalSource {
    val name: String
    val events: Flow<VehicleEvent>
    fun start()
    fun stop()
}
