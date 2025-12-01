/*
KMP Desktop (Compose Multiplatform) single-file example
Filename: KMP_Desktop_Clipping_Lab.kt

Описание: приложение для демонстрации алгоритмов отсечения:
  - Часть 1: алгоритм средней точки (midpoint subdivision) для прямоугольного окна
  - Часть 2: алгоритм отсечения отрезков выпуклым многоугольником (Cyrus-Beck)

Запуск: Откройте проект как Kotlin/Compose Desktop (JVM) в IntelliJ IDEA.
Создайте стандартный gradle проект с Compose Multiplatform (JVM desktop) или добавьте этот файл в существующий проект.

Формат входного файла (пример):
n
X1_1 Y1_1 X2_1 Y2_1
...
X1_n Y1_n X2_n Y2_n
Xmin Ymin Xmax Ymax
(или вместо последней строки — координаты выпуклого многоугольника: x1 y1 x2 y2 ... xm ym)

Программа читает файл, отображает исходные отрезки и окно отсечения / многоугольник; выполняет отсечение и показывает видимые части.
*/

import androidx.compose.desktop.ui.tooling.preview.Preview
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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

// --- Data classes ---
data class Segment(val x1: Double, val y1: Double, val x2: Double, val y2: Double)
data class Rect(val xmin: Double, val ymin: Double, val xmax: Double, val ymax: Double)

// World bounding box to map coordinates to canvas
data class BBox(val xmin: Double, val ymin: Double, val xmax: Double, val ymax: Double)

