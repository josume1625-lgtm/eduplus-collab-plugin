package com.eduplus.collab.editor

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * 项目启动后注册全局文档与光标事件监听器
 */
class CollabEditorStartupActivity : StartupActivity {

    override fun runActivity(project: Project) {
        val editorFactory = EditorFactory.getInstance()
        val eventMulticaster = editorFactory.eventMulticaster

        // 监听文档内容变动
        eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                // 后续阶段接入：节流 (30ms) 计算差分增量并推送到 WebSocket 客户端
            }
        }, project)

        // 监听光标与选区变动
        eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                // 后续阶段接入：防抖 (16ms) 捕获老师当前光标并广播
            }
        }, project)
    }
}
