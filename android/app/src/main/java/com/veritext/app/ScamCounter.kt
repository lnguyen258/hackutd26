package com.veritext.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Notification-style badge: warning icon + running total of scams caught; pops and shows "+N" on each new one. */
@Composable
fun ScamCounter(count: Int) {
    var prev by remember { mutableIntStateOf(count) }
    var delta by remember { mutableIntStateOf(0) }
    val pop = remember { Animatable(1f) }
    val plus = remember { Animatable(0f) }

    LaunchedEffect(count) {
        if (count > prev) {
            delta = count - prev
            launch {
                pop.animateTo(1.35f, tween(110))
                pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
            plus.snapTo(1f)
            plus.animateTo(0f, tween(1500))
        }
        prev = count
    }

    // Always red so it can never be mistaken for a legit/verified message counter.
    val tint = dangerColor()
    Box(contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                .clip(RoundedCornerShape(50))
                .background(tint.copy(alpha = 0.14f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WarnBadge(16.dp)
            Text("$count", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = tint, maxLines = 1, softWrap = false)
        }
        if (plus.value > 0f) {
            Text(
                "+$delta", color = dangerColor(), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = plus.value
                    translationY = -(1f - plus.value) * 46f - 28f
                },
            )
        }
    }
}
