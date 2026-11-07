package com.relkin.shelly.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relkin.shelly.data.Sample
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun MetricTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** «Красивый» верх шкалы: 1, 2, 2.5, 5 × 10ⁿ. */
fun niceMax(v: Double): Double {
    if (v <= 0) return 10.0
    val mag = 10.0.pow(ceil(log10(v)) - 1)
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= v }
}

/** Линейный график мощности с заливкой. Время по X — от now-rangeMs до now. */
@Composable
fun PowerChart(samples: List<Sample>, rangeMs: Long, now: Long, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pts = samples.filter { it.t >= now - rangeMs }
    val top = niceMax((pts.maxOfOrNull { it.w } ?: 0.0) * 1.1)

    Row(modifier) {
        Column(Modifier.fillMaxHeight().padding(end = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(axis(top), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(axis(top / 2), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text("0", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
            for (i in 0..2) {
                val y = size.height * i / 2f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = if (i == 2) null else dash)
            }
            if (pts.size < 2) return@Canvas
            fun x(t: Long) = ((t - (now - rangeMs)).toFloat() / rangeMs) * size.width
            fun y(w: Double) = size.height - (w / top).toFloat() * size.height
            val path = Path().apply {
                moveTo(x(pts[0].t), y(pts[0].w))
                for (i in 1 until pts.size) lineTo(x(pts[i].t), y(pts[i].w))
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(x(pts.last().t), size.height)
                lineTo(x(pts.first().t), size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0.02f))))
            drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(line, 4.dp.toPx(), Offset(x(pts.last().t), y(pts.last().w)))
        }
    }
}

private fun axis(w: Double): String = when {
    w >= 1000 -> "%.1f кВт".format(w / 1000).replace(Regex("[.,]0 "), " ")
    else -> "%.0f".format(w)
}
