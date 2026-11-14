package com.elio.jianyu.network.keys

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** 仅协调一轮独立回复的主回答 Key，不持有明文，也不在锁内等待网络。 */
internal class ConversationKeyCoordinator(
    private val availableKeys: () -> List<ApiKeyLease>,
) {
    private val lock = Any()
    private val owners = mutableMapOf<Pair<String, String>, ConversationKeyReservation>()
    private val revision = MutableStateFlow(0L)

    suspend fun reserve(preferredKeyId: String?): ConversationKeyReservation {
        val reservation = ConversationKeyReservation(this)
        while (true) {
            currentCoroutineContext().ensureActive()
            val observed = synchronized(lock) {
                val available = availableKeys()
                check(available.isNotEmpty()) { "没有可用的主回答 API Key" }
                val free = available.filter { identity(it) !in owners }
                val selected = free.firstOrNull { it.keyId == preferredKeyId } ?: free.firstOrNull()
                if (selected != null) {
                    owners[identity(selected)] = reservation
                    reservation.selected = selected
                    return reservation
                }
                revision.value
            }
            revision.first { it != observed }
        }
    }

    /** 换 Key 前先释放原占用，避免两位角色各持一把 Key 并互相等待。 */
    suspend fun select(reservation: ConversationKeyReservation, lease: ApiKeyLease): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            val observed = synchronized(lock) {
                check(!reservation.closed) { "主回答 Key 占用已结束" }
                if (reservation.selected?.let(::identity) != identity(lease)) releaseLocked(reservation)
                if (availableKeys().none { identity(it) == identity(lease) }) {
                    releaseLocked(reservation)
                    return false
                }
                val owner = owners[identity(lease)]
                if (owner == null || owner === reservation) {
                    owners[identity(lease)] = reservation
                    reservation.selected = lease
                    return true
                }
                revision.value
            }
            revision.first { it != observed }
        }
    }

    fun attemptPlan(reservation: ConversationKeyReservation): List<ApiKeyLease> = synchronized(lock) {
        val available = availableKeys()
        val selected = reservation.selected
        available.sortedBy { if (identity(it) == selected?.let(::identity)) 0 else 1 }
    }

    fun close(reservation: ConversationKeyReservation) = synchronized(lock) {
        reservation.closed = true
        releaseLocked(reservation)
    }

    private fun releaseLocked(reservation: ConversationKeyReservation) {
        val selected = reservation.selected ?: return
        if (owners[identity(selected)] === reservation) owners.remove(identity(selected))
        reservation.selected = null
        revision.value += 1L
    }

    private fun identity(lease: ApiKeyLease): Pair<String, String> = lease.provider.name to lease.keyId
}

/** 随角色协程传到请求执行器；摘要嵌套调用共享同一占用，不能再申请第二份。 */
internal class ConversationKeyReservation(
    private val coordinator: ConversationKeyCoordinator,
) {
    internal var selected: ApiKeyLease? = null
    internal var closed = false

    suspend fun select(lease: ApiKeyLease): Boolean = coordinator.select(this, lease)
    fun attemptPlan(): List<ApiKeyLease> = coordinator.attemptPlan(this)
    fun close() = coordinator.close(this)
}

/** 辅助检索显式传 null，覆盖继承的占用，避免改变主回答的 Key。 */
internal class ConversationKeyContext(val reservation: ConversationKeyReservation?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConversationKeyContext>
}
