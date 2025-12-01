package com.example.my5.algorithms

import com.example.my5.model.Line
import com.example.my5.model.Point
import com.example.my5.model.Rectangle
import kotlin.math.max
import kotlin.math.min

object MidpointClipping {
    private const val INSIDE = 0 // 0000
    private const val LEFT = 1   // 0001
    private const val RIGHT = 2  // 0010
    private const val BOTTOM = 4 // 0100
    private const val TOP = 8    // 1000

    private fun computeCode(p: Point, rect: Rectangle): Int {
        var code = INSIDE
        if (p.x < rect.left) code = code or LEFT
        else if (p.x > rect.right) code = code or RIGHT
        if (p.y < rect.top) code = code or TOP
        else if (p.y > rect.bottom) code = code or BOTTOM
        return code
    }

    fun clipLine(line: Line, rect: Rectangle): Line? {
        var (x1, y1) = line.start
        var (x2, y2) = line.end
        
        // Вычисляем коды концов отрезка
        var code1 = computeCode(Point(x1, y1), rect)
        var code2 = computeCode(Point(x2, y2), rect)
        
        while (true) {
            // Отрезок полностью видимый
            if (code1 == 0 && code2 == 0) {
                return Line(Point(x1, y1), Point(x2, y2))
            }
            // Отрезок полностью невидимый
            else if (code1 and code2 != 0) {
                return null
            }
            // Нужно отсекать
            else {
                var x = 0.0
                var y = 0.0
                
                // Выбираем точку вне прямоугольника
                val codeOut = if (code1 != 0) code1 else code2
                
                // Находим точку пересечения
                // Используем метод средней точки
                if (codeOut and TOP != 0) {
                    // Точка над прямоугольником
                    x = x1 + (x2 - x1) * (rect.top - y1) / (y2 - y1)
                    y = rect.top
                } else if (codeOut and BOTTOM != 0) {
                    // Точка под прямоугольником
                    x = x1 + (x2 - x1) * (rect.bottom - y1) / (y2 - y1)
                    y = rect.bottom
                } else if (codeOut and RIGHT != 0) {
                    // Точка справа от прямоугольника
                    y = y1 + (y2 - y1) * (rect.right - x1) / (x2 - x1)
                    x = rect.right
                } else if (codeOut and LEFT != 0) {
                    // Точка слева от прямоугольника
                    y = y1 + (y2 - y1) * (rect.left - x1) / (x2 - x1)
                    x = rect.left
                }
                
                // Обновляем точку вне прямоугольника
                if (codeOut == code1) {
                    x1 = x
                    y1 = y
                    code1 = computeCode(Point(x1, y1), rect)
                } else {
                    x2 = x
                    y2 = y
                    code2 = computeCode(Point(x2, y2), rect)
                }
            }
        }
    }
}
