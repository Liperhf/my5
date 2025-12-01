import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import javax.swing.JFileChooser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// --- Data classes ---
data class DSegment(val x1: Double, val y1: Double, val x2: Double, val y2: Double)
data class DRect(val xmin: Double, val ymin: Double, val xmax: Double, val ymax: Double)

data class DBBox(val xmin: Double, val ymin: Double, val xmax: Double, val ymax: Double)

// Parser for input format
private fun parseInputFileDesktop(file: File): Triple<List<DSegment>, DRect?, List<Pair<Double, Double>>?> {
    val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    require(lines.isNotEmpty()) { "Пустой файл" }
    val n = lines[0].split(Regex("\\s+")).first().toInt()
    require(lines.size >= 1 + n + 1) { "Недостаточно строк" }
    val segs = mutableListOf<DSegment>()
    for (i in 1..n) {
        val p = lines[i].split(Regex("\\s+")).map { it.toDouble() }
        require(p.size >= 4) { "Строка отрезка должна содержать 4 числа" }
        segs += DSegment(p[0], p[1], p[2], p[3])
    }
    val last = lines[1 + n].split(Regex("\\s+")).map { it.toDouble() }
    return if (last.size == 4) {
        Triple(segs, DRect(last[0], last[1], last[2], last[3]), null)
    } else if (last.size >= 6 && last.size % 2 == 0) {
        Triple(segs, null, last.chunked(2).map { it[0] to it[1] })
    } else {
        error("Последняя строка должна содержать 4 числа (прямоугольник) или чётное число >= 6 (многоугольник)")
    }
}

// World -> Screen mapping
private class DViewport(var bbox: DBBox, var size: IntSize, var paddingPx: Int = 60) {
    fun worldToScreen(x: Double, y: Double): Offset {
        val w = (size.width - paddingPx * 2).coerceAtLeast(1)
        val h = (size.height - paddingPx * 2).coerceAtLeast(1)
        val sx = if (bbox.xmax - bbox.xmin == 0.0) 1.0 else (x - bbox.xmin) / (bbox.xmax - bbox.xmin)
        val sy = if (bbox.ymax - bbox.ymin == 0.0) 1.0 else (y - bbox.ymin) / (bbox.ymax - bbox.ymin)
        return Offset((paddingPx + sx * w).toFloat(), (paddingPx + (1.0 - sy) * h).toFloat())
    }
}

private fun computeBBoxDesktop(segs: List<DSegment>, rect: DRect?, polygon: List<Pair<Double, Double>>?): DBBox {
    var xmin = Double.POSITIVE_INFINITY
    var ymin = Double.POSITIVE_INFINITY
    var xmax = Double.NEGATIVE_INFINITY
    var ymax = Double.NEGATIVE_INFINITY
    for (s in segs) {
        xmin = min(xmin, min(s.x1, s.x2))
        ymin = min(ymin, min(s.y1, s.y2))
        xmax = max(xmax, max(s.x1, s.x2))
        ymax = max(ymax, max(s.y1, s.y2))
    }
    rect?.let {
        xmin = min(xmin, it.xmin)
        ymin = min(ymin, it.ymin)
        xmax = max(xmax, it.xmax)
        ymax = max(ymax, it.ymax)
    }
    polygon?.forEach { (x, y) ->
        xmin = min(xmin, x)
        ymin = min(ymin, y)
        xmax = max(xmax, x)
        ymax = max(ymax, y)
    }
    val dx = (xmax - xmin).takeIf { it > 0 } ?: 1.0
    val dy = (ymax - ymin).takeIf { it > 0 } ?: 1.0
    return DBBox(xmin - dx * 0.1, ymin - dy * 0.1, xmax + dx * 0.1, ymax + dy * 0.1)
}

// Midpoint clipping (rectangular window)
private fun insideRect(x: Double, y: Double, r: DRect) = x >= r.xmin && x <= r.xmax && y >= r.ymin && y <= r.ymax
private fun outcode(x: Double, y: Double, r: DRect): Int {
    var c = 0
    if (x < r.xmin) c = c or 1
    if (x > r.xmax) c = c or 2
    if (y < r.ymin) c = c or 4
    if (y > r.ymax) c = c or 8
    return c
}
private fun midpointClip(s: DSegment, r: DRect, eps: Double = 1e-3, maxDepth: Int = 50): List<DSegment> {
    val out = mutableListOf<DSegment>()
    fun rec(x1: Double, y1: Double, x2: Double, y2: Double, d: Int) {
        if (d > maxDepth) return
        val aIn = insideRect(x1, y1, r)
        val bIn = insideRect(x2, y2, r)
        if (aIn && bIn) { out += DSegment(x1, y1, x2, y2); return }
        val c1 = outcode(x1, y1, r)
        val c2 = outcode(x2, y2, r)
        if ((c1 and c2) != 0) return
        val dx = x2 - x1; val dy = y2 - y1
        if (abs(dx) + abs(dy) < eps) return
        val mx = (x1 + x2) / 2.0; val my = (y1 + y2) / 2.0
        val mIn = insideRect(mx, my, r)
        if (!mIn && !aIn && !bIn) {
            if ((c1 and c2) != 0) return
        }
        rec(x1, y1, mx, my, d + 1)
        rec(mx, my, x2, y2, d + 1)
    }
    rec(s.x1, s.y1, s.x2, s.y2, 0)
    return out
}

