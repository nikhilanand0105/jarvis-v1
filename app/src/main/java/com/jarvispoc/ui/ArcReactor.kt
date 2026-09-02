package com.jarvispoc.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ArcReactor(modifier: Modifier = Modifier, color: Color = Color(0xFF00E5FF)) {
    val infiniteTransition = rememberInfiniteTransition(label = "arc_reactor")
    val rotation1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation1"
    )
    val rotation2 by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation2"
    )

    Box(modifier = modifier.aspectRatio(1f)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val maxRadius = size.minDimension / 2
            
            // Outer dashed ring
            rotate(rotation1, center) {
                drawDashedRing(
                    color = color.copy(alpha = 0.6f),
                    radius = maxRadius * 0.95f,
                    strokeWidth = maxRadius * 0.04f,
                    dashCount = 30,
                    dashSweep = 8f
                )
            }

            // Inner solid ring with segments
            rotate(rotation2, center) {
                drawRingWithGaps(
                    color = color.copy(alpha = 0.8f),
                    radius = maxRadius * 0.8f,
                    strokeWidth = maxRadius * 0.1f,
                    gapAngle = 20f,
                    segments = 6
                )
            }
            
            // Central glowing triangle/core
            drawCentralCore(color = color, radius = maxRadius * 0.5f)
        }
    }
}

private fun DrawScope.drawDashedRing(
    color: Color,
    radius: Float,
    strokeWidth: Float,
    dashCount: Int,
    dashSweep: Float
) {
    val step = 360f / dashCount
    for (i in 0 until dashCount) {
        drawArc(
            color = color,
            startAngle = i * step,
            sweepAngle = dashSweep,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

private fun DrawScope.drawRingWithGaps(
    color: Color,
    radius: Float,
    strokeWidth: Float,
    gapAngle: Float,
    segments: Int
) {
    val sweepAngle = (360f / segments) - gapAngle
    val step = 360f / segments
    for (i in 0 until segments) {
        drawArc(
            color = color,
            startAngle = i * step + (gapAngle / 2),
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Square)
        )
    }
}

private fun DrawScope.drawCentralCore(color: Color, radius: Float) {
    // Draw an inverted triangle
    val path = Path().apply {
        val angle1 = Math.toRadians(90.0).toFloat()
        val angle2 = Math.toRadians(210.0).toFloat()
        val angle3 = Math.toRadians(330.0).toFloat()
        
        moveTo(
            center.x + radius * cos(angle1),
            center.y + radius * sin(angle1)
        )
        lineTo(
            center.x + radius * cos(angle2),
            center.y + radius * sin(angle2)
        )
        lineTo(
            center.x + radius * cos(angle3),
            center.y + radius * sin(angle3)
        )
        close()
    }
    
    // Triangle Outline
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = radius * 0.1f, cap = StrokeCap.Round)
    )
    
    // Central bright dot
    drawCircle(
        color = Color.White,
        radius = radius * 0.3f,
        center = center
    )
    
    // Glowing halo around the dot
    drawCircle(
        color = color.copy(alpha = 0.5f),
        radius = radius * 0.5f,
        center = center
    )
}
