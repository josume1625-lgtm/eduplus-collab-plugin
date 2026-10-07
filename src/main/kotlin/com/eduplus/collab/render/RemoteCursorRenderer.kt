package com.eduplus.collab.render

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.awt.*

/**
 * 学生远程光标垂直指示线 + 浮动学生姓名胶囊渲染器
 * 基于 MarkupModel 的 CustomHighlighterRenderer，零排版侵入、平滑视口滚动随动。
 */
class RemoteCursorRenderer(
    private val studentName: String,
    private val themeColor: Color = Color(255, 109, 0) // 活力鲜橙色，与老师蓝色形成高反差
) : CustomHighlighterRenderer {

    private val tagFont = Font("JetBrains Mono", Font.BOLD, 10)
    private val badgeBgColor = Color(themeColor.red, themeColor.green, themeColor.blue, 230)
    private val textColor = Color.WHITE

    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        val offset = highlighter.startOffset
        val docLength = editor.document.textLength
        if (offset < 0 || offset > docLength) return

        val g2d = g.create() as? Graphics2D ?: return
        try {
            // 开启图形与文本抗锯齿
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            // 1. 获取物理像素坐标
            val visualPosition = editor.offsetToVisualPosition(offset)
            val point = editor.visualPositionToXY(visualPosition)
            val lineHeight = editor.lineHeight

            val cursorX = point.x
            val cursorY = point.y

            // 2. 绘制 2px 橙色垂直光标线
            g2d.color = themeColor
            g2d.fillRect(cursorX, cursorY, 2, lineHeight)

            // 3. 计算浮动姓名胶囊尺寸
            val fontMetrics = g2d.getFontMetrics(tagFont)
            val text = "👨‍🎓 $studentName"
            val textWidth = fontMetrics.stringWidth(text)
            val textHeight = fontMetrics.ascent

            val paddingH = 6
            val paddingV = 2
            val badgeWidth = textWidth + paddingH * 2
            val badgeHeight = fontMetrics.height + paddingV * 2

            // 4. 自适应防截断定位（如果在第一行则放置在光标下方，否则悬浮在光标正上方）
            val badgeY = if (cursorY - badgeHeight >= 0) {
                cursorY - badgeHeight - 1
            } else {
                cursorY + lineHeight + 1
            }
            val badgeX = cursorX

            // 绘制气泡圆角矩形底色
            g2d.color = badgeBgColor
            g2d.fillRoundRect(badgeX, badgeY, badgeWidth, badgeHeight, 6, 6)

            // 绘制气泡边框
            g2d.color = themeColor.darker()
            g2d.drawRoundRect(badgeX, badgeY, badgeWidth, badgeHeight, 6, 6)

            // 绘制白色学生姓名
            g2d.color = textColor
            g2d.font = tagFont
            g2d.drawString(text, badgeX + paddingH, badgeY + paddingV + textHeight)

        } finally {
            g2d.dispose()
        }
    }
}
