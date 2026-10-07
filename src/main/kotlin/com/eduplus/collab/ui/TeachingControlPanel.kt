package com.eduplus.collab.ui

import com.eduplus.collab.model.ConnectionStatus
import com.eduplus.collab.model.RemotePeer
import com.eduplus.collab.model.TeachingMode
import com.eduplus.collab.service.TeachingSessionService
import com.eduplus.collab.ui.component.CursorLegendCard
import com.eduplus.collab.ui.component.StatusIndicatorComponent
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.CompoundBorder

/**
 * 教学协同右侧 ToolWindow 主控制面板
 * 支持本地 127.0.0.1 及局域网 LAN IP 分享、教学模式切换、在线学生监控
 */
class TeachingControlPanel(private val project: Project) : JBPanel<TeachingControlPanel>() {

    private val sessionService = TeachingSessionService.getInstance(project)

    // UI 组件定义
    private val statusIndicator = StatusIndicatorComponent()
    private val statusTextLabel = JBLabel(ConnectionStatus.IDLE.label).apply {
        font = JBFont.medium().asBold()
    }
    private val toggleServiceButton = JButton("启动协同服务").apply {
        background = JBColor(0x388E3C, 0x2E7D32)
        isFocusPainted = false
    }

    // 本地回环地址
    private val localUrlField = JBTextField().apply {
        isEditable = false
        emptyText.text = "服务未启动"
    }
    private val copyLocalUrlButton = JButton("复制").apply {
        toolTipText = "一键复制本机调试链接"
    }
    private val openBrowserButton = JButton("浏览器打开").apply {
        toolTipText = "在默认浏览器中打开学生端页面进行同屏验证"
    }

    // 局域网分享地址 (同 Wi-Fi 学生设备访问)
    private val lanUrlField = JBTextField().apply {
        isEditable = false
        emptyText.text = "服务未启动"
    }
    private val copyLanUrlButton = JButton("复制局域网").apply {
        toolTipText = "一键复制局域网分享链接供学生加入"
    }

    // 教学模式开关组件
    private val exclusiveModeRadio = JRadioButton("老师独占讲解 (强制只读)", true)
    private val freeCollabRadio = JRadioButton("自由互动协同 (双向可写)", false)
    private val modeButtonGroup = ButtonGroup()
    private val modeHintLabel = JBLabel("").apply {
        font = JBFont.small()
        foreground = JBColor(0x757575, 0x9E9E9E)
    }

    // 在线成员与动态列表
    private val peerListModel = DefaultListModel<String>()
    private val peerJList = JList(peerListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        background = JBColor(0xFAFAFA, 0x1E1F22)
    }

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(12)

        val mainContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // 1. 顶栏：状态指示器与快速启停按钮
        mainContent.add(createHeaderSection())
        mainContent.add(Box.createVerticalStrut(12))

        // 2. 本地与局域网访问地址卡片
        mainContent.add(createAddressSection())
        mainContent.add(Box.createVerticalStrut(12))

        // 3. 教学模式切换控制卡片
        mainContent.add(createModeSection())
        mainContent.add(Box.createVerticalStrut(12))

