package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.service.ZinaidaVoiceService.AssistantState

@Composable
fun AudioWaveVisualizer(
    amplitude: Float,
    state: AssistantState,
    isServiceRunning: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_anim")
    val pulseAnim by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
    ) {
        val width = size.width
        val height = size.height
        val barCount = 28
        val barWidth = (width / barCount) * 0.55f
        val spacing = (width / barCount) * 0.45f
        val centerY = height / 2f

        for (i in 0 until barCount) {
            val distanceFromCenter = kotlin.math.abs(i - barCount / 2f) / (barCount / 2f)
            val bellFactor = 1f - (distanceFromCenter * 0.6f)

            val baseHeight = when {
                !isServiceRunning -> 6f
                state == AssistantState.SPEAKING -> {
                    val offsetPhase = kotlin.math.sin((i * 0.5f) + (pulseAnim * 6.28f))
                    (20f + offsetPhase * 18f + (pulseAnim * 30f)) * bellFactor
                }
                state == AssistantState.COMMAND_LISTENING -> {
                    val audioBoost = amplitude * 180f
                    (12f + audioBoost + (pulseAnim * 16f)) * bellFactor
                }
                state == AssistantState.PROCESSING -> {
                    val wave = kotlin.math.sin((i * 0.8f) + (pulseAnim * 8f))
                    (16f + wave * 16f) * bellFactor
                }
                else -> {
                    // IDLE_LISTENING
                    val audioBoost = amplitude * 80f
                    (8f + audioBoost + kotlin.math.sin(i * 0.4f + pulseAnim * 3f) * 6f) * bellFactor
                }
            }.coerceIn(6f, height - 8f)

            val x = i * (barWidth + spacing) + spacing / 2f
            val top = centerY - (baseHeight / 2f)

            val barColor = when {
                !isServiceRunning -> Color.Gray.copy(alpha = 0.35f)
                state == AssistantState.COMMAND_LISTENING -> tertiaryColor
                state == AssistantState.SPEAKING -> secondaryColor
                state == AssistantState.PROCESSING -> primaryColor
                else -> primaryColor.copy(alpha = 0.75f)
            }

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        barColor.copy(alpha = 0.4f),
                        barColor,
                        barColor.copy(alpha = 0.4f)
                    ),
                    startY = top,
                    endY = top + baseHeight
                ),
                topLeft = Offset(x, top),
                size = Size(barWidth, baseHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
