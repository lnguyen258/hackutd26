package com.veritext.app

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val VerifiedBlue = Color(0xFF0A66C2)
val DangerRed = Color(0xFFD92D20)
val Magenta = Color(0xFFE20074)

private val Light = lightColorScheme(
    primary = Magenta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0F0),
    onPrimaryContainer = Color(0xFF5B0033),
    background = Color.White,
    surface = Color.White,
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFF1EFF1),
    onSurfaceVariant = Color(0xFF5F6368),
    outlineVariant = Color(0xFFE6E1E5),
    error = DangerRed,
)
private val Dark = darkColorScheme(
    primary = Color(0xFFFF5CAE),
    onPrimary = Color(0xFF3B0020),
    primaryContainer = Color(0xFF5B0033),
    onPrimaryContainer = Color(0xFFFFD9EA),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF2B2A2D),
    onSurfaceVariant = Color(0xFFB0B3B8),
    outlineVariant = Color(0xFF3A393C),
    error = Color(0xFFF97066),
)

@Composable
fun VeriTextTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

@Composable
fun dangerColor(): Color = if (isSystemInDarkTheme()) Color(0xFFF97066) else DangerRed

@Composable
fun verifiedColor(): Color = if (isSystemInDarkTheme()) Color(0xFF70B5F9) else VerifiedBlue

/** The LinkedIn-style blue check. */
@Composable
fun VerifiedBadge(badgeSize: Dp = 16.dp) {
    val c = verifiedColor()
    Canvas(Modifier.size(badgeSize)) {
        val w = size.width
        drawCircle(c)
        val p = Path().apply {
            moveTo(w * 0.28f, w * 0.52f)
            lineTo(w * 0.43f, w * 0.67f)
            lineTo(w * 0.73f, w * 0.35f)
        }
        drawPath(p, Color.White, style = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun WarnBadge(badgeSize: Dp = 16.dp) {
    val c = dangerColor()
    Canvas(Modifier.size(badgeSize)) {
        val w = size.width
        drawCircle(c)
        drawLine(Color.White, Offset(w * 0.5f, w * 0.24f), Offset(w * 0.5f, w * 0.58f), strokeWidth = w * 0.13f, cap = StrokeCap.Round)
        drawCircle(Color.White, radius = w * 0.075f, center = Offset(w * 0.5f, w * 0.76f))
    }
}

@Composable
fun Avatar(label: String, color: Color, avatarSize: Dp = 44.dp, fontSize: TextUnit = 18.sp) {
    Box(Modifier.size(avatarSize).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#", color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatusChip(r: VerifyResult?, retryHint: Boolean = false) {
    when {
        r == null -> Text(
            if (retryHint) "Couldn't verify — check connection" else "Checking…",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        r.decision == Decision.VERIFIED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            VerifiedBadge(14.dp)
            Text("Verified · ${r.orgName}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = verifiedColor())
        }
        r.decision == Decision.SUSPICIOUS -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WarnBadge(14.dp)
            Text(reasonText(r.reason), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = dangerColor())
        }
        else -> {}
    }
}

fun reasonText(reason: String) = when (reason) {
    "no_token" -> "Not verified — possible spoof"
    "body_mismatch" -> "Message was altered — not verified"
    "token_expired" -> "Verification expired"
    "unknown_token", "token_from_other_org" -> "Invalid verification code"
    "sender_not_registered" -> "Verification code from wrong number"
    "org_revoked" -> "Sender was revoked"
    else -> "Not verified"
}

fun formatWhen(ctx: android.content.Context, ms: Long): String =
    if (DateUtils.isToday(ms)) DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_TIME)
    else DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
