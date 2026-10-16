package com.inkwell.notes.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Folder
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.library.CardDecor
import com.inkwell.notes.library.FolderStyle
import com.inkwell.notes.library.LibraryText
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/** Values every card needs; refreshed once a minute so relative times stay current. */
@Immutable
internal data class CardEnv(
    val nowMs: Long,
    val autoNaming: Boolean,
    val delayHours: Int,
    val defaultPaper: PaperSpec,
    val selectionMode: Boolean,
)

/** Grid card: paper thumbnail, two-line title, meta line, auto-naming chip. */
@Composable
internal fun DocumentGridCard(
    doc: DocumentMeta,
    decor: CardDecor?,
    env: CardEnv,
    selected: Boolean,
    moreButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    Column(modifier.padding(8.dp)) {
        DocThumbnail(
            doc = doc,
            decor = decor,
            defaultPaper = env.defaultPaper,
            modifier = Modifier.fillMaxWidth(),
            selected = selected,
        ) { ThumbnailOverlays(doc, decor, env.selectionMode, selected) }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp, start = 2.dp)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    LibraryText.metaLine(doc, env.nowMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            moreButton()
        }
        if (LibraryText.showsAutoNameChip(doc, env.autoNaming)) {
            Spacer(Modifier.height(8.dp))
            AutoNameChip(env.delayHours)
        }
    }
}

/** List row: small thumbnail, one-line title, meta line and state icons. */
@Composable
internal fun DocumentListRow(
    doc: DocumentMeta,
    decor: CardDecor?,
    env: CardEnv,
    selected: Boolean,
    moreButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (env.selectionMode) {
            SelectionMark(selected)
            Spacer(Modifier.width(12.dp))
        }
        DocThumbnail(doc, decor, env.defaultPaper, Modifier.width(44.dp), selected = selected, corner = 6.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    LibraryText.metaLine(doc, env.nowMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (LibraryText.showsAutoNameChip(doc, env.autoNaming)) {
                    Spacer(Modifier.width(8.dp))
                    AutoNameChip(env.delayHours)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        StateIcons(doc, decor)
        moreButton()
    }
}

/** Folder tile for the grid (and, full width, for the list). */
@Composable
internal fun FolderTile(
    folder: Folder,
    itemCount: Int,
    selected: Boolean,
    dropHovered: Boolean,
    selectionMode: Boolean,
    moreButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    val tint = FolderStyle.color(folder.color, c.inkMuted)
    val highlighted = selected || dropHovered
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(R12)
            .background(if (highlighted) c.accentContainer else c.surface)
            .border(if (dropHovered) 2.dp else 1.dp, if (highlighted) c.accent else c.outline, R12)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            SelectionMark(selected)
            Spacer(Modifier.width(10.dp))
        }
        Box(
            Modifier.size(36.dp).clip(R8).background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(FolderStyle.icon(folder.icon), null, Modifier.size(Dimens.icon), tint = tint) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(folder.name, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (dropHovered) stringResource(R.string.lib_drop_here) else LibraryText.itemCount(itemCount),
                style = MaterialTheme.typography.bodySmall,
                color = if (dropHovered) c.accent else c.inkMuted,
                maxLines = 1,
            )
        }
        moreButton()
    }
}

/** 44dp overflow button. */
@Composable
internal fun MoreButton(label: String, onClick: () -> Unit) {
    LibIconButton(Icons.Outlined.MoreVert, label, onClick)
}

@Composable
private fun BoxScope.ThumbnailOverlays(doc: DocumentMeta, decor: CardDecor?, selectionMode: Boolean, selected: Boolean) {
    if (selectionMode) {
        SelectionMark(selected, Modifier.align(Alignment.TopStart).padding(8.dp))
    }
    Row(
        Modifier.align(Alignment.TopEnd).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (doc.pinned) OverlayIcon(Icons.Outlined.PushPin, stringResource(R.string.lib_badge_pinned), Inkwell.colors.inkMuted)
        if (doc.favorite) OverlayIcon(Icons.Outlined.Star, stringResource(R.string.lib_badge_favorite), Inkwell.colors.warning)
    }
    Row(
        Modifier.align(Alignment.BottomStart).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KindBadge(doc.kind)
        if (decor?.hasRecordings == true) {
            val label = stringResource(R.string.lib_badge_recordings)
            SmallBadge(Modifier.semantics { contentDescription = label }) {
                Icon(Icons.Outlined.Mic, null, Modifier.size(14.dp), tint = Inkwell.colors.inkMuted)
            }
        }
    }
}

@Composable
private fun KindBadge(kind: DocKind) {
    val c = Inkwell.colors
    when (kind) {
        DocKind.PDF -> SmallBadge { Text(stringResource(R.string.lib_badge_pdf), style = MaterialTheme.typography.labelSmall, color = c.inkMuted) }
        DocKind.WHITEBOARD -> {
            val label = stringResource(R.string.lib_badge_whiteboard)
            SmallBadge(Modifier.semantics { contentDescription = label }) { Icon(Icons.Outlined.Gesture, null, Modifier.size(14.dp), tint = c.inkMuted) }
        }
        DocKind.TYPED -> {
            val label = stringResource(R.string.lib_badge_typed)
            SmallBadge(Modifier.semantics { contentDescription = label }) { Icon(Icons.AutoMirrored.Outlined.Article, null, Modifier.size(14.dp), tint = c.inkMuted) }
        }
        DocKind.NOTEBOOK -> Unit
    }
}

@Composable
private fun StateIcons(doc: DocumentMeta, decor: CardDecor?) {
    val c = Inkwell.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (doc.kind == DocKind.PDF) Text(stringResource(R.string.lib_badge_pdf), style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
        if (decor?.hasRecordings == true) Icon(Icons.Outlined.Mic, stringResource(R.string.lib_badge_recordings), Modifier.size(16.dp), tint = c.inkMuted)
        if (doc.pinned) Icon(Icons.Outlined.PushPin, stringResource(R.string.lib_badge_pinned), Modifier.size(16.dp), tint = c.inkMuted)
        if (doc.favorite) Icon(Icons.Outlined.Star, stringResource(R.string.lib_badge_favorite), Modifier.size(16.dp), tint = c.warning)
    }
}

@Composable
private fun OverlayIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color) {
    val c = Inkwell.colors
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(c.surface).border(1.dp, c.outline, CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(14.dp), tint = tint) }
}

/** Round selection check used in selection mode. */
@Composable
internal fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    val label = stringResource(if (selected) R.string.lib_selected else R.string.lib_not_selected)
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) c.accent else c.surface)
            .border(1.5.dp, if (selected) c.accent else c.inkMuted, CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = c.onAccent)
    }
}

@Composable
internal fun AutoNameChip(delayHours: Int) {
    val c = Inkwell.colors
    Row(
        Modifier
            .heightIn(min = 24.dp)
            .clip(R8)
            .border(1.dp, c.outline, R8)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Schedule, null, Modifier.size(13.dp), tint = c.inkMuted)
        Spacer(Modifier.width(5.dp))
        Text(
            stringResource(R.string.lib_auto_name_chip, delayHours),
            style = MaterialTheme.typography.labelSmall,
            color = c.inkMuted,
            maxLines = 1,
        )
    }
}
