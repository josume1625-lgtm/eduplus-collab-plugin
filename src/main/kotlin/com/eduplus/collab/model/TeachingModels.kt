package com.eduplus.collab.model

import java.awt.Color

/**
 * 协同服务端与客户端连接状态枚举
 */
enum class ConnectionStatus(val label: String, val color: Color) {
    IDLE("Stopped", Color(0x8C, 0x8C, 0x8C)),                 // Gray: Service stopped
    WAITING("Waiting for students...", Color(0xE5, 0xA8, 0x00)),        // Yellow: Waiting for clients
    CONNECTED("Collab Active", Color(0x38, 0x8E, 0x3C)),      // Green: Connected and active
    NETWORK_SHAKING("Network Jitter / Lag", Color(0xD3, 0x2F, 0x2F)) // Red: Latency/Jitter
}

/**
 * 教学控制模式枚举
 */
enum class TeachingMode(val displayName: String, val description: String) {
    TEACHER_EXCLUSIVE("Teacher Exclusive (Read-only)", "Student editor is read-only to avoid interruptions"),
    FREE_COLLABORATION("Interactive Collab (Bi-directional)", "Teacher and students can edit simultaneously")
}

/**
 * 角色身份定义
 */
enum class UserRole(val displayName: String, val badgeColor: Color) {
    TEACHER("Teacher", Color(0x29, 0x79, 0xFF)), // Deep Blue
    STUDENT("Student", Color(0xFF, 0x91, 0x00))  // Bright Orange
}

/**
 * 光标位置坐标
 */
data class CursorPosition(
    val line: Int = 0,
    val column: Int = 0,
    val offset: Int = 0
)

/**
 * 文本高亮选区范围
 */
data class SelectionRange(
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val startLine: Int = 0,
    val endLine: Int = 0
)

/**
 * 远程对端终端实体
 */
data class RemotePeer(
    val id: String,
    val name: String,
    val role: UserRole,
    val cursor: CursorPosition? = null,
    val selection: SelectionRange? = null,
    val latencyMs: Long = 0,
    val activeFile: String = ""
)

/**
 * 协同信令消息统一结构
 */
data class CollabMessage(
    val type: String,               // HELLO, DOC_INIT, DOC_DIFF, CURSOR_SYNC, MODE_CHANGE, PING, PONG
    val senderId: String,
    val role: String,
    val payload: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val version: Long = 0
)
