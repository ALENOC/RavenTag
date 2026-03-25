package io.raventag.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import io.raventag.app.R
import io.raventag.app.ui.theme.RavenMuted
import io.raventag.app.ui.theme.RavenOrange
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var phase by remember { mutableStateOf(0) } // 0=invisible, 1=visible, 2=fading out

    LaunchedEffect(Unit) {
        delay(100)
        phase = 1       // fade in
        delay(1200)
        phase = 2       // fade out
        delay(400)
        onFinished()
    }

    val alpha by animateFloatAsState(
        targetValue = when (phase) { 1 -> 1f; else -> 0f },
        animationSpec = tween(durationMillis = if (phase == 2) 400 else 600, easing = FastOutSlowInEasing),
        label = "alpha"
    )
    val scale by animateFloatAsState(
        targetValue = when (phase) { 1 -> 1f; else -> 0.88f },
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .graphicsLayer(alpha = alpha, scaleX = scale, scaleY = scale)
                .padding(horizontal = 16.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.raven_logo),
                contentDescription = "RavenTag",
                tint = Color.Unspecified,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Protocol RTP-1",
                style = MaterialTheme.typography.labelSmall,
                color = RavenOrange.copy(alpha = 0.7f),
                letterSpacing = 3.sp
            )
        }
    }
}