        // 4. 师生双光标图例卡片
        mainContent.add(CursorLegendCard().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        })
        mainContent.add(Box.createVerticalStrut(12))

        // 5. 在线协作学生监控面板
        mainContent.add(createPeerListSection())

        val scrollPane = JBScrollPane(mainContent).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scrollPane, BorderLayout.CENTER)

        // 绑定事件和监听器
        bindActions()
        registerServiceListener()
        updateModeHint(sessionService.mode)
    }

    private fun createHeaderSection(): JPanel {
        val panel = JPanel(BorderLayout(8, 0)).apply {
            background = JBColor(0xF5F6F7, 0x2B2D30)
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor(0xE0E0E0, 0x3E4246), 1),
                JBUI.Borders.empty(10, 12)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 54)
        }

        val statusBox = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(statusIndicator)
            add(statusTextLabel)
        }

        panel.add(statusBox, BorderLayout.WEST)
        panel.add(toggleServiceButton, BorderLayout.EAST)
        return panel
    }

    private fun createAddressSection(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = JBColor(0xF5F6F7, 0x2B2D30)
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor(0xE0E0E0, 0x3E4246), 1),
                JBUI.Borders.empty(10, 12)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 150)
        }

        val title = JBLabel("课堂接入访问地址 (本地 / 局域网)").apply {
            font = JBFont.regular().asBold()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(title)
        panel.add(Box.createVerticalStrut(6))

        // 本机地址行
        val localLabel = JBLabel("💻 本机地址:").apply {
            font = JBFont.small()
            foreground = JBColor(0x666666, 0xAAAAAA)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(localLabel)
        panel.add(Box.createVerticalStrut(2))

        val localRow = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(localUrlField, BorderLayout.CENTER)
            val btnBox = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(copyLocalUrlButton)
                add(openBrowserButton)
            }
            add(btnBox, BorderLayout.EAST)
        }
        panel.add(localRow)
        panel.add(Box.createVerticalStrut(6))

        // 局域网分享行
        val lanLabel = JBLabel("🌐 局域网分享 (同 Wi-Fi 学生):").apply {
            font = JBFont.small()
            foreground = JBColor(0x666666, 0xAAAAAA)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(lanLabel)
        panel.add(Box.createVerticalStrut(2))

        val lanRow = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(lanUrlField, BorderLayout.CENTER)
            val btnBox = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(copyLanUrlButton)
            }
            add(btnBox, BorderLayout.EAST)
        }
        panel.add(lanRow)

        return panel
    }

    private fun createModeSection(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = JBColor(0xF5F6F7, 0x2B2D30)
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor(0xE0E0E0, 0x3E4246), 1),
                JBUI.Borders.empty(10, 12)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 125)
        }

        val title = JBLabel("课堂协同控制模式").apply {
            font = JBFont.regular().asBold()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(title)
        panel.add(Box.createVerticalStrut(6))

        modeButtonGroup.add(exclusiveModeRadio)
        modeButtonGroup.add(freeCollabRadio)

        exclusiveModeRadio.isOpaque = false
        freeCollabRadio.isOpaque = false

        val radioRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(exclusiveModeRadio)
            add(Box.createHorizontalStrut(12))
            add(freeCollabRadio)
        }
        panel.add(radioRow)
        panel.add(Box.createVerticalStrut(4))

        modeHintLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(modeHintLabel)

        return panel
    }

    private fun createPeerListSection(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor(0xE0E0E0, 0x3E4246), 1),
                JBUI.Borders.empty(10, 12)
            )
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val title = JBLabel("在线课堂成员列表 (0)").apply {
            font = JBFont.regular().asBold()
        }
        panel.add(title, BorderLayout.NORTH)

        val listScrollPane = JBScrollPane(peerJList).apply {
            preferredSize = Dimension(260, 120)
            border = JBUI.Borders.customLine(JBColor(0xE0E0E0, 0x3E4246), 1)
        }
        panel.add(listScrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun bindActions() {
        // 启停协同服务
        toggleServiceButton.addActionListener {
            if (sessionService.status == ConnectionStatus.IDLE) {
                val started = sessionService.startSession()
                if (started) {
                    localUrlField.text = sessionService.accessUrl
                    lanUrlField.text = sessionService.lanAccessUrl
                    toggleServiceButton.text = "停止协同服务"
                    toggleServiceButton.background = JBColor(0xD32F2F, 0xC62828)
                } else {
                    Messages.showErrorDialog(project, "协同服务启动失败，请检查端口是否被占用。", "错误")
                }
            } else {
                sessionService.stopSession()
                localUrlField.text = ""
                lanUrlField.text = ""
                toggleServiceButton.text = "启动协同服务"
                toggleServiceButton.background = JBColor(0x388E3C, 0x2E7D32)
            }
        }

        // 复制本机链接
        copyLocalUrlButton.addActionListener {
            val url = sessionService.accessUrl
            if (sessionService.status != ConnectionStatus.IDLE && url.isNotBlank()) {
                CopyPasteManager.getInstance().setContents(StringSelection(url))
                Messages.showInfoMessage(project, "本机调试链接已复制到剪贴板！\n$url", "EduPlus 协同分享")
            } else {
                Messages.showWarningDialog(project, "协同服务未启动，暂无可复制的接入链接。", "提示")
            }
        }

        // 复制局域网分享链接
        copyLanUrlButton.addActionListener {
            val url = sessionService.lanAccessUrl
            if (sessionService.status != ConnectionStatus.IDLE && url.isNotBlank()) {
                CopyPasteManager.getInstance().setContents(StringSelection(url))
                Messages.showInfoMessage(project, "局域网分享链接已复制到剪贴板！可发给同一 Wi-Fi 下的学生：\n$url", "EduPlus 局域网分享")
            } else {
                Messages.showWarningDialog(project, "协同服务未启动，暂无可复制的接入链接。", "提示")
            }
        }

        // 浏览器打开
        openBrowserButton.addActionListener {
            if (sessionService.status != ConnectionStatus.IDLE) {
                BrowserUtil.browse(sessionService.accessUrl)
            } else {
                Messages.showWarningDialog(project, "协同服务未启动，请先点击启动服务。", "提示")
            }
        }

        // 教学模式变更
        exclusiveModeRadio.addActionListener {
            sessionService.setTeachingMode(TeachingMode.TEACHER_EXCLUSIVE)
            updateModeHint(TeachingMode.TEACHER_EXCLUSIVE)
        }
        freeCollabRadio.addActionListener {
            sessionService.setTeachingMode(TeachingMode.FREE_COLLABORATION)
            updateModeHint(TeachingMode.FREE_COLLABORATION)
        }
    }

    private fun registerServiceListener() {
        sessionService.addListener(object : TeachingSessionService.SessionEventListener {
            override fun onStatusChanged(newStatus: ConnectionStatus) {
                SwingUtilities.invokeLater {
                    statusIndicator.setStatus(newStatus)
                    statusTextLabel.text = newStatus.label
                    when (newStatus) {
                        ConnectionStatus.IDLE -> {
                            toggleServiceButton.text = "启动协同服务"
                            localUrlField.text = ""
                            lanUrlField.text = ""
                        }
                        ConnectionStatus.WAITING, ConnectionStatus.CONNECTED, ConnectionStatus.NETWORK_SHAKING -> {
                            toggleServiceButton.text = "停止协同服务"
                            localUrlField.text = sessionService.accessUrl
                            lanUrlField.text = sessionService.lanAccessUrl
                        }
                    }
                }
            }

            override fun onModeChanged(newMode: TeachingMode) {
                SwingUtilities.invokeLater {
                    exclusiveModeRadio.isSelected = (newMode == TeachingMode.TEACHER_EXCLUSIVE)
                    freeCollabRadio.isSelected = (newMode == TeachingMode.FREE_COLLABORATION)
                    updateModeHint(newMode)
                }
            }

            override fun onPeerUpdated(peers: List<RemotePeer>) {
                SwingUtilities.invokeLater {
                    peerListModel.clear()
                    if (peers.isEmpty()) {
                        peerListModel.addElement("暂无学生接入")
                    } else {
                        peers.forEach { peer ->
                            val latencyTag = if (peer.latencyMs > 0) "[${peer.latencyMs}ms]" else ""
                            val cursorTag = peer.cursor?.let { "行:${it.line + 1},列:${it.column + 1}" } ?: "未聚焦"
                            peerListModel.addElement("${peer.name} (${peer.role.displayName}) $latencyTag - 光标: $cursorTag")
                        }
                    }
                }
            }
        })
    }

    private fun updateModeHint(mode: TeachingMode) {
        modeHintLabel.text = "说明: ${mode.description}"
    }
}
