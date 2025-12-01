import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

// --- Data classes ---
data class Segment(val x1: Double, val y1: Double, val x2: Double, val y2: Double)
data class Rect(val xmin: Double, val ymin: Double, val xmax: Double, val ymax: Double)

// World bounding box to map coordinates to canvas
data class BBox(val xmin: Double, val ymin: Double, val xmax: Double, val ymax: Double)

// --- Utility: world -> screen mapping ---
class Viewport(var bbox: BBox, var size: IntSize, var paddingPx: Int = 40) {
    fun worldToScreen(x: Double, y: Double): Offset {
        val w = size.width - paddingPx * 2
        val h = size.height - paddingPx * 2
        val sx = if (bbox.xmax - bbox.xmin == 0.0) 1.0 else (x - bbox.xmin) / (bbox.xmax - bbox.xmin)
        val sy = if (bbox.ymax - bbox.ymin == 0.0) 1.0 else (y - bbox.ymin) / (bbox.ymax - bbox.ymin)
        // invert y for screen coordinates
        return Offset((paddingPx + sx * w).toFloat(), (paddingPx + (1.0 - sy) * h).toFloat())
    }
}

// Expand bounding box to include segments and clipping polygon/rect
fun computeBBox(segments: List<Segment>, rect: Rect?): BBox {
    var xmin = Double.POSITIVE_INFINITY
    var ymin = Double.POSITIVE_INFINITY
    var xmax = Double.NEGATIVE_INFINITY
    var ymax = Double.NEGATIVE_INFINITY
    for (s in segments) {
        xmin = min(xmin, min(s.x1, s.x2))
        ymin = min(ymin, min(s.y1, s.y2))
        xmax = max(xmax, max(s.x1, s.x2))
        ymax = max(ymax, max(s.y1, s.y2))
    }
    if (rect != null) {
        xmin = min(xmin, rect.xmin)
        ymin = min(ymin, rect.ymin)
        xmax = max(xmax, rect.xmax)
        ymax = max(ymax, rect.ymax)
    }
    // Add small margin
    val dx = (xmax - xmin).takeIf { it > 0 } ?: 1.0
    val dy = (ymax - ymin).takeIf { it > 0 } ?: 1.0
    xmin -= dx * 0.1
    xmax += dx * 0.1
    ymin -= dy * 0.1
    ymax += dy * 0.1
    return BBox(xmin, ymin, xmax, ymax)
}

// --- Midpoint subdivision algorithm for rectangular window ---
fun pointInsideRect(x: Double, y: Double, r: Rect): Boolean = x >= r.xmin && x <= r.xmax && y >= r.ymin && y <= r.ymax

// trivial reject/accept using Cohen-Sutherland outcodes (helps midpoint algorithm early)
fun computeOutcode(x: Double, y: Double, r: Rect): Int {
    var code = 0
    if (x < r.xmin) code = code or 1 // left
    if (x > r.xmax) code = code or 2 // right
    if (y < r.ymin) code = code or 4 // bottom
    if (y > r.ymax) code = code or 8 // top
    return code
}

// Midpoint subdivision: returns list of visible subsegments (may be empty)
fun midpointClipSegment(s: Segment, rect: Rect, epsilon: Double = 1e-3, maxDepth: Int = 50): List<Segment> {
    val out = mutableListOf<Segment>()
    fun recurse(x1: Double, y1: Double, x2: Double, y2: Double, depth: Int) {
        if (depth > maxDepth) return
        val aInside = pointInsideRect(x1, y1, rect)
        val bInside = pointInsideRect(x2, y2, rect)
        if (aInside && bInside) {
            out += Segment(x1, y1, x2, y2)
            return
        }
        val outcode1 = computeOutcode(x1, y1, rect)
        val outcode2 = computeOutcode(x2, y2, rect)
        if ((outcode1 and outcode2) != 0) {
            // trivially outside
            return
        }
        val dx = x2 - x1
        val dy = y2 - y1
        if (abs(dx) + abs(dy) < epsilon) return
        val mx = (x1 + x2) / 2.0
        val my = (y1 + y2) / 2.0
        // if midpoint and both endpoints outside but midpoint inside, split
        val mInside = pointInsideRect(mx, my, rect)
        if (!mInside && !aInside && !bInside) {
            // both endpoints outside and midpoint outside: if outcodes share a bit, reject
            if ((outcode1 and outcode2) != 0) return
        }
        // split
        recurse(x1, y1, mx, my, depth + 1)
        recurse(mx, my, x2, y2, depth + 1)
    }
    recurse(s.x1, s.y1, s.x2, s.y2, 0)
    return out
}

