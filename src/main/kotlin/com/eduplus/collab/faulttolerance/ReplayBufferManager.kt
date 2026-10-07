package com.eduplus.collab.faulttolerance

import com.eduplus.collab.protocol.CodeDeltaPayload
import com.eduplus.collab.protocol.CodeFullPayload
import com.eduplus.collab.protocol.ReconnectSyncPayload
import com.eduplus.collab.protocol.SyncStatus
import java.util.LinkedList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 断线重连协商与操作缓存回放管理器 (Replay Buffer Manager)
 *
 * 核心机制：
 * 1. 内存维护定长环形队列 (默认最大缓存 500 条增量操作)。
 * 2. 客户端网络中断重连后发送协商请求 (含 lastSeqId, clientVersion)。
 * 3. 若客户端版本在缓冲池窗口内，下发增量差异补丁列表 (REPLAY_PATCH)。
 * 4. 若客户端版本已过期或滑出窗口水位线，直接降级推送全量代码 (FALLBACK_FULL)，杜绝错乱。
 */
class ReplayBufferManager(
    private val maxCapacity: Int = 500
) {
    private val rwLock = ReentrantReadWriteLock()
    private val buffer = LinkedList<CodeDeltaPayload>()

    @Volatile
    private var latestFullSnapshot: CodeFullPayload? = null

    @Volatile
    private var currentVersion: Long = 0L

    @Volatile
    private var currentSeqId: Long = 0L

    /**
     * 记录最新全量基线快照
     */
    fun setFullSnapshot(full: CodeFullPayload) {
        rwLock.write {
            latestFullSnapshot = full
            currentVersion = full.version
            buffer.clear()
        }
    }

    /**
     * 写入一个增量操作至重放缓冲区
     */
    fun recordDelta(delta: CodeDeltaPayload) {
        rwLock.write {
            if (buffer.size >= maxCapacity) {
                buffer.removeFirst()
            }
            buffer.addLast(delta)
            currentVersion = delta.version
            currentSeqId = delta.seqId
        }
    }

    /**
     * 执行断线重连协商仲裁
     */
    fun negotiateReconnect(
        filePath: String,
        clientLastSeqId: Long,
        clientVersion: Long
    ): ReconnectSyncPayload {
        rwLock.read {
            val serverVer = currentVersion
            val snapshot = latestFullSnapshot

            // 1. 客户端已经是最新版本
            if (clientVersion == serverVer) {
                return ReconnectSyncPayload(
                    status = SyncStatus.UP_TO_DATE,
                    filePath = filePath,
                    lastSeqId = currentSeqId,
                    clientVersion = clientVersion,
                    targetVersion = serverVer,
                    reason = "客户端已与服务端保持最新"
                )
            }

            // 2. 客户端版本超前或异常
            if (clientVersion > serverVer || buffer.isEmpty() || snapshot == null) {
                return fallbackFullSync(
                    filePath,
                    clientLastSeqId,
                    clientVersion,
                    "客户端版本异常或服务端无差量历史，执行全量同步"
                )
            }

            // 3. 检查客户端版本是否在当前 Replay 缓冲池覆盖范围内
            val oldestBufferedVersion = buffer.first().baseVersion
            if (clientVersion < oldestBufferedVersion) {
                // 已超出重放水位线，降级为全量兜底
                return fallbackFullSync(
                    filePath,
                    clientLastSeqId,
                    clientVersion,
                    "客户端版本 ($clientVersion) 过旧，已滑出重放缓冲水位线 ($oldestBufferedVersion)，降级为全量同步"
                )
            }

            // 4. 在有效窗口内，提取自客户端版本后的所有增量补丁
            val patches = buffer.filter { it.version > clientVersion }
            return ReconnectSyncPayload(
                status = SyncStatus.REPLAY_PATCH,
                filePath = filePath,
                lastSeqId = currentSeqId,
                clientVersion = clientVersion,
                targetVersion = serverVer,
                patches = patches,
                reason = "在缓冲区窗口内，成功下发 ${patches.size} 条增量回放补丁"
            )
        }
    }

    private fun fallbackFullSync(
        filePath: String,
        lastSeqId: Long,
        clientVersion: Long,
        reason: String
    ): ReconnectSyncPayload {
        return ReconnectSyncPayload(
            status = SyncStatus.FALLBACK_FULL,
            filePath = filePath,
            lastSeqId = currentSeqId,
            clientVersion = clientVersion,
            targetVersion = currentVersion,
            fullSync = latestFullSnapshot,
            reason = reason
        )
    }

    fun getCurrentVersion(): Long = currentVersion
    fun getCurrentSeqId(): Long = currentSeqId
    fun getBufferSize(): Int = rwLock.read { buffer.size }

    fun clear() {
        rwLock.write {
            buffer.clear()
            latestFullSnapshot = null
            currentVersion = 0L
            currentSeqId = 0L
        }
    }
}
