package com.inkwell.notes.whiteboard.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.whiteboard.kit.KitIconButton

/** A large dialog with a header (optional back, title, close) used by the board pickers. */
@Composable
fun PickerShell(
    title: String,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Inkwell.colors
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth(0.94f)
                .heightIn(max = 760.dp)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(16.dp),
            color = c.surface,
            contentColor = c.ink,
            border = BorderStroke(1.dp, c.outline),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) KitIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), onBack)
                    else Spacer(Modifier.width(10.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f).padding(horizontal = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    KitIconButton(Icons.Outlined.Close, stringResource(R.string.cd_close), onClose)
                }
                HorizontalDivider(thickness = 1.dp, color = c.outline)
                content()
            }
        }
    }
}

/** Library documents as a simple list. A null list shows a spinner. */
@Composable
fun DocumentList(
    docs: List<DocumentMeta>?,
    emptyText: String,
    onPick: (DocumentMeta) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val c = Inkwell.colors
    when {
        docs == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp) }
        else -> LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            if (header != null) item { header() }
            if (docs.isEmpty()) {
                item {
                    Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, modifier = Modifier.padding(24.dp))
                }
            }
            items(docs, key = { it.id }) { d ->
                PickerRow(
                    icon = { Icon(if (d.kind == DocKind.PDF) Icons.Outlined.PictureAsPdf else Icons.AutoMirrored.Outlined.MenuBook, null, tint = c.inkMuted, modifier = Modifier.size(Dimens.icon)) },
                    title = d.title,
                    subtitle = if (d.pageCount > 0) stringResource(R.string.wb_pages_count, d.pageCount) else null,
                    onClick = { onPick(d) },
                )
            }
        }
    }
}

@Composable
fun PickerRow(icon: @Composable () -> Unit, title: String, subtitle: String?, onClick: () -> Unit) {
    val c = Inkwell.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceAlt), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }
    }
}

/** A page thumbnail cell: loads its bitmap lazily, shows the page number beneath. */
@Composable
fun PageThumbCell(key: Any, aspect: Float, label: String, load: suspend () -> Bitmap?, onClick: () -> Unit) {
    val c = Inkwell.colors
    // first = finished loading, second = the bitmap (null when it could not be rendered)
    val thumb by produceState<Pair<Boolean, Bitmap?>>(false to null, key) { value = true to runCatching { load() }.getOrNull() }
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.3f, 3f))
                .clip(RoundedCornerShape(6.dp))
                .background(c.surfaceAlt)
                .border(1.dp, c.outline, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val (loaded, b) = thumb
            when {
                b != null && !b.isRecycled -> Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                !loaded -> CircularProgressIndicator(color = c.inkMuted, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.Outlined.BrokenImage, null, tint = c.inkMuted, modifier = Modifier.size(Dimens.icon))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
    }
}
