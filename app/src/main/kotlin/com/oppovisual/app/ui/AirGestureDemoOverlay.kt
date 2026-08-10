package com.oppovisual.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppovisual.core.GestureId
import com.oppovisual.core.ProductInteractionStatus
import com.oppovisual.core.ProductScaleStatus

private val DemoChrome = Color(0xFF101719).copy(alpha = 0.90f)
private val DemoMint = Color(0xFF7CFFD9)

/** Shows the ready affordance without exposing internal FSM states. */
@Composable
fun AirGestureDemoOverlay(
    state: RecognitionUiState,
    modifier: Modifier = Modifier,
) {
    val pairReady = state.scaleStatus == ProductScaleStatus.READY
    val singleReady = state.interactionStatus == ProductInteractionStatus.READY
    val ready = state.handPresent && (pairReady || singleReady)
    val readyGesture = state.gestureHands
        .maxByOrNull { it.staticPrediction?.confidence ?: -1f }
        ?.staticPrediction
        ?.gesture
        ?: state.gesture

    Column(
        modifier = modifier.padding(top = 14.dp, start = 18.dp, end = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = ready,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                color = DemoChrome,
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = if (pairReady) "↔" else gestureIcon(readyGesture),
                        fontSize = 28.sp,
                        modifier = Modifier.size(34.dp),
                    )
                    Column {
                        Text(
                            text = if (pairReady) "双手放缩已就绪" else "已就绪",
                            color = DemoMint,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = if (pairReady) "双手握拳开始调整" else "开始滑动或缩放",
                            color = Color.White.copy(alpha = 0.68f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun gestureIcon(gesture: GestureId?): String = when (gesture) {
    GestureId.OPEN_PALM -> "🖐"
    GestureId.STOP, GestureId.STOP_INVERTED -> "🤚"
    GestureId.CLOSED_FIST -> "✊"
    GestureId.POINT -> "🫵"
    GestureId.GRABBING -> "🤌"
    GestureId.THUMB_INDEX, GestureId.THUMB_INDEX_PAIR -> "🤏"
    else -> "✋"
}
