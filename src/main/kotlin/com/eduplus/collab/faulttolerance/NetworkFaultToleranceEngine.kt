package com.eduplus.collab.faulttolerance

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 序列号校验结果
 */
sealed class SeqValidationResult {
    object Accepted : SeqValidationResult()
    data class OutdatedDuplicate(val seqId: Long, val expected: Long) : SeqValidationResult()
    data class GapDetected(val seqId: Long, val expected: Long) : SeqValidationResult()
}

/**
 * 序列号严格单调递增与乱序校验器 (Sequence Validator)
 */
class SequenceValidator(initialExpectedSeqId: Long = 1L) {
    private val expectedSeqId = AtomicLong(initialExpectedSeqId)

    fun validateAndAdvance(incomingSeqId: Long): SeqValidationResult {
        val currentExpected = expectedSeqId.get()
        return when {
            incomingSeqId == currentExpected -> {
                expectedSeqId.incrementAndGet()
                SeqValidationResult.Accepted
            }
            incomingSeqId < currentExpected -> {
                // 重复帧或过期帧，直接丢弃
                SeqValidationResult.OutdatedDuplicate(incomingSeqId, currentExpected)
            }
            else -> {
                // 乱序或存在丢包空洞
                SeqValidationResult.GapDetected(incomingSeqId, currentExpected)
            }
        }
    }

    fun forceReset(newExpected: Long) {
        expectedSeqId.set(newExpected)
    }

    fun currentExpected(): Long = expectedSeqId.get()
}

/**
 * 30ms 光标移动节流器 (Cursor Throttle: Leading + Trailing)
 * 保证在 30ms 时间窗内高频滑动只触发一次，并在窗口结束时补发最新停止帧，杜绝丢帧与光标偏移。
 */
class CursorThrottle<T>(
    private val windowMs: Long = 30L,
    private val executor: ScheduledExecutorService,
    private val onEmit: (T) -> Unit
) {
    private val lock = ReentrantLock()
    private var lastEmitTime = 0L
    private var pendingItem: T? = null
    private var scheduledTask: ScheduledFuture<*>? = null

    fun emit(item: T) {
        lock.withLock {
            val now = System.currentTimeMillis()
            val timeSinceLast = now - lastEmitTime

            if (timeSinceLast >= windowMs) {
                // 窗口已过，立即发送 (Leading)
                lastEmitTime = now
                pendingItem = null
                scheduledTask?.cancel(false)
                onEmit(item)
            } else {
                // 窗口期内，暂存最新值，并在窗口到期时补发 (Trailing)
                pendingItem = item
                if (scheduledTask == null || scheduledTask?.isDone == true) {
                    val remaining = windowMs - timeSinceLast
                    scheduledTask = executor.schedule({
                        flushTrailing()
                    }, remaining, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun flushTrailing() {
        var toEmit: T? = null
        lock.withLock {
            if (pendingItem != null) {
                toEmit = pendingItem
                pendingItem = null
                lastEmitTime = System.currentTimeMillis()
            }
        }
        toEmit?.let { onEmit(it) }
    }
}

/**
 * 50ms 输入聚合防抖器 (Debounce)
 * 在连续高频击键时延迟聚合，避免单字符小包网络风暴。
 */
class InputDebounce<T>(
    private val debounceMs: Long = 50L,
    private val executor: ScheduledExecutorService,
    private val onAction: (T) -> Unit
) {
    private val lock = ReentrantLock()
    private var scheduledTask: ScheduledFuture<*>? = null

    fun submit(item: T) {
        lock.withLock {
            scheduledTask?.cancel(false)
            scheduledTask = executor.schedule({
                onAction(item)
            }, debounceMs, TimeUnit.MILLISECONDS)
        }
    }
}

/**
 * 3秒双向心跳检测与断线判定引擎 (Heartbeat Engine)
 * - 每隔 3 秒向对端发送 Ping
 * - 连续 2 次无有效 Pong 响应判定为断线 (6000ms 超时)
 */
class HeartbeatDetector(
    private val pingIntervalMs: Long = 3000L,
    private val maxMissedThreshold: Int = 2,
    private val onSendPing: (sessionId: String) -> Unit,
    private val onSessionDisconnected: (sessionId: String, reason: String) -> Unit
) {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Heartbeat-Monitor").apply { isDaemon = true }
    }

    private data class SessionHeartbeatState(
        var lastPongTime: Long = System.currentTimeMillis(),
        var missedCount: Int = 0
    )

    private val sessionStates = ConcurrentHashMap<String, SessionHeartbeatState>()
    private var monitorTask: ScheduledFuture<*>? = null

    fun start() {
        monitorTask = scheduler.scheduleAtFixedRate({
            checkAllSessions()
        }, pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS)
    }

    fun registerSession(sessionId: String) {
        sessionStates[sessionId] = SessionHeartbeatState(
            lastPongTime = System.currentTimeMillis(),
            missedCount = 0
        )
    }

    fun unregisterSession(sessionId: String) {
        sessionStates.remove(sessionId)
    }

    fun onPongReceived(sessionId: String): Long {
        val now = System.currentTimeMillis()
        val state = sessionStates[sessionId] ?: return 0L
        val rtt = now - state.lastPongTime
        state.lastPongTime = now
        state.missedCount = 0
        return rtt
    }

    private fun checkAllSessions() {
        val now = System.currentTimeMillis()
        sessionStates.forEach { (sessionId, state) ->
            if (state.missedCount >= maxMissedThreshold) {
                // 超过 2 次未响应，触发掉线判定
                sessionStates.remove(sessionId)
                onSessionDisconnected(
                    sessionId,
                    "心跳超时：连续 $maxMissedThreshold 次未响应 Pong (超过 ${maxMissedThreshold * pingIntervalMs}ms)"
                )
            } else {
                state.missedCount++
                onSendPing(sessionId)
            }
        }
    }

    fun shutdown() {
        monitorTask?.cancel(true)
        scheduler.shutdownNow()
        sessionStates.clear()
    }
}
