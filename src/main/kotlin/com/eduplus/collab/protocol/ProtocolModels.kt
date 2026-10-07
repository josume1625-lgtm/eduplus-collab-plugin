package com.eduplus.collab.protocol

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * 协同用户角色枚举
 */
enum class UserRole {
    TEACHER,
    STUDENT
}

/**
 * 统一 WebSocket 消息信封 (Message Envelope)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CollabMessage<T : CollabPayload>(
    val type: String,
    val sessionId: String,
    val role: UserRole,
    val filePath: String? = null,
    val seqId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: T
)

/**
 * 所有业务载荷的基础接口
 */
sealed interface CollabPayload

// ==========================================
// 1. code_full: 全量代码同步协议
// ==========================================
data class CodeFullPayload(
    val filePath: String,
    val language: String,
    val content: String,
    val version: Long,
    val totalLines: Int = content.lines().size
) : CollabPayload

// ==========================================
// 2. code_delta: 增量代码修改协议
// ==========================================
data class CodeDeltaPayload(
    val rangeOffset: Int,
    val oldLength: Int,
    val text: String,
    val version: Long,
    val baseVersion: Long,
    val seqId: Long
) : CollabPayload

// ==========================================
// 3. cursor_teacher: 老师光标与选区协议
// ==========================================
data class Position(
    val line: Int,
    val ch: Int
)

data class CursorTeacherPayload(
    val line: Int,
    val ch: Int,
    val selectionStart: Position? = null,
    val selectionEnd: Position? = null,
    val teacherName: String = "Teacher",
    val avatarColor: String = "#FF5722"
) : CollabPayload

// ==========================================
// 4. cursor_student: 学生光标与选区协议
// ==========================================
data class CursorStudentPayload(
    val studentId: String,
    val studentName: String,
    val line: Int,
    val ch: Int,
    val selectionStart: Position? = null,
    val selectionEnd: Position? = null,
    val cursorColor: String = "#2196F3",
    val isQuestionActive: Boolean = false
) : CollabPayload

// ==========================================
// 5. heartbeat: 双向心跳保活协议
// ==========================================
data class HeartbeatPayload(
    val action: String, // "ping" or "pong"
    val clientTime: Long,
    val serverTime: Long = 0L,
    val rttMs: Long = 0L
) : CollabPayload

// ==========================================
// 6. reconnect_sync: 断线重连协商协议
// ==========================================
enum class SyncStatus {
    QUERY,
    REPLAY_PATCH,
    FALLBACK_FULL,
    UP_TO_DATE
}

data class ReconnectSyncPayload(
    val status: SyncStatus = SyncStatus.QUERY,
    val filePath: String,
    val lastSeqId: Long,
    val clientVersion: Long,
    val targetVersion: Long? = null,
    val patches: List<CodeDeltaPayload>? = null,
    val fullSync: CodeFullPayload? = null,
    val reason: String? = null
) : CollabPayload

/**
 * JSON 序列化工具单例
 */
object CollabJsonMapper {
    val mapper: ObjectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }

    fun toJson(obj: Any): String = mapper.writeValueAsString(obj)

    inline fun <reified T> fromJson(json: String): T = mapper.readValue(json, T::class.java)
}
