package com.mcpintelligence.fr3k.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.protocol.DeviceStatus

/** Status dot — used everywhere we render an "online / offline / degraded" signal.
 *  Online/degraded dots breathe with a soft halo; colors cross-fade on state change.
 *  Layout footprint is exactly `size` (the halo is drawn, not measured). */
@Composable
fun StatusDot(
    status: DeviceStatus,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    val target = when (status) {
        DeviceStatus.ONLINE -> Fr3kPalette.Ok
        DeviceStatus.DEGRADED -> Fr3kPalette.Warn
        DeviceStatus.OFFLINE -> Fr3kPalette.Err
        DeviceStatus.UNKNOWN -> Fr3kPalette.TextDim
    }
    val color by animateColorAsState(target, tween(350), label = "dotColor")
    val pulsing = status == DeviceStatus.ONLINE || status == DeviceStatus.DEGRADED
    val transition = rememberInfiniteTransition(label = "dotPulse")
    val halo by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "dotHalo",
    )
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                if (pulsing) {
                    drawCircle(color = color.copy(alpha = halo * 0.45f), radius = size.toPx() * 1.4f)
                }
                drawCircle(color = color, radius = size.toPx() / 2f)
            },
    )
}

/**
 * The canonical HUD panel: layered vertical-gradient fill, a soft accent→teal
 * gradient border, tinted elevation and a section header with an accent bar.
 * All screens compose their content inside this so the shell stays coherent.
 */
@Composable
fun Fr3kPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    accent: Color = Fr3kPalette.Accent,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Fr3kDimens.RadiusPanel)
    Column(
        modifier = modifier
            .shadow(
                elevation = Fr3kDimens.PanelElevation,
                shape = shape,
                clip = false,
                ambientColor = accent.copy(alpha = 0.20f),
                spotColor = accent.copy(alpha = 0.28f),
            )
            .background(Fr3kGradients.PanelFill, shape)
            .border(Fr3kDimens.PanelBorder, Fr3kGradients.panelBorder(accent), shape)
            .padding(Fr3kDimens.PanelPadding),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 14.dp)
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.12f)))),
                )
                Spacer(Modifier.width(Fr3kDimens.SpacingSm))
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )
                if (subtitle != null) {
                    Spacer(Modifier.width(Fr3kDimens.SpacingSm))
                    Text(
                        text = subtitle.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Fr3kPalette.TextDim,
                    )
                }
            }
            Spacer(Modifier.height(Fr3kDimens.SpacingSm))
            Fr3kDivider(accent = accent)
            Spacer(Modifier.height(Fr3kDimens.SpacingMd))
        }
        content()
    }
}

/** Gradient hairline used under panel titles and in footers. */
@Composable
fun Fr3kDivider(modifier: Modifier = Modifier, accent: Color = Fr3kPalette.Accent) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.55f), Fr3kPalette.Ok.copy(alpha = 0.30f), Fr3kPalette.Border.copy(alpha = 0.15f)),
                ),
            ),
    )
}

/** Gradient-painted hero text (screen titles). Keeps the monospace identity. */
@Composable
fun Fr3kGradientText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(Fr3kPalette.Accent, Fr3kPalette.Ok),
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(brush = Fr3kGradients.titleBrush(colors)),
    )
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(Fr3kDimens.SpacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            modifier = Modifier.width(92.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Fr3kPalette.TextDim,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Fr3kPalette.Text,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** Pill badge with a tinted fill + animated color transitions. */
@Composable
fun Fr3kBadge(text: String, color: Color = Fr3kPalette.Accent, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Fr3kDimens.RadiusChip)
    val fg by animateColorAsState(color, tween(300), label = "badgeFg")
    val bg by animateColorAsState(color.copy(alpha = 0.12f), tween(300), label = "badgeBg")
    val brd by animateColorAsState(color.copy(alpha = 0.55f), tween(300), label = "badgeBrd")
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, brd, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text.uppercase(),
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Primary action: purple→magenta gradient, press-scale, teal glow on press. */
@Composable
fun Fr3kButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = Fr3kPalette.Accent,
) {
    val shape = RoundedCornerShape(Fr3kDimens.RadiusRow)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.965f else 1f, tween(120), label = "btnScale")
    val glow by animateColorAsState(
        if (pressed && enabled) Fr3kPalette.Ok.copy(alpha = 0.75f) else Color.Transparent,
        tween(160),
        label = "btnGlow",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (enabled) 6.dp else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = accent.copy(alpha = 0.25f),
                spotColor = accent.copy(alpha = 0.45f),
            )
            .clip(shape)
            .background(
                if (enabled) Brush.horizontalGradient(listOf(accent, Fr3kPalette.Magenta.copy(alpha = 0.88f)))
                else Brush.horizontalGradient(listOf(Fr3kPalette.Surface, Fr3kPalette.SurfaceLow)),
                shape,
            )
            .border(1.dp, glow, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Fr3kPalette.Bg else Fr3kPalette.TextDim,
        )
    }
}

/** Secondary action: ghost outline that warms to the accent on press. */
@Composable
fun Fr3kOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = Fr3kPalette.Accent,
) {
    val shape = RoundedCornerShape(Fr3kDimens.RadiusRow)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, tween(120), label = "outScale")
    val borderColor by animateColorAsState(
        when {
            !enabled -> Fr3kPalette.Border.copy(alpha = 0.35f)
            pressed -> accent
            else -> accent.copy(alpha = 0.55f)
        },
        tween(160),
        label = "outBorder",
    )
    val fillColor by animateColorAsState(
        if (pressed && enabled) accent.copy(alpha = 0.12f) else Color.Transparent,
        tween(160),
        label = "outFill",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(fillColor, shape)
            .border(1.dp, borderColor, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            color = if (enabled) accent else Fr3kPalette.TextDim,
        )
    }
}

/** Tertiary action: filled surface chip (destructive/reset/neutral verbs). */
@Composable
fun Fr3kGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = Fr3kPalette.Text,
) {
    val shape = RoundedCornerShape(Fr3kDimens.RadiusRow)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, tween(120), label = "ghostScale")
    val fillColor by animateColorAsState(
        if (pressed && enabled) Fr3kPalette.SurfaceHigh else Fr3kPalette.Surface,
        tween(160),
        label = "ghostFill",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(fillColor, shape)
            .border(1.dp, Fr3kPalette.Border.copy(alpha = 0.7f), shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            color = if (enabled) contentColor else Fr3kPalette.TextDim,
        )
    }
}

/**
 * Tappable list row (navigation, suggestions, quick actions). Animated
 * background tint + border + micro-scale on press; keeps the monospace grid.
 */
@Composable
fun Fr3kRowItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    accent: Color = Fr3kPalette.Accent,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Fr3kDimens.RadiusRow)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        if (pressed && enabled) accent.copy(alpha = 0.14f) else Color.Transparent,
        tween(140),
        label = "rowBg",
    )
    val brd by animateColorAsState(
        if (pressed && enabled) accent.copy(alpha = 0.45f) else Fr3kPalette.Border.copy(alpha = 0.40f),
        tween(140),
        label = "rowBrd",
    )
    val scale by animateFloatAsState(if (pressed && enabled) 0.985f else 1f, tween(120), label = "rowScale")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, brd, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Fr3kDimens.SpacingSm))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                color = if (enabled) accent else Fr3kPalette.TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    color = Fr3kPalette.TextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Fr3kDimens.SpacingSm))
            trailing()
        }
    }
}
