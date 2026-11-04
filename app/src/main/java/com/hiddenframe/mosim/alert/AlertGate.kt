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
 * 기다리는 동안 공유 설정이 꺼지거나 연결이 해제되면 보내지 않습니다(보내기 직전에 다시 확인).
 */
class AlertGate(
    private val share: ShareSettings,
    private val onResult: (Anomaly, Outcome) -> Unit,
) {
    enum class Outcome { SENT, NOT_CONFIGURED, REJECTED, FAILED, VETOED, SHARING_OFF }

    data class Pending(val id: Long, val anomaly: Anomaly, val secondsLeft: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = LinkedHashMap<Long, Job>()
    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending
    private var seq = 0L

    fun submit(a: Anomaly) {
        val id = ++seq
        jobs[id] = scope.launch {
            for (s in HOLD_SECONDS downTo 1) {
                update(id, a, s)
                delay(1000)
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

    private fun update(id: Long, a: Anomaly, s: Int) {
        val others = _pending.value.filterNot { it.id == id }
        _pending.value = (others + Pending(id, a, s)).sortedBy { it.id }
    }

    private fun remove(id: Long) { _pending.value = _pending.value.filterNot { it.id == id }; jobs.remove(id) }

    companion object { const val HOLD_SECONDS = 30 }
}
