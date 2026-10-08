package com.eduplus.collab.render

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.project.Project
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程学生光标与选区生命周期管理器
 * 负责在本地 Editor 的 MarkupModel 上添加、更新、清除学生的远程高亮层。
 */
class RemoteCursorManager(private val project: Project) : Disposable {

    // 活跃学生视图容器：Map<StudentId, StudentVisualSession>
    private val studentVisuals = ConcurrentHashMap<String, StudentVisualSession>()

    // 橙色半透明选区样式（RGBA: 255, 160, 0, 60）
    private val selectionAttributes = TextAttributes().apply {
        backgroundColor = Color(255, 160, 0, 60)
    }

    /**
     * 更新指定学生在编辑器中的光标与选区
     */
    fun updateStudentCursor(
        editor: Editor,
        studentId: String,
        studentName: String,
        cursorOffset: Int,
        selectionStart: Int? = null,
        selectionEnd: Int? = null
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed || project.isDisposed) return@invokeLater

            val markupModel = editor.markupModel
            val docLength = editor.document.textLength
            val safeCursorOffset = cursorOffset.coerceIn(0, docLength)

            // 1. 清理该学生旧的高亮标记（使用其原先所在 editor 的 markupModel，杜绝跨 Tab 报错）
            clearStudent(studentId)

            // 2. 渲染选区（如果存在有效选区）
            var selectionHighlighter: RangeHighlighter? = null
            if (selectionStart != null && selectionEnd != null && selectionStart != selectionEnd) {
                val start = minOf(selectionStart, selectionEnd).coerceIn(0, docLength)
                val end = maxOf(selectionStart, selectionEnd).coerceIn(0, docLength)
                if (start < end) {
                    selectionHighlighter = markupModel.addRangeHighlighter(
                        start,
                        end,
                        HighlighterLayer.SELECTION - 1, // 略低于老师本地原生选区优先级
                        selectionAttributes,
                        HighlighterTargetArea.EXACT_RANGE
                    ).apply {
                        isGreedyToLeft = false
                        isGreedyToRight = false
                    }
                }
            }

            // 3. 渲染光标垂直线与姓名胶囊
            val caretHighlighter = markupModel.addRangeHighlighter(
                safeCursorOffset,
                safeCursorOffset,
                HighlighterLayer.LAST + 10, // 最高优先级，确保显示在最上层
                null,
                HighlighterTargetArea.EXACT_RANGE
            ).apply {
                customRenderer = RemoteCursorRenderer(studentName)
                isGreedyToLeft = false
                isGreedyToRight = false
            }

            // 4. 存入管理缓存
            studentVisuals[studentId] = StudentVisualSession(
                studentId = studentId,
                editor = editor,
                caretHighlighter = caretHighlighter,
                selectionHighlighter = selectionHighlighter,
                lastActiveTime = System.currentTimeMillis()
            )
        }
    }

    /**
     * 清理指定学生的高亮图层（从其绑定宿主 Editor 的 MarkupModel 中安全移除）
     */
    fun clearStudent(studentId: String) {
        val session = studentVisuals.remove(studentId) ?: return
        try {
            if (!session.editor.isDisposed) {
                val mm = session.editor.markupModel
                session.caretHighlighter?.let { if (it.isValid) mm.removeHighlighter(it) }
                session.selectionHighlighter?.let { if (it.isValid) mm.removeHighlighter(it) }
            }
        } catch (_: Throwable) {
            try { session.caretHighlighter?.dispose() } catch (_: Throwable) {}
            try { session.selectionHighlighter?.dispose() } catch (_: Throwable) {}
        }
    }

    /**
     * 根据学生ID移除远程光标与选区
     */
    fun removeStudentCursor(studentId: String) {
        ApplicationManager.getApplication().invokeLater {
            clearStudent(studentId)
        }
    }

    /**
     * 清理指定编辑器或所有编辑器内的学生远程高亮
     */
    fun clearAll(editor: Editor? = null) {
        val it = studentVisuals.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val session = entry.value
            if (editor == null || session.editor == editor) {
                try {
                    if (!session.editor.isDisposed) {
                        val mm = session.editor.markupModel
                        session.caretHighlighter?.let { if (it.isValid) mm.removeHighlighter(it) }
                        session.selectionHighlighter?.let { if (it.isValid) mm.removeHighlighter(it) }
                    }
                } catch (_: Throwable) {
                    try { session.caretHighlighter?.dispose() } catch (_: Throwable) {}
                    try { session.selectionHighlighter?.dispose() } catch (_: Throwable) {}
                }
                it.remove()
            }
        }
    }

    override fun dispose() {
        studentVisuals.clear()
    }

    private data class StudentVisualSession(
        val studentId: String,
        val editor: Editor,
        val caretHighlighter: RangeHighlighter?,
        val selectionHighlighter: RangeHighlighter?,
        var lastActiveTime: Long
    )
}
