package com.eduplus.collab.ui.action

import com.eduplus.collab.model.ConnectionStatus
import com.eduplus.collab.service.TeachingSessionService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 快捷启动/停止教学协同动作
 */
class ToggleCollabAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = TeachingSessionService.getInstance(project)

        if (service.status == ConnectionStatus.IDLE) {
            service.startSession()
        } else {
            service.stopSession()
        }

        // 打开右侧 ToolWindow
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("EduPlus 教学协同")
        toolWindow?.show()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}