// --- Parser ---
fun parseInputFile(file: File): Triple<List<Segment>, Rect?, List<Pair<Double, Double>>?> {
    val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) throw IllegalArgumentException("Empty file")
    val n = lines[0].split(Regex("\\s+"))[0].toInt()
    if (lines.size < 1 + n + 1) throw IllegalArgumentException("Not enough lines. Expected at least ${1 + n + 1}")
    val segments = mutableListOf<Segment>()
    for (i in 1..n) {
        val parts = lines[i].split(Regex("\\s+"))
        if (parts.size < 4) throw IllegalArgumentException("Segment line must have 4 numbers")
        segments += Segment(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble())
    }
    val lastParts = lines[1 + n].split(Regex("\\s+"))
    return if (lastParts.size == 4) {
        val rect = Rect(lastParts[0].toDouble(), lastParts[1].toDouble(), lastParts[2].toDouble(), lastParts[3].toDouble())
        Triple(segments, rect, null)
    } else if (lastParts.size >= 6 && lastParts.size % 2 == 0) {
        val polygon = lastParts.map { it.toDouble() }.chunked(2).map { it[0] to it[1] }
        Triple(segments, null, polygon)
    } else {
        throw IllegalArgumentException("Last line must be either 4 numbers (rectangle) or even number >=6 (polygon) ")
    }
}

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
fun computeBBox(segments: List<Segment>, rect: Rect?, polygon: List<Pair<Double, Double>>?): BBox {
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
    if (polygon != null) {
        for ((x, y) in polygon) {
            xmin = min(xmin, x)
            ymin = min(ymin, y)
            xmax = max(xmax, x)
            ymax = max(ymax, y)
        }
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

// --- Part 1: midpoint subdivision algorithm for rectangular window ---

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

// --- Part 2: Cyrus-Beck clipping for convex polygon ---

// Vector helpers
fun dot(ax: Double, ay: Double, bx: Double, by: Double) = ax * bx + ay * by
fun sub(ax: Double, ay: Double, bx: Double, by: Double) = Pair(ax - bx, ay - by)

// compute outward normal for edge (p_i -> p_{i+1}) assuming polygon in CCW order,
// outward normal is (edgeY, -edgeX) for CCW (verify during usage)
fun edgeNormal(x1: Double, y1: Double, x2: Double, y2: Double): Pair<Double, Double> {
    val ex = x2 - x1
    val ey = y2 - y1
    // For CCW polygon, outward normal = (ey, -ex)
    return Pair(ey, -ex)
}

fun cyrusBeckClip(s: Segment, polygon: List<Pair<Double, Double>>): Segment? {
    var tE = 0.0 // maximum entering
    var tL = 1.0 // minimum leaving
    val dx = s.x2 - s.x1
    val dy = s.y2 - s.y1
    val m = polygon.size
    for (i in 0 until m) {
        val (xk, yk) = polygon[i]
        val (xk1, yk1) = polygon[(i + 1) % m]
        val nxny = edgeNormal(xk, yk, xk1, yk1)
        val nx = nxny.first
        val ny = nxny.second
        val w = sub(s.x1, s.y1, xk, yk)
        val num = dot(nx, ny, w.first, w.second)
        val den = dot(nx, ny, dx, dy)
        if (abs(den) < 1e-12) {
            // segment parallel to edge
            if (num < 0) return null // outside
            else continue
        }
        val t = -num / den
        if (den < 0) {
            // potential entering
            if (t > tE) tE = t
        } else {
            // potential leaving
            if (t < tL) tL = t
        }
        if (tE > tL) return null
    }
    if (tE <= tL) {
        val cx1 = s.x1 + dx * tE
        val cy1 = s.y1 + dy * tE
        val cx2 = s.x1 + dx * tL
        val cy2 = s.y1 + dy * tL
        return Segment(cx1, cy1, cx2, cy2)
    }
    return null
}

// --- Compose UI ---
@Composable
@Preview
fun App() {
    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var rect by remember { mutableStateOf<Rect?>(null) }
    var polygon by remember { mutableStateOf<List<Pair<Double, Double>>?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize(800, 600)) }
    var mode by remember { mutableStateOf(1) } // 1 = midpoint rect, 2 = convex polygon (Cyrus-Beck)
    var errorMsg by remember { mutableStateOf<String?>(null) }

    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    try {
                        val chooser = javax.swing.JFileChooser()
                        val res = chooser.showOpenDialog(null)
                        if (res == javax.swing.JFileChooser.APPROVE_OPTION) {
                            val f = chooser.selectedFile
                            val parsed = parseInputFile(f)
                            segments = parsed.first
                            rect = parsed.second
                            polygon = parsed.third
                            errorMsg = null
                        }
                    } catch (e: Exception) {
                        errorMsg = e.message
                    }
                }) { Text("Load input file") }

                Spacer(Modifier.width(12.dp))
                Text("Mode:")
                Spacer(Modifier.width(8.dp))
                Row {
                    RadioButton(selected = mode == 1, onClick = { mode = 1 })
                    BasicText("Part 1 — Midpoint (rect)", Modifier.align(Alignment.CenterVertically).padding(end = 8.dp))
                    RadioButton(selected = mode == 2, onClick = { mode = 2 })
                    BasicText("Part 2 — Convex polygon (Cyrus-Beck)", Modifier.align(Alignment.CenterVertically))
                }
                Spacer(Modifier.weight(1f))
                if (errorMsg != null) Text("Error: ${errorMsg}", color = Color.Red)
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFAFAFA))) {
                Canvas(modifier = Modifier.fillMaxSize(), onDraw = {
                    val canvasSize = IntSize(size.width.toInt(), size.height.toInt())
                    viewportSize = canvasSize
                    if (segments.isEmpty()) {
                        drawIntoCanvas { it.nativeCanvas.drawText("Load an input file to start", 20f, 40f, android.graphics.Paint().apply{ textSize = 24f }) }
                        return@Canvas
                    }
                    val bbox = computeBBox(segments, rect, polygon)
                    val vp = Viewport(bbox, canvasSize, 60)

                    // draw axes grid
                    // draw simple axes lines (x and y at 0 if within bbox)
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

                    // draw clipping window / polygon
                    if (rect != null) {
                        val r = rect!!
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
                        drawPath(path, color = Color(0x1100AAFF))
                        drawPath(path, color = Color.Blue, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                    }
                    if (polygon != null) {
                        val poly = polygon!!
                        if (poly.size >= 3) {
                            val path = Path()
                            val first = vp.worldToScreen(poly[0].first, poly[0].second)
                            path.moveTo(first.x, first.y)
                            for (i in 1 until poly.size) {
                                val p = vp.worldToScreen(poly[i].first, poly[i].second)
                                path.lineTo(p.x, p.y)
                            }
                            path.close()
                            drawPath(path, color = Color(0x1100CC00))
                            drawPath(path, color = Color.Green, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                        }
                    }

                    // draw original segments
                    for (s in segments) {
                        val a = vp.worldToScreen(s.x1, s.y1)
                        val b = vp.worldToScreen(s.x2, s.y2)
                        drawLine(color = Color.Gray, start = a, end = b, strokeWidth = 2f)
                    }

                    // perform clipping and draw results
                    if (mode == 1 && rect != null) {
                        for (s in segments) {
                            val clipped = midpointClipSegment(s, rect!!, epsilon = 1e-3)
                            for (cs in clipped) {
                                val a = vp.worldToScreen(cs.x1, cs.y1)
                                val b = vp.worldToScreen(cs.x2, cs.y2)
                                drawLine(color = Color.Red, start = a, end = b, strokeWidth = 3f)
                            }
                        }
                    } else if (mode == 2 && polygon != null) {
                        for (s in segments) {
                            val cs = cyrusBeckClip(s, polygon!!)
                            if (cs != null) {
                                val a = vp.worldToScreen(cs.x1, cs.y1)
                                val b = vp.worldToScreen(cs.x2, cs.y2)
                                drawLine(color = Color.Magenta, start = a, end = b, strokeWidth = 3f)
                            }
                        }
                    } else {
                        // mismatch: show hint
                        drawIntoCanvas { it.nativeCanvas.drawText("Mode/window mismatch. Choose appropriate mode for loaded data.", 20f, 40f, android.graphics.Paint().apply{ textSize = 18f }) }
                    }

                    // legend
                    drawIntoCanvas { c ->
                        val paint = android.graphics.Paint().apply{ textSize = 14f }
                        c.nativeCanvas.drawText("Gray: original segments", 12f, (canvasSize.height - 60).toFloat(), paint)
                        c.nativeCanvas.drawText("Red/Magenta: clipped visible parts", 12f, (canvasSize.height - 42).toFloat(), paint)
                        c.nativeCanvas.drawText("Blue/Green: clipping window/polygon", 12f, (canvasSize.height - 24).toFloat(), paint)
                    }
                })
            }
        }
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Clipping algorithms — Lab") {
        App()
    }
}
