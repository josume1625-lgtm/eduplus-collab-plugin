package com.eduplus.collab.ui.component

import com.eduplus.collab.model.UserRole
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.border.CompoundBorder

/**
 * 师生双光标图例对照卡片
 * 直观说明蓝色（老师）与橙色（学生）光标色彩体系与交互含义
 */
class CursorLegendCard : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor(0xF8F9FA, 0x2B2D30)
        border = CompoundBorder(
            JBUI.Borders.customLine(JBColor(0xE0E0E0, 0x3E4246), 1),
            JBUI.Borders.empty(10, 12)
        )

        val titleLabel = JBLabel("师生双光标视觉图例").apply {
            font = JBFont.regular().asBold()
            foreground = JBColor(0x333333, 0xBBBBBB)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        add(titleLabel)
        add(Box.createVerticalStrut(8))

        // 老师项
        add(createLegendRow(
            roleName = UserRole.TEACHER.displayName,
            badgeColor = UserRole.TEACHER.badgeColor,
            desc = "蓝色光标：主讲教师指针，拥有代码讲解广播与全局高亮权限"
        ))
        add(Box.createVerticalStrut(6))

        // 学生项
        add(createLegendRow(
            roleName = UserRole.STUDENT.displayName,
            badgeColor = UserRole.STUDENT.badgeColor,
            desc = "橙色光标：互动学生指针，受模式管控，实时呈现焦点选区"
        ))
    }

    private fun createLegendRow(roleName: String, badgeColor: Color, desc: String): JPanel {
        val row = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val badge = object : JPanel() {
            init {
                isOpaque = false
                preferredSize = JBUI.size(46, 20)
                maximumSize = preferredSize
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    // 绘制圆角胶囊
                    g2.color = badgeColor
                    g2.fillRoundRect(0, 0, width, height, 10, 10)

                    // 绘制文字
                    g2.color = Color.WHITE
                    g2.font = JBFont.small().asBold()
                    val fm = g2.fontMetrics
                    val textWidth = fm.stringWidth(roleName)
                    val textHeight = fm.ascent
                    g2.drawString(roleName, (width - textWidth) / 2, (height + textHeight) / 2 - 2)
                } finally {
                    g2.dispose()
                }
            }
        }

        val textLabel = JBLabel("<html><body style='width: 220px;'>$desc</body></html>").apply {
            font = JBFont.small()
            foreground = JBColor(0x666666, 0x999999)
        }

        row.add(badge, BorderLayout.WEST)
        row.add(textLabel, BorderLayout.CENTER)
        return row
    }
}
