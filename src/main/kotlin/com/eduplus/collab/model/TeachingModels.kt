package com.eduplus.collab.model

import java.awt.Color

/**
 * 协同服务端与客户端连接状态枚举
 */
enum class ConnectionStatus(val label: String, val color: Color) {
    IDLE("未启动", Color(0x8C, 0x8C, 0x8C)),                 // 灰灯：服务停止
    WAITING("等待学生加入...", Color(0xE5, 0xA8, 0x00)),        // 黄灯：服务已起，暂无客户端
    CONNECTED("教学协同进行中", Color(0x38, 0x8E, 0x3C)),      // 绿灯：至少一名学生连接协同中
    NETWORK_SHAKING("网络抖动/延迟高", Color(0xD3, 0x2F, 0x2F)) // 闪烁红灯：心跳超时或重传抖动
}

/**
 * 教学控制模式枚举
 */
enum class TeachingMode(val displayName: String, val description: String) {
    TEACHER_EXCLUSIVE("老师独占讲解", "学生端编辑器只读，无法修改代码，避免误触与打断"),
    FREE_COLLABORATION("自由互动协同", "师生均拥有实时编辑权限，代码并发输入并同步")
}

/**
 * 角色身份定义
 */
enum class UserRole(val displayName: String, val badgeColor: Color) {
    TEACHER("老师", Color(0x29, 0x79, 0xFF)), // 经典深蓝
    STUDENT("学生", Color(0xFF, 0x91, 0x00))  // 活力暖橙
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
