package com.boxagent.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boxagent.app.ui.theme.BwShape

/** Press feedback shared by tappable surfaces: a quick spring scale. */
@Composable
private fun pressScale(interaction: MutableInteractionSource, pressed: Float = 0.94f): Float {
    val isPressed by interaction.collectIsPressedAsState()
    return animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "press",
    ).value
}

/** Round icon button: filled ink for the primary action, quiet otherwise. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 40.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val c = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        when {
            filled && enabled -> c.primary
            filled -> c.surfaceVariant
            else -> Color.Transparent
        },
        tween(180), label = "iconBg",
    )
    val fg by animateColorAsState(
        when {
            !enabled -> c.onSurfaceVariant.copy(alpha = 0.5f)
            filled -> c.onPrimary
            else -> c.onSurface
        },
        tween(180), label = "iconFg",
    )
    Box(
        modifier
            .scale(pressScale(interaction))
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(interaction, null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = fg, modifier = Modifier.size(size * 0.5f))
    }
}

/** Selectable pill chip with an optional leading icon. */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val c = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) c.primary else c.surfaceVariant, tween(180), label = "chipBg")
    val fg by animateColorAsState(if (selected) c.onPrimary else c.onSurface, tween(180), label = "chipFg")
    Row(
        modifier
            .scale(if (onClick != null) pressScale(interaction, 0.96f) else 1f)
            .clip(BwShape.Pill)
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(interaction, null, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** Small capsule tag. [strong] = inverted (ink), for states that matter. */
@Composable
fun Tag(text: String, strong: Boolean = false, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (strong) c.onPrimary else c.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .clip(BwShape.Pill)
            .then(
                if (strong) Modifier.background(c.primary)
                else Modifier.border(1.dp, c.outline, BwShape.Pill),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** iOS-style segmented control: the selected thumb slides between options. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surfaceVariant)
            .padding(3.dp),
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(
            w * selected,
            spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
            label = "thumb",
        )
        Box(
            Modifier
                .offset(x = x)
                .width(w)
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.surface)
                .border(1.dp, c.outline, RoundedCornerShape(8.dp)),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = if (i == selected) MaterialTheme.typography.labelLarge
                        else MaterialTheme.typography.labelMedium,
                        color = if (i == selected) c.onSurface else c.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Pill search field with a magnifier and a clear button. */
@Composable
fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(BwShape.Pill)
            .background(c.surfaceVariant)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface),
                cursorBrush = SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, null, tint = c.onSurfaceVariant,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onChange("") },
            )
        }
    }
}

/** Screen header: large title, optional subtitle, trailing actions. */
@Composable
fun LargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 1)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Rounded square holding a glyph — the visual anchor of list rows/cards. */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    inverted: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(if (inverted) c.primary else c.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint = if (inverted) c.onPrimary else c.onSurface,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** Inset grouped list (iOS Settings): one rounded container, hairlines
 *  between rows. */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(content = content)
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
    }
}

/** Row inside a [ListGroup]. Set [divider] on every row but the last. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    divider: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                IconTile(icon, size = 30.dp)
                Box(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        if (divider) {
            Box(
                Modifier
                    .padding(start = if (icon != null) 58.dp else 16.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline),
            )
        }
    }
}

/** Centered illustration-free empty state. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconTile(icon, size = 56.dp)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        action?.invoke()
    }
}

/**
 * Bottom panel over a scrim — used for short forms (run a skill, import).
 * Slides up with a spring; tapping the scrim dismisses.
 */
@Composable
fun BottomPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { it } +
                fadeIn(tween(120)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(160)),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().imePadding(),
            ) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp)) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(BwShape.Pill)
                            .background(MaterialTheme.colorScheme.outline),
                    )
                    Box(Modifier.height(14.dp))
                    content()
                }
            }
        }
    }
}

/** Labeled input: caption above a soft rounded field (forms, editors). */
@Composable
fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    minLines: Int = 1,
    maxLines: Int = if (minLines > 1) 12 else 1,
    mono: Boolean = false,
    error: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
        }
        val style = (if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            else MaterialTheme.typography.bodyMedium).copy(color = c.onSurface)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = (22 * minLines + 20).dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.surfaceVariant)
                .then(if (error) Modifier.border(1.5.dp, c.primary, RoundedCornerShape(12.dp)) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = style.copy(color = c.onSurfaceVariant))
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = maxLines == 1,
                maxLines = maxLines,
                textStyle = style,
                cursorBrush = SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