@Composable
fun App() {
    // Тестовые данные для варианта 12 (алгоритм средней точки)
    val testSegments = listOf(
        Segment(-5.0, -3.0, 8.0, 6.0),    // Отрезок пересекающий окно
        Segment(-2.0, 4.0, 7.0, 1.0),     // Отрезок частично в окне
        Segment(1.0, 1.0, 4.0, 4.0),      // Отрезок полностью в окне
        Segment(-8.0, -8.0, -6.0, -6.0),  // Отрезок полностью вне окна
        Segment(0.0, -1.0, 3.0, 5.0),     // Отрезок входящий в окно
        Segment(2.0, 0.0, 6.0, 2.0)       // Отрезок выходящий из окна
    )
    val testRect = Rect(0.0, 0.0, 5.0, 4.0)  // Прямоугольное окно отсечения
    
    var segments by remember { mutableStateOf(testSegments) }
    var rect by remember { mutableStateOf<Rect?>(testRect) }
    var viewportSize by remember { mutableStateOf(IntSize(800, 600)) }
    
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Text(
                "Лабораторная работа 5 - Вариант 12: Алгоритм средней точки",
                style = MaterialTheme.typography.h6,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Button(onClick = {
                    // Сброс к тестовым данным
                    segments = testSegments
                    rect = testRect
                }) { 
                    Text("Загрузить тестовые данные") 
                }
                
                Spacer(Modifier.width(16.dp))
                
                Button(onClick = {
                    // Другой набор тестовых данных
                    segments = listOf(
                        Segment(-3.0, -2.0, 7.0, 5.0),
                        Segment(1.0, -1.0, 4.0, 6.0),
                        Segment(-1.0, 2.0, 6.0, 3.0),
                        Segment(2.5, 1.5, 3.5, 2.5)
                    )
                    rect = Rect(1.0, 1.0, 4.0, 3.0)
                }) { 
                    Text("Альтернативные данные") 
                }
            }
            
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFAFAFA))) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasSize = IntSize(size.width.toInt(), size.height.toInt())
                    viewportSize = canvasSize
                    
                    if (segments.isEmpty() || rect == null) return@Canvas
                    
                    val bbox = computeBBox(segments, rect)
                    val vp = Viewport(bbox, canvasSize, 60)

                    // Рисуем координатные оси
                    if (bbox.xmin < 0 && bbox.xmax > 0) {
                        val p1 = vp.worldToScreen(0.0, bbox.ymin)
                        val p2 = vp.worldToScreen(0.0, bbox.ymax)
                        drawLine(color = Color.LightGray, start = p1, end = p2, strokeWidth = 1f)
                    }
                    if (bbox.ymin < 0 && bbox.ymax > 0) {
                        val p1 = vp.worldToScreen(bbox.xmin, 0.0)
                        val p2 = vp.worldToScreen(bbox.xmax, 0.0)
                        drawLine(color = Color.LightGray, start = p1, end = p2, strokeWidth = 1f)
                    }

                    // Рисуем окно отсечения
                    rect?.let { r ->
                        val p1 = vp.worldToScreen(r.xmin, r.ymin)
                        val p2 = vp.worldToScreen(r.xmax, r.ymin)
                        val p3 = vp.worldToScreen(r.xmax, r.ymax)
                        val p4 = vp.worldToScreen(r.xmin, r.ymax)
                        val path = Path().apply {
                            moveTo(p1.x, p1.y)
                            lineTo(p2.x, p2.y)
                            lineTo(p3.x, p3.y)
                            lineTo(p4.x, p4.y)
                            close()
                        }
                        drawPath(path, color = Color(0x3300AAFF))
                        drawPath(path, color = Color.Blue, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                    }

                    // Рисуем исходные отрезки
                    for (s in segments) {
                        val a = vp.worldToScreen(s.x1, s.y1)
                        val b = vp.worldToScreen(s.x2, s.y2)
                        drawLine(color = Color.Gray, start = a, end = b, strokeWidth = 2f)
                    }

                    // Выполняем отсечение алгоритмом средней точки и рисуем результаты
                    rect?.let { r ->
                        for (s in segments) {
                            val clipped = midpointClipSegment(s, r, epsilon = 1e-3)
                            for (cs in clipped) {
                                val a = vp.worldToScreen(cs.x1, cs.y1)
                                val b = vp.worldToScreen(cs.x2, cs.y2)
                                drawLine(color = Color.Red, start = a, end = b, strokeWidth = 4f)
                            }
                        }
                    }

                    // Легенда (используем простые круги для обозначения цветов)
                    val legendY = canvasSize.height - 100f
                    drawCircle(Color.Gray, radius = 8f, center = Offset(20f, legendY))
                    drawCircle(Color.Red, radius = 8f, center = Offset(20f, legendY + 20f))
                    drawCircle(Color.Blue, radius = 8f, center = Offset(20f, legendY + 40f))
                }
                
                // Текстовые подписи к легенде
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 60.dp)
                ) {
                    Text("Исходные отрезки", style = MaterialTheme.typography.caption, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Text("Видимые части", style = MaterialTheme.typography.caption, color = Color.Red)
                    Spacer(Modifier.height(4.dp))
                    Text("Окно отсечения", style = MaterialTheme.typography.caption, color = Color.Blue)
                    Spacer(Modifier.height(4.dp))
                    Text("Алгоритм средней точки (вариант 12)", style = MaterialTheme.typography.caption)
                }
            }
        }
    }
}

expect fun getPlatformName(): String