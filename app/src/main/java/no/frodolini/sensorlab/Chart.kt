package no.frodolini.sensorlab

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Enkel linjegraf. Kvar serie er ei rekkje verdiar med eldst først.
 * Grafen fyllest frå venstre og rullar når han er full ([HIST] punkt).
 * Y-aksen tilpassar seg verdiane, med mindre [fixedMin]/[fixedMax] er sett.
 */
@Composable
fun LineChart(
    series: List<FloatArray>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    decimals: Int = 2,
    fixedMin: Float? = null,
    fixedMax: Float? = null,
    marker: Float? = null,
    lineColor: Color? = null,
    capacity: Int = HIST,
) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    Canvas(modifier) {
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (s in series) for (x in s) {
            if (x < mn) mn = x
            if (x > mx) mx = x
        }
        if (mn > mx) { mn = 0f; mx = 1f }
        // Litt luft, og aldri eit heilt flatt område.
        val span = maxOf(mx - mn, 1e-3f, kotlin.math.abs(mx) * 0.002f)
        var lo = mn - span * 0.1f
        var hi = mx + span * 0.1f
        if (fixedMin != null) lo = fixedMin
        if (fixedMax != null) hi = fixedMax

        val left = 4.dp.toPx()
        val w = size.width - left
        val h = size.height
        fun y(v: Float) = h - (v - lo) / (hi - lo) * h

        for (k in 0..4) {
            val gy = h * k / 4f
            drawLine(grid, Offset(left, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
        }
        if (marker != null && marker in lo..hi) {
            drawLine(Color(0xFFD93A3A), Offset(left, y(marker)), Offset(size.width, y(marker)), strokeWidth = 2.dp.toPx())
        }
        for ((i, s) in series.withIndex()) {
            if (s.size < 2) continue
            val path = Path()
            for (k in s.indices) {
                val px = left + w * k / (capacity - 1).toFloat()
                val py = y(s[k].coerceIn(lo, hi))
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(
                path, lineColor ?: colors[i % colors.size],
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        drawText(measurer, fmt(hi, decimals), Offset(left + 4.dp.toPx(), 2.dp.toPx()), labelStyle)
        val bottom = measurer.measure(fmt(lo, decimals), labelStyle)
        drawText(bottom, topLeft = Offset(left + 4.dp.toPx(), h - bottom.size.height - 2.dp.toPx()))
    }
}
