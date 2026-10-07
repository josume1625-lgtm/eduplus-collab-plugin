package com.eduplus.collab.ui.component

import com.eduplus.collab.model.ConnectionStatus
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel
import javax.swing.Timer

/**
 * 连接状态指示徽标
 * 支持灰、黄、绿常亮以及红灯心跳/网络抖动闪烁动效
 */
class StatusIndicatorComponent : JPanel() {

    private var status: ConnectionStatus = ConnectionStatus.IDLE
    private var isBlinkVisible: Boolean = true
    private var blinkTimer: Timer? = null

    init {
        isOpaque = false
        preferredSize = JBUI.size(16, 16)
        minimumSize = preferredSize
        maximumSize = preferredSize

        // 闪烁定时器：网络抖动时 400ms 交替亮灭
        blinkTimer = Timer(400) {
            if (status == ConnectionStatus.NETWORK_SHAKING) {
                isBlinkVisible = !isBlinkVisible
                repaint()
            }
        }
    }

    fun setStatus(newStatus: ConnectionStatus) {
        this.status = newStatus
        if (newStatus == ConnectionStatus.NETWORK_SHAKING) {
            if (!blinkTimer!!.isRunning) {
                blinkTimer?.start()
            }
        } else {
            if (blinkTimer!!.isRunning) {
                blinkTimer?.stop()
            }
            isBlinkVisible = true
        }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = 12
            val x = (width - size) / 2
            val y = (height - size) / 2

            val baseColor = status.color
            if (status == ConnectionStatus.NETWORK_SHAKING && !isBlinkVisible) {
                // 闪烁暗态
                g2.color = JBColor(0xFCA5A5, 0x7F1D1D)
                g2.fillOval(x, y, size, size)
            } else {
                // 外圈光晕
                g2.color = java.awt.Color(baseColor.red, baseColor.green, baseColor.blue, 60)
                g2.fillOval(x - 2, y - 2, size + 4, size + 4)

                // 实体光标点
                g2.color = baseColor
                g2.fillOval(x, y, size, size)

                // 中心反光高光
                g2.color = java.awt.Color(255, 255, 255, 120)
                g2.fillOval(x + 2, y + 2, 4, 4)
            }
        } finally {
            g2.dispose()
        }
    }
}