// Cyrus–Beck for convex polygon
private fun dot(ax: Double, ay: Double, bx: Double, by: Double) = ax * bx + ay * by
private fun sub(ax: Double, ay: Double, bx: Double, by: Double) = Pair(ax - bx, ay - by)
private fun edgeNormal(x1: Double, y1: Double, x2: Double, y2: Double): Pair<Double, Double> {
    val ex = x2 - x1; val ey = y2 - y1
    // CCW polygon -> outward normal
    return Pair(ey, -ex)
}
private fun cyrusBeck(s: DSegment, poly: List<Pair<Double, Double>>): DSegment? {
    var tE = 0.0
    var tL = 1.0
    val dx = s.x2 - s.x1
    val dy = s.y2 - s.y1
    val m = poly.size
    for (i in 0 until m) {
        val (xk, yk) = poly[i]
        val (xk1, yk1) = poly[(i + 1) % m]
        val (nx, ny) = edgeNormal(xk, yk, xk1, yk1)
        val (wx, wy) = sub(s.x1, s.y1, xk, yk)
        val num = dot(nx, ny, wx, wy)
        val den = dot(nx, ny, dx, dy)
        if (abs(den) < 1e-12) {
            if (num < 0) return null else continue
        }
        val t = -num / den
        if (den < 0) { if (t > tE) tE = t } else { if (t < tL) tL = t }
        if (tE > tL) return null
    }
    if (tE <= tL) {
        val cx1 = s.x1 + dx * tE
        val cy1 = s.y1 + dy * tE
        val cx2 = s.x1 + dx * tL
        val cy2 = s.y1 + dy * tL
        return DSegment(cx1, cy1, cx2, cy2)
    }
    return null
}

