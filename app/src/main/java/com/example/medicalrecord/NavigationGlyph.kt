package com.example.medicalrecord

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun NavigationGlyph(index: Int, selected: Boolean) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    val color by animateColorAsState(
        targetValue = if (selected) activeColor else inactiveColor,
        animationSpec = tween(durationMillis = 200),
        label = "navGlyphColor"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "navGlyphScale"
    )

    Canvas(
        Modifier
            .size(24.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        val unit = size.width / 24f
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        val stroke = (if (selected) 2.1f else 1.8f) * unit
        when (index) {
            0 -> {
                val roof = Path().apply {
                    moveTo(3f * unit, 10.5f * unit)
                    lineTo(12f * unit, 3.5f * unit)
                    lineTo(21f * unit, 10.5f * unit)
                }
                drawPath(roof, color, style = Stroke(stroke, cap = StrokeCap.Round))
                val house = Path().apply {
                    moveTo(5.5f * unit, 9.5f * unit)
                    lineTo(5.5f * unit, 20f * unit)
                    lineTo(18.5f * unit, 20f * unit)
                    lineTo(18.5f * unit, 9.5f * unit)
                }
                drawPath(house, color, style = Stroke(stroke, cap = StrokeCap.Round))
                drawLine(color, p(9.5f, 20f), p(9.5f, 14.5f), stroke, cap = StrokeCap.Round)
                drawLine(color, p(9.5f, 14.5f), p(14.5f, 14.5f), stroke, cap = StrokeCap.Round)
                drawLine(color, p(14.5f, 14.5f), p(14.5f, 20f), stroke, cap = StrokeCap.Round)
            }
            1 -> {
                drawRoundRect(
                    color, p(5f, 3f), Size(14f * unit, 18f * unit), CornerRadius(2.5f * unit),
                    style = Stroke(stroke)
                )
                drawLine(color, p(8f, 9f), p(16f, 9f), stroke, cap = StrokeCap.Round)
                drawLine(color, p(8f, 13f), p(16f, 13f), stroke, cap = StrokeCap.Round)
                drawLine(color, p(8f, 17f), p(13f, 17f), stroke, cap = StrokeCap.Round)
            }
            2 -> {
                drawRoundRect(
                    color, p(3.5f, 5f), Size(17f * unit, 16f * unit), CornerRadius(2.5f * unit),
                    style = Stroke(stroke)
                )
                drawLine(color, p(3.5f, 10f), p(20.5f, 10f), stroke)
                drawLine(color, p(8f, 3f), p(8f, 7f), stroke, cap = StrokeCap.Round)
                drawLine(color, p(16f, 3f), p(16f, 7f), stroke, cap = StrokeCap.Round)
                drawCircle(color, 1.2f * unit, p(8f, 14f))
                drawCircle(color, 1.2f * unit, p(13f, 14f))
                drawCircle(color, 1.2f * unit, p(8f, 18f))
            }
            else -> {
                drawCircle(color, 7.5f * unit, p(12f, 12f), style = Stroke(stroke))
                drawCircle(color, 2.5f * unit, p(12f, 12f), style = Stroke(stroke))
                for (step in 0 until 8) {
                    val angle = step * Math.PI / 4
                    val dx = kotlin.math.cos(angle).toFloat()
                    val dy = kotlin.math.sin(angle).toFloat()
                    drawLine(color, p(12f + 8f * dx, 12f + 8f * dy), p(12f + 10.5f * dx, 12f + 10.5f * dy), stroke, cap = StrokeCap.Round)
                }
            }
        }
    }
}
