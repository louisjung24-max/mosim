package com.hiddenframe.mosim.alert

import com.hiddenframe.mosim.data.ShareSettings
import com.hiddenframe.mosim.rules.Anomaly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * "부모님 먼저" 관문.
 * 가족에게 갈 신호는 차 화면에 먼저 [HOLD_SECONDS]초 동안 보여 드리고, 그 사이 부모님이 "보내지 않기"를 누르면 보내지 않습니다.
 * 30초는 차가 멈춰 있을 때만 흐릅니다. 달리는 중에 생긴 신호는 다음에 멈출 때까지 기다려, 부모님이 운전 중 화면을 만지지 않고도 거부할 기회를 갖습니다.
 * 기다리는 동안 공유 설정이 꺼지거나 연결이 해제되면 보내지 않습니다(보내기 직전에 다시 확인).
 */
class AlertGate(
    private val share: ShareSettings,
    private val isStopped: () -> Boolean,
    private val onResult: (Anomaly, Outcome) -> Unit,
) {
    enum class Outcome { SENT, NOT_CONFIGURED, REJECTED, FAILED, VETOED, SHARING_OFF }

    /** [waitingForStop]이면 차가 멈출 때까지 대기 중(카운트다운 멈춤) */
    data class Pending(val id: Long, val anomaly: Anomaly, val secondsLeft: Int, val waitingForStop: Boolean = false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = LinkedHashMap<Long, Job>()
    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending
    private var seq = 0L

    fun submit(a: Anomaly) {
        val id = ++seq
        jobs[id] = scope.launch {
            var left = HOLD_SECONDS
            while (left > 0) {
                val stopped = isStopped()
                update(id, a, left, waiting = !stopped)
                delay(1000)
                if (stopped) left--
            }
            remove(id)
            val outcome = if (!share.allows(a)) Outcome.SHARING_OFF else when (AlertSender.send(share.familyCode, a)) {
                Delivery.SENT -> Outcome.SENT
                Delivery.NOT_CONFIGURED -> Outcome.NOT_CONFIGURED
                Delivery.REJECTED -> Outcome.REJECTED
                Delivery.FAILED -> Outcome.FAILED
            }
            onResult(a, outcome)
        }
    }

    /** 부모님이 "보내지 않기"를 눌렀을 때 */
    fun veto(id: Long) {
        val p = _pending.value.firstOrNull { it.id == id } ?: return
        jobs.remove(id)?.cancel()
        remove(id)
        onResult(p.anomaly, Outcome.VETOED)
    }

    /** 공유 전체 끄기·연결 해제 시 대기 중인 신호를 모두 취소 */
    fun cancelAll() {
        val list = _pending.value
        jobs.values.forEach { it.cancel() }; jobs.clear(); _pending.value = emptyList()
        list.forEach { onResult(it.anomaly, Outcome.SHARING_OFF) }
    }

    private fun update(id: Long, a: Anomaly, s: Int, waiting: Boolean) {
        val others = _pending.value.filterNot { it.id == id }
        _pending.value = (others + Pending(id, a, s, waiting)).sortedBy { it.id }
    }

    private fun remove(id: Long) { _pending.value = _pending.value.filterNot { it.id == id }; jobs.remove(id) }

    companion object { const val HOLD_SECONDS = 30 }
}