@Composable
fun DesktopApp() {
    var segments by remember { mutableStateOf<List<DSegment>>(emptyList()) }
    var rect by remember { mutableStateOf<DRect?>(null) }
    var polygon by remember { mutableStateOf<List<Pair<Double, Double>>?>(null) }
    var mode by remember { mutableStateOf(1) } // 1: midpoint rect, 2: convex polygon
    var error by remember { mutableStateOf<String?>(null) }

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    try {
                        val chooser = JFileChooser()
                        val rc = chooser.showOpenDialog(null)
                        if (rc == JFileChooser.APPROVE_OPTION) {
                            val f = chooser.selectedFile
                            val (segs, r, poly) = parseInputFileDesktop(f)
                            segments = segs
                            rect = r
                            polygon = poly
                            error = null
                        }
                    } catch (e: Exception) { error = e.message }
                }) { Text("Загрузить файл") }

                Spacer(Modifier.width(12.dp))
                Text("Режим:")
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == 1, onClick = { mode = 1 })
                    Text("Средняя точка (прямоугольник)", modifier = Modifier.padding(end = 12.dp))
                    RadioButton(selected = mode == 2, onClick = { mode = 2 })
                    Text("Выпуклый многоугольник (Cyrus–Beck)")
                }
                Spacer(Modifier.weight(1f))
                error?.let { Text("Ошибка: $it", color = Color.Red) }
            }

            Spacer(Modifier.height(8.dp))

            // Info bar: what is loaded now
            val windowType = when {
                rect != null -> "Загружено окно: прямоугольник"
                polygon != null -> "Загружено окно: выпуклый многоугольник (${polygon!!.size} вершин)"
                else -> "Окно не загружено"
            }
            Surface(color = Color(0xFFEFF7FF), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(windowType)
                    Spacer(Modifier.width(12.dp))
                    // Quick actions
                    Button(onClick = {
                        // Rectangle sample
                        segments = listOf(
                            DSegment(-5.0, -3.0, 8.0, 6.0),
                            DSegment(-2.0, 4.0, 7.0, 1.0),
                            DSegment(1.0, 1.0, 4.0, 4.0),
                            DSegment(-8.0, -8.0, -6.0, -6.0),
                            DSegment(0.0, -1.0, 3.0, 5.0),
                            DSegment(2.0, 0.0, 6.0, 2.0)
                        )
                        rect = DRect(0.0, 0.0, 5.0, 4.0)
                        polygon = null
                        mode = 1
                    }, modifier = Modifier.padding(end = 8.dp)) { Text("Пример: прямоугольник") }

                    Button(onClick = {
                        // Convex polygon sample (четырёхугольник CCW)
                        segments = listOf(
                            DSegment(-5.0, -3.0, 8.0, 6.0),
                            DSegment(-2.0, 4.0, 7.0, 1.0),
                            DSegment(1.0, 1.0, 4.0, 4.0),
                            DSegment(0.0, -1.0, 3.0, 5.0),
                            DSegment(2.0, 0.0, 6.0, 2.0)
                        )
                        polygon = listOf(0.0 to 0.0, 5.0 to 0.0, 5.0 to 4.0, 0.0 to 4.0)
                        rect = null
                        mode = 2
                    }) { Text("Пример: многоугольник") }
                }
            }

            // Mismatch warning and helper
            val mismatch = (mode == 1 && rect == null) || (mode == 2 && polygon == null)
            if (mismatch) {
                Spacer(Modifier.height(8.dp))
                Surface(color = Color(0xFFFFF4E5), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Выбранный режим не совпадает с типом загруженного окна.", color = Color(0xFF8A6D3B))
                        if (mode == 2 && rect != null) {
                            Spacer(Modifier.height(4.dp))
                            Button(onClick = {
                                val r = rect!!
                                polygon = listOf(r.xmin to r.ymin, r.xmax to r.ymin, r.xmax to r.ymax, r.xmin to r.ymax)
                                rect = null
                            }) { Text("Использовать текущий прямоугольник как многоугольник") }
                        }
                        if (mode == 1 && polygon != null) {
                            Spacer(Modifier.height(4.dp))
                            Button(onClick = {
                                // Попробовать вычислить ограничивающий прямоугольник
                                val xs = polygon!!.map { it.first }
                                val ys = polygon!!.map { it.second }
                                rect = DRect(xs.minOrNull() ?: 0.0, ys.minOrNull() ?: 0.0, xs.maxOrNull() ?: 0.0, ys.maxOrNull() ?: 0.0)
                                polygon = null
                            }) { Text("Преобразовать многоугольник в ограничивающий прямоугольник") }
                        }
                    }
                }
            }

            Box(Modifier.fillMaxSize().background(Color(0xFFFAFAFA))) {
                Canvas(Modifier.fillMaxSize()) {
                    if (segments.isEmpty()) return@Canvas
                    val canvasSize = IntSize(size.width.toInt(), size.height.toInt())
                    val bbox = computeBBoxDesktop(segments, rect, polygon)
                    val vp = DViewport(bbox, canvasSize, 60)

                    // Axes
                    if (bbox.xmin < 0 && bbox.xmax > 0) {
                        val p1 = vp.worldToScreen(0.0, bbox.ymin)
                        val p2 = vp.worldToScreen(0.0, bbox.ymax)
                        drawLine(Color.LightGray, p1, p2, 1f)
                    }
                    if (bbox.ymin < 0 && bbox.ymax > 0) {
                        val p1 = vp.worldToScreen(bbox.xmin, 0.0)
                        val p2 = vp.worldToScreen(bbox.xmax, 0.0)
                        drawLine(Color.LightGray, p1, p2, 1f)
                    }

                    // Window / polygon
                    rect?.let { r ->
                        val p1 = vp.worldToScreen(r.xmin, r.ymin)
                        val p2 = vp.worldToScreen(r.xmax, r.ymin)
                        val p3 = vp.worldToScreen(r.xmax, r.ymax)
                        val p4 = vp.worldToScreen(r.xmin, r.ymax)
                        val path = Path().apply {
                            moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); lineTo(p4.x, p4.y); close()
                        }
                        drawPath(path, color = Color(0x1100AAFF))
                        drawPath(path, color = Color.Blue, style = Stroke(width = 2f))
                    }
                    polygon?.let { poly ->
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
                            drawPath(path, color = Color.Green, style = Stroke(width = 2f))
                        }
                    }

                    // Original segments
                    for (s in segments) {
                        val a = vp.worldToScreen(s.x1, s.y1)
                        val b = vp.worldToScreen(s.x2, s.y2)
                        drawLine(Color.Gray, a, b, 2f)
                    }

                    // Clipped
                    when {
                        mode == 1 && rect != null -> {
                            for (s in segments) {
                                val parts = midpointClip(s, rect!!)
                                for (cs in parts) {
                                    val a = vp.worldToScreen(cs.x1, cs.y1)
                                    val b = vp.worldToScreen(cs.x2, cs.y2)
                                    drawLine(Color.Red, a, b, 3f)
                                }
                            }
                        }
                        mode == 2 && polygon != null -> {
                            for (s in segments) {
                                val cs = cyrusBeck(s, polygon!!)
                                if (cs != null) {
                                    val a = vp.worldToScreen(cs.x1, cs.y1)
                                    val b = vp.worldToScreen(cs.x2, cs.y2)
                                    drawLine(Color.Magenta, a, b, 3f)
                                }
                            }
                        }
                    }
                }

                // Legend
                Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Text("Серый: исходные отрезки", color = Color.Gray)
                    Text("Красный/Пурпурный: видимые части", color = Color.Red)
                    Text("Синий/Зелёный: окно/многоугольник")
                }
            }
        }
    }
}
