package com.eduplus.collab.server

import com.eduplus.collab.arbitration.ArbitrationResult
import com.eduplus.collab.arbitration.TeacherLockListener
import com.eduplus.collab.arbitration.TeacherPriorityLockManager
import com.eduplus.collab.faulttolerance.*
import com.eduplus.collab.protocol.*
import com.fasterxml.jackson.databind.JsonNode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong

/**
 * 客户端会话封装
 */
interface ICollabSession {
    val sessionId: String
    val role: UserRole
    val userName: String
    fun sendText(text: String)
    fun close(reason: String)
}

/**
 * WebSocket 实时协同调度中心
 * 集成容错引擎、序列号单调递增校验、光标 30ms 节流、输入防抖、心跳检测与老师优先写锁
 */
class CollabWebSocketCoordinator(
    private val lockManager: TeacherPriorityLockManager = TeacherPriorityLockManager(),
    private val replayBuffer: ReplayBufferManager = ReplayBufferManager()
) : TeacherLockListener {

    private val sessions = ConcurrentHashMap<String, ICollabSession>()
    private val seqValidators = ConcurrentHashMap<String, SequenceValidator>()
    private val serverSeqCounter = AtomicLong(1000L)

    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(4) { r ->
        Thread(r, "Collab-Coordinator-Worker").apply { isDaemon = true }
    }

    // 30ms 光标节流器
    private val cursorThrottle = CursorThrottle<CollabMessage<*>>(
        windowMs = 30L,
        executor = executor
    ) { message ->
        broadcastToOthers(message.sessionId, CollabJsonMapper.toJson(message))
    }

    // 3秒双向心跳检测器
    private val heartbeatDetector = HeartbeatDetector(
        pingIntervalMs = 3000L,
        maxMissedThreshold = 2,
        onSendPing = { sessionId ->
            val session = sessions[sessionId] ?: return@HeartbeatDetector
            val pingMsg = CollabMessage(
                type = "heartbeat",
                sessionId = "server",
                role = UserRole.TEACHER,
                seqId = serverSeqCounter.incrementAndGet(),
                payload = HeartbeatPayload(
                    action = "ping",
                    clientTime = System.currentTimeMillis(),
                    serverTime = System.currentTimeMillis()
                )
            )
            session.sendText(CollabJsonMapper.toJson(pingMsg))
        },
        onSessionDisconnected = { sessionId, reason ->
            println("[EduPlu-WS] 会话掉线: $sessionId, 原因: $reason")
            sessions[sessionId]?.close(reason)
            handleSessionClosed(sessionId)
        }
    )

    init {
        lockManager.addListener(this)
        heartbeatDetector.start()
    }

    fun handleSessionConnected(session: ICollabSession) {
        sessions[session.sessionId] = session
        seqValidators[session.sessionId] = SequenceValidator(1L)
        heartbeatDetector.registerSession(session.sessionId)
        println("[EduPlu-WS] 新连接加入: ${session.sessionId} (${session.role} - ${session.userName})")
    }

    fun handleSessionClosed(sessionId: String) {
        sessions.remove(sessionId)
        seqValidators.remove(sessionId)
        heartbeatDetector.unregisterSession(sessionId)
        println("[EduPlu-WS] 连接退出: $sessionId")
    }

    /**
     * 统一入口：分发处理入站文本消息
     */
    fun onMessageReceived(session: ICollabSession, rawJson: String) {
        try {
            val rootNode: JsonNode = CollabJsonMapper.mapper.readTree(rawJson)
            val type = rootNode.get("type")?.asText() ?: return
            val seqId = rootNode.get("seqId")?.asLong() ?: 0L

            // 1. 序列号时序校验
            val validator = seqValidators[session.sessionId]
            if (validator != null && type != "heartbeat") {
                when (val result = validator.validateAndAdvance(seqId)) {
                    is SeqValidationResult.OutdatedDuplicate -> {
                        println("[EduPlu-Seq] 丢弃重复/过期帧: seqId=$seqId, expected=${result.expected}")
                        return
                    }
                    is SeqValidationResult.GapDetected -> {
                        println("[EduPlu-Seq] 发现乱序或空洞帧: seqId=$seqId, expected=${result.expected}")
                        // 空洞情况下仍记录处理，后续可排队重排
                    }
                    is SeqValidationResult.Accepted -> {}
                }
            }

            // 2. 协议分发路由
            when (type) {
                "code_full" -> handleCodeFull(session, rawJson)
                "code_delta" -> handleCodeDelta(session, rawJson)
                "cursor_teacher" -> handleCursorTeacher(session, rawJson)
                "cursor_student" -> handleCursorStudent(session, rawJson)
                "heartbeat" -> handleHeartbeat(session, rawJson)
                "reconnect_sync" -> handleReconnectSync(session, rawJson)
                else -> println("[EduPlu-WS] 未知协议类型: $type")
            }
        } catch (e: Exception) {
            System.err.println("[EduPlu-WS] 消息解析异常: ${e.message}")
        }
    }

    private fun handleCodeFull(session: ICollabSession, json: String) {
        val msg: CollabMessage<CodeFullPayload> = CollabJsonMapper.fromJson(json)
        replayBuffer.setFullSnapshot(msg.payload)
        // 老师端发布全量代码后，向所有学生广播
        broadcastToOthers(session.sessionId, json)
    }

    private fun handleCodeDelta(session: ICollabSession, json: String) {
        val msg: CollabMessage<CodeDeltaPayload> = CollabJsonMapper.fromJson(json)

        // 教学仲裁：老师优先锁检查
        val arbitration = lockManager.checkAndAcquireWrite(session.role, session.userName)
        when (arbitration) {
            is ArbitrationResult.Blocked -> {
                // 学生写操作被拦截，向学生发送拦截提示
                val rejectMsg = mapOf(
                    "type" to "error_lock_blocked",
                    "reason" to arbitration.reason,
                    "remainingMs" to arbitration.remainingMs
                )
                session.sendText(CollabJsonMapper.toJson(rejectMsg))
                return
            }
            is ArbitrationResult.Allowed -> {
                // 放行：记录入重放缓冲区
                replayBuffer.recordDelta(msg.payload)
                // 广播增量修改
                broadcastToOthers(session.sessionId, json)
            }
        }
    }

    private fun handleCursorTeacher(session: ICollabSession, json: String) {
        val msg: CollabMessage<CursorTeacherPayload> = CollabJsonMapper.fromJson(json)
        // 30ms 节流广播
        cursorThrottle.emit(msg)
    }

    private fun handleCursorStudent(session: ICollabSession, json: String) {
        val msg: CollabMessage<CursorStudentPayload> = CollabJsonMapper.fromJson(json)
        // 30ms 节流广播
        cursorThrottle.emit(msg)
    }

    private fun handleHeartbeat(session: ICollabSession, json: String) {
        val msg: CollabMessage<HeartbeatPayload> = CollabJsonMapper.fromJson(json)
        if (msg.payload.action == "ping") {
            // 回应 Pong
            val pongMsg = CollabMessage(
                type = "heartbeat",
                sessionId = session.sessionId,
                role = UserRole.TEACHER,
                seqId = serverSeqCounter.incrementAndGet(),
                payload = HeartbeatPayload(
                    action = "pong",
                    clientTime = msg.payload.clientTime,
                    serverTime = System.currentTimeMillis(),
                    rttMs = System.currentTimeMillis() - msg.payload.clientTime
                )
            )
            session.sendText(CollabJsonMapper.toJson(pongMsg))
        } else if (msg.payload.action == "pong") {
            // 客户端回应 Pong，更新心跳探测器
            heartbeatDetector.onPongReceived(session.sessionId)
        }
    }

    private fun handleReconnectSync(session: ICollabSession, json: String) {
        val msg: CollabMessage<ReconnectSyncPayload> = CollabJsonMapper.fromJson(json)
        val syncResult = replayBuffer.negotiateReconnect(
            filePath = msg.payload.filePath,
            clientLastSeqId = msg.payload.lastSeqId,
            clientVersion = msg.payload.clientVersion
        )

        val responseMsg = CollabMessage(
            type = "reconnect_sync",
            sessionId = session.sessionId,
            role = UserRole.TEACHER,
            filePath = msg.payload.filePath,
            seqId = serverSeqCounter.incrementAndGet(),
            payload = syncResult
        )
        session.sendText(CollabJsonMapper.toJson(responseMsg))
    }

    private fun broadcastToOthers(senderSessionId: String, text: String) {
        sessions.forEach { (id, s) ->
            if (id != senderSessionId) {
                try {
                    s.sendText(text)
                } catch (e: Exception) {
                    System.err.println("[EduPlu-WS] 广播失败至 $id: ${e.message}")
                }
            }
        }
    }

    private fun broadcastAll(text: String) {
        sessions.values.forEach { s ->
            try {
                s.sendText(text)
            } catch (e: Exception) {
                System.err.println("[EduPlu-WS] 全员广播失败: ${e.message}")
            }
        }
    }

    override fun onLockAcquired(teacherName: String, lockWindowMs: Long) {
        val notice = mapOf(
            "type" to "notice_teacher_lock",
            "isLocked" to true,
            "teacherName" to teacherName,
            "windowMs" to lockWindowMs
        )
        broadcastAll(CollabJsonMapper.toJson(notice))
    }

    override fun onLockReleased() {
        val notice = mapOf(
            "type" to "notice_teacher_lock",
            "isLocked" to false
        )
        broadcastAll(CollabJsonMapper.toJson(notice))
    }

    fun shutdown() {
        heartbeatDetector.shutdown()
        lockManager.shutdown()
        executor.shutdownNow()
        sessions.clear()
        seqValidators.clear()
    }
}
