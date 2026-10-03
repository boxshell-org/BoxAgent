package com.boxagent.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boxagent.app.ui.theme.BwShape

/** Primary action — filled ink pill, spring scale + color fade on press. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pillScale",
    )
    val bgTarget = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        filled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface
    }
    val fgTarget = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        filled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.primary
    }
    val bg by animateColorAsState(bgTarget, tween(180), label = "pillBg")
    val fg by animateColorAsState(fgTarget, tween(180), label = "pillFg")
    Box(
        modifier = modifier
            .scale(scale)
            .clip(BwShape.Pill)
            .background(bg)
            .then(if (!filled) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, BwShape.Pill) else Modifier)
            .clickable(interaction, null, enabled = enabled) { onClick() }
            .padding(horizontal = 22.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Hairline-bordered card — Apple's utility-card grammar. */
@Composable
fun BwCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = BwShape.Card,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(Modifier.padding(20.dp)) { content() }
    }
}

/** Slim top bar: title at left, actions at right, hairline below. */
@Composable
fun BwTopBar(
    title: String,
    actions: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) { actions?.invoke() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
    }
}

/** Capability status — ink fill fades in/out inside a constant ring. */
@Composable
fun StatusDot(on: Boolean, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val fill by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = tween(280),
        label = "dotFill",
    )
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(size)
                .alpha(fill)
                .background(color),
        )
    }
}

@Composable
fun StatusPill(label: String, on: Boolean, detail: String = "") {
    Row(
        Modifier
            .clip(BwShape.Pill)
            .border(1.dp, MaterialTheme.colorScheme.outline, BwShape.Pill)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(on)
        Text(label, style = MaterialTheme.typography.labelMedium)
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** iOS-style switch: track color fades, knob slides with a spring. */
@Composable
fun BwSwitch(checked: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(220),
        label = "track",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 18.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "knob",
    )
    Box(
        modifier
            .width(46.dp)
            .height(28.dp)
            .clip(BwShape.Pill)
            .background(trackColor)
            .clickable(
                remember { MutableInteractionSource() },
                null,
            ) { onToggle(!checked) }
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/** Monospace text for code/terminal fragments. */
@Composable
fun MonoText(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        modifier = modifier,
    )
}

/**
 * TextField colors in the B/W grammar: transparent container, hairline
 * underline, ink focus indicator + cursor. Kills the default grey M3 box.
 */
@Composable
fun bwTextFieldColors(): TextFieldColors {
    val c = MaterialTheme.colorScheme
    return TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        focusedIndicatorColor = c.primary,
        unfocusedIndicatorColor = c.outline,
        disabledIndicatorColor = c.outline,
        cursorColor = c.primary,
        focusedLabelColor = c.onSurfaceVariant,
        unfocusedLabelColor = c.onSurfaceVariant,
        focusedTextColor = c.onSurface,
        unfocusedTextColor = c.onSurface,
    )
}
