package com.example.my5.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.example.my5.algorithms.MidpointClipping
import com.example.my5.model.Line
import com.example.my5.model.Point
import com.example.my5.model.Rectangle
import kotlin.math.max
import kotlin.math.min

@Composable
fun ClippingView(
    lines: List<Line>,
    clipRect: Rectangle,
    clippedLines: List<Line>,
    onLineAdded: (Line) -> Unit,
    onClipRectChanged: (Rectangle) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDrawing by remember { mutableStateOf(false) }
    var startPoint by remember { mutableStateOf<Point?>(null) }
    var isClippingRectMode by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Управление
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { isClippingRectMode = !isClippingRectMode }) {
                Text(if (isClippingRectMode) "Режим: Рисование отсекающего прямоугольника" else "Режим: Рисование отрезков")
            }
            
            Button(onClick = { 
                // Очистка всех отрезков
                // Очистка реализуется через передачу пустого списка в onLineAdded
                onLineAdded(Line(Point(0.0, 0.0), Point(0.0, 0.0))) // Фиктивное добавление
                onLineAdded(Line(Point(0.0, 0.0), Point(0.0, 0.0))) // Фиктивное добавление
            }) {
                Text("Очистить")
            }
        }
        
        // Холст для рисования
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White)
                .padding(16.dp)
        ) {
            var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
            
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        canvasSize = coordinates.size.toSize()
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isDrawing = true
                                startPoint = Point(offset.x.toDouble(), offset.y.toDouble())
                            },
                            onDrag = { change, _ ->
                                val position = change.position
                                val point = Point(position.x.toDouble(), position.y.toDouble())
                                startPoint?.let { start ->
                                    if (isClippingRectMode) {
                                        val newRect = Rectangle(
                                            min(start.x, point.x),
                                            min(start.y, point.y),
                                            max(start.x, point.x),
                                            max(start.y, point.y)
                                        )
                                        onClipRectChanged(newRect)
                                    } else {
                                        onLineAdded(Line(start, point))
                                        startPoint = point
                                    }
                                }
                            },
                            onDragEnd = {
                                isDrawing = false
                                startPoint = null
                            }
                        )
                    }
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                
                // Оси координат
                drawLine(
                    color = Color.LightGray,
                    start = Offset(0f, canvasHeight / 2),
                    end = Offset(canvasWidth, canvasHeight / 2),
                    strokeWidth = 1f
                )
                drawLine(
                    color = Color.LightGray,
                    start = Offset(canvasWidth / 2, 0f),
                    end = Offset(canvasWidth / 2, canvasHeight),
                    strokeWidth = 1f
                )
                
                // Отсекающий прямоугольник
                drawRect(
                    color = Color.Blue.copy(alpha = 0.2f),
                    topLeft = Offset(clipRect.left.toFloat(), clipRect.top.toFloat()),
                    size = androidx.compose.ui.geometry.Size(
                        clipRect.width.toFloat(),
                        clipRect.height.toFloat()
                    ),
                    style = Stroke(width = 2f)
                )
                
                // Исходные отрезки
                lines.forEach { line ->
                    drawLine(
                        color = Color.Red,
                        start = Offset(line.start.x.toFloat(), line.start.y.toFloat()),
                        end = Offset(line.end.x.toFloat(), line.end.y.toFloat()),
                        strokeWidth = 2f
                    )
                }
                
                // Отсеченные отрезки
                clippedLines.forEach { line ->
                    drawLine(
                        color = Color.Green,
                        start = Offset(line.start.x.toFloat(), line.start.y.toFloat()),
                        end = Offset(line.end.x.toFloat(), line.end.y.toFloat()),
                        strokeWidth = 3f
                    )
                }
            }
            
            // Подсказка
            Text(
                text = if (isClippingRectMode) "Нарисуйте отсекающий прямоугольник" else "Нарисуйте отрезки",
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                color = Color.Black
            )
        }
    }
}
