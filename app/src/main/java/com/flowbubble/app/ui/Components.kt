package com.flowbubble.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowbubble.app.ui.theme.BubbleShapes
import com.flowbubble.app.ui.theme.Ink

internal val ReadyGreen = Ink.Jade
internal val NeedAmber = Ink.Amber
internal val WarnRed = Ink.Crimson

/** A card lifted off the black: burgundy-tinted top edge, lavender hairline, no hard shadow. */
@Composable
internal fun FlowCard(
    modifier: Modifier = Modifier,
    accent: Color = Ink.LavenderDeep,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BubbleShapes.Card)
            .background(
                Brush.verticalGradient(
                    0f to Ink.Onyx,
                    0.55f to Ink.Obsidian,
                    1f to Ink.Void
                )
            )
            .drawBehind {
                drawLine(
                    brush = Brush.horizontalGradient(
                        0f to accent.copy(alpha = 0.0f),
                        0.18f to accent.copy(alpha = 0.55f),
                        0.82f to Ink.Burgundy.copy(alpha = 0.55f),
                        1f to Ink.Burgundy.copy(alpha = 0f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.6.dp.toPx()
                )
            }
            .border(1.dp, Ink.Lavender.copy(alpha = 0.10f), BubbleShapes.Card)
            .padding(18.dp),
        content = content
    )
}

@Composable
internal fun SectionLabel(text: String, color: Color = Ink.Lavender) {
    Text(
        text = text.uppercase(),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
internal fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 21.sp
    )
}

@Composable
internal fun StatusChip(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(BubbleShapes.Chip)
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.32f), BubbleShapes.Chip)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

enum class FlowButtonTone { Primary, Danger, Ghost }

@Composable
internal fun FlowPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: FlowButtonTone = FlowButtonTone.Primary,
    onClick: () -> Unit
) {
    val fill = when (tone) {
        FlowButtonTone.Primary -> Brush.horizontalGradient(listOf(Ink.LavenderDeep, Ink.Burgundy))
        FlowButtonTone.Danger -> Brush.horizontalGradient(listOf(Ink.Crimson, Ink.Burgundy))
        FlowButtonTone.Ghost -> Brush.horizontalGradient(listOf(Ink.Smoke, Ink.Onyx))
    }
    val contentColor = when (tone) {
        FlowButtonTone.Ghost -> Ink.Lavender
        else -> Color(0xFFFDFBFF)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(BubbleShapes.Button)
            .background(if (enabled) fill else Brush.horizontalGradient(listOf(Ink.Smoke, Ink.Smoke)))
            .border(
                1.dp,
                if (enabled) Ink.Lavender.copy(alpha = 0.28f) else Color.Transparent,
                BubbleShapes.Button
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else Ink.Whisper,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            letterSpacing = 0.2.sp
        )
    }
}

@Composable
internal fun FlowGhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (danger) Ink.Crimson else Ink.Lavender
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(BubbleShapes.Button)
            .background(Ink.Smoke.copy(alpha = 0.6f))
            .border(1.dp, tint.copy(alpha = 0.35f), BubbleShapes.Button)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) tint else Ink.Whisper,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}

/** The switch used by every setting row: lavender when off, crimson-lit when on. */
@Composable
internal fun FlowToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val track = animateColorAsState(
        if (checked) Ink.Burgundy else Ink.Smoke,
        label = "toggle-track"
    )
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Ink.Lavender,
            checkedTrackColor = track.value,
            checkedBorderColor = Ink.Crimson.copy(alpha = 0.45f),
            uncheckedThumbColor = Ink.Faint,
            uncheckedTrackColor = Ink.Onyx,
            uncheckedBorderColor = Ink.Veil,
            disabledCheckedThumbColor = Ink.Whisper,
            disabledUncheckedThumbColor = Ink.Whisper
        )
    )
}

@Composable
internal fun FlowRow(
    title: String,
    detail: String,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Ink.Mist)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Ink.Faint, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.size(12.dp))
        trailing()
    }
}
