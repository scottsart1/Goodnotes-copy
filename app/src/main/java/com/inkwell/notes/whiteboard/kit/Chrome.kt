package com.inkwell.notes.whiteboard.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/**
 * Small shared building blocks for the whiteboard and typed-document screens: 44dp icon
 * buttons with 20dp outlined icons, outlined pills, swatches, the slim top bar and the
 * message host. Colours always come from [Inkwell.colors].
 */

@Composable
fun KitIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color? = null,
    size: Dp = Dimens.minTouch,
) {
    val c = Inkwell.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(if (selected) c.accentContainer else Color.Transparent)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = null,
                indication = ripple(bounded = true),
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(Dimens.icon).alpha(if (enabled) 1f else 0.35f),
            tint = tint ?: if (selected) c.accent else c.ink,
        )
    }
}

/** A floating outlined surface (tool pill, minimap card, zoom controls). */
@Composable
fun PillSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable () -> Unit,
) {
    val c = Inkwell.colors
    Surface(
        modifier = modifier,
        shape = shape,
        color = c.surface,
        contentColor = c.ink,
        border = BorderStroke(1.dp, c.outline),
        shadowElevation = 1.dp,
        content = content,
    )
}

@Composable
fun PillDivider(vertical: Boolean = true) {
    val c = Inkwell.colors
    if (vertical) {
        Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(c.outline))
    } else {
        Box(Modifier.padding(vertical = 4.dp).height(1.dp).fillMaxWidth().background(c.outline))
    }
}

/** A round colour chip inside a 44dp touch target; light colours get an outline. */
@Composable
fun ColorSwatch(argb: Int, selected: Boolean, contentDescription: String, onClick: () -> Unit, diameter: Dp = 26.dp) {
    val c = Inkwell.colors
    val color = Color(argb)
    val transparent = (argb ushr 24) == 0
    Box(
        Modifier
            .size(Dimens.minTouch)
            .clip(CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(diameter + 8.dp).border(2.dp, c.accent, CircleShape))
        }
        Box(
            Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(if (transparent) c.surface else color)
                .border(1.dp, if (transparent || color.luminance() > 0.8f || (c.isDark && color.luminance() < 0.05f)) c.outline else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (transparent) Box(Modifier.size(diameter * 0.7f).height(1.5.dp).background(c.danger))
            if (selected && !transparent) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (color.luminance() > 0.5f) Color(0xFF1A1916) else Color.White,
                )
            }
        }
    }
}

/** Small segmented control (e.g. Ballpoint / Fountain / Brush). */
@Composable
fun Segmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .clip(shape)
            .border(1.dp, c.outline, shape)
            .background(c.surfaceAlt),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sel) c.surface else Color.Transparent)
                    .then(if (sel) Modifier.border(1.dp, c.outline, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .semantics { selected = sel },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (sel) c.ink else c.inkMuted, maxLines = 1)
            }
        }
    }
}

/** The slim 48dp editor top bar: back, title (tap to rename), optional subtitle and actions. */
@Composable
fun SlimTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Inkwell.colors
    Column(modifier.fillMaxWidth().background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.topBarHeight).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KitIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), onBack)
            Spacer(Modifier.width(4.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = 560.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (onTitleClick != null) Modifier.clickable(onClickLabel = stringResource(R.string.cd_rename), onClick = onTitleClick) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
        HorizontalDivider(thickness = 1.dp, color = c.outline)
    }
}

/** Snackbar host styled as the app's dark pill messages. */
@Composable
fun KitMessageHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = RoundedCornerShape(12.dp),
            containerColor = c.ink,
            contentColor = c.surface,
            actionColor = if (c.isDark) c.accentContainer else c.accentContainer,
            actionContentColor = c.accentContainer,
            dismissActionContentColor = c.surface,
        )
    }
}

/** Dark pill toast, e.g. "Side button held · Eraser". */
@Composable
fun DarkToast(text: String, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(c.ink.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = c.surface)
    }
}

/** Centered empty/error state with an optional action. */
@Composable
fun CenteredState(title: String, body: String?, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    val c = Inkwell.colors
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink)
            if (body != null) {
                Spacer(Modifier.height(8.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            }
            if (action != null) {
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.accent)
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(action, style = MaterialTheme.typography.labelLarge, color = c.onAccent)
                }
            }
        }
    }
}

/** Remembered no-op interaction-free click modifier for scrims. */
@Composable
fun Modifier.consumeClicks(onClick: () -> Unit = {}): Modifier =
    this.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick)
