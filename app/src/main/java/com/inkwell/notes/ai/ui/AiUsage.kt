package com.inkwell.notes.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.ai.engine.CostFormat
import com.inkwell.notes.ai.engine.MonthWindow
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.storage.db.AiUsageEntity
import com.inkwell.notes.core.storage.db.UsageTotalRow
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

/** Totals of a set of usage rows. */
internal data class UsageSummary(val requests: Int, val inputTokens: Long, val outputTokens: Long, val costUsd: Double, val unpricedRequests: Int)

internal fun summarize(rows: List<UsageTotalRow>) = UsageSummary(
    requests = rows.sumOf { it.requests },
    inputTokens = rows.sumOf { it.inputTokens },
    outputTokens = rows.sumOf { it.outputTokens },
    costUsd = rows.sumOf { it.costUsd ?: 0.0 },
    unpricedRequests = rows.filter { it.costUsd == null }.sumOf { it.requests },
)

private fun providerName(name: String): String = runCatching { AiProviderId.valueOf(name).displayName }.getOrDefault(name)
private fun taskName(name: String): String = runCatching { AiTask.valueOf(name).label }.getOrDefault(name)

@Composable
internal fun AiUsageImpl(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    val graph = AppGraph.get(LocalContext.current)
    val dao = remember { graph.db.aiUsage() }
    val monthStart = remember { MonthWindow.startOfMonth(System.currentTimeMillis(), ZoneId.systemDefault()) }
    val month by remember { dao.observeTotalsSince(monthStart) }.collectAsState(initial = emptyList())
    val allTime by remember { dao.observeTotalsSince(0) }.collectAsState(initial = emptyList())
    val recent by remember { dao.observeRecent(40) }.collectAsState(initial = emptyList())
    val settings by graph.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    val c = Inkwell.colors

    AdaptiveScrollColumn(modifier, contentPadding) {
        val m = summarize(month)
        val prefs = settings.ai
        SectionCard("This month", subtitle = "Since the 1st, device time") {
            SummaryRow(m)
            if (prefs.monthlyHardLimitUsd > 0) {
                val frac = (m.costUsd / prefs.monthlyHardLimitUsd).coerceIn(0.0, 1.0).toFloat()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(Radius8),
                        color = if (frac >= 1f) c.danger else if (prefs.monthlyWarningUsd > 0 && m.costUsd >= prefs.monthlyWarningUsd) c.warning else c.accent,
                        trackColor = c.surfaceAlt,
                        strokeCap = StrokeCap.Round,
                        drawStopIndicator = {},
                    )
                    Text(
                        "${CostFormat.usd(m.costUsd)} of your ${CostFormat.usd(prefs.monthlyHardLimitUsd)} hard limit" +
                            if (frac >= 1f) " — requests are paused until next month or until you raise the limit." else "",
                        style = MaterialTheme.typography.bodySmall, color = if (frac >= 1f) c.danger else c.inkMuted,
                    )
                }
            } else if (prefs.monthlyWarningUsd > 0) {
                Text(
                    if (m.costUsd >= prefs.monthlyWarningUsd) "Above your ${CostFormat.usd(prefs.monthlyWarningUsd)} monthly warning."
                    else "Warning at ${CostFormat.usd(prefs.monthlyWarningUsd)}. No hard limit set.",
                    style = MaterialTheme.typography.bodySmall, color = if (m.costUsd >= prefs.monthlyWarningUsd) c.warning else c.inkMuted,
                )
            }
            if (month.isNotEmpty()) ByModel(month)
        }
        SectionCard("All time") {
            SummaryRow(summarize(allTime))
            if (allTime.isNotEmpty()) ByModel(allTime)
        }
        SectionCard("Recent requests") {
            if (recent.isEmpty()) Text("No AI requests yet.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            else RecentList(recent)
        }
        Text(
            "Estimated from public list prices; your provider's bill is authoritative.",
            style = MaterialTheme.typography.bodySmall, color = c.inkMuted,
        )
        InkOutlinedButton("Reset usage", { confirmReset = true }, icon = Icons.Outlined.DeleteOutline, color = c.danger, enabled = allTime.isNotEmpty())
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = c.surface,
            shape = Radius16,
            title = { Text("Reset AI usage?", style = MaterialTheme.typography.titleMedium, color = c.ink) },
            text = { Text("This clears the local usage history and this month's estimated spend, which also resets the monthly limit.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) },
            confirmButton = { InkTextButton("Reset", { confirmReset = false; scope.launch { dao.clear() } }, color = c.danger) },
            dismissButton = { InkTextButton("Cancel", { confirmReset = false }, color = c.inkMuted) },
        )
    }
}

@Composable
private fun SummaryRow(s: UsageSummary) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat("Requests", CostFormat.tokens(s.requests), Modifier.weight(1f))
        Stat("Input tokens", CostFormat.tokens(s.inputTokens), Modifier.weight(1f))
        Stat("Output tokens", CostFormat.tokens(s.outputTokens), Modifier.weight(1f))
        Stat("Est. cost", CostFormat.usd(s.costUsd) + if (s.unpricedRequests > 0) "+" else "", Modifier.weight(1f))
    }
    if (s.unpricedRequests > 0) {
        Text(
            "${s.unpricedRequests} request(s) used models without a known price and are not included. Set a price in AI settings to include them.",
            style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.inkMuted,
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val c = Inkwell.colors
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
    }
}

@Composable
private fun ByModel(rows: List<UsageTotalRow>) {
    val c = Inkwell.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HorizontalDivider(color = c.outline)
        for ((provider, list) in rows.groupBy { it.provider }.toList().sortedByDescending { (_, l) -> l.sumOf { it.costUsd ?: 0.0 } }) {
            val sum = summarize(list)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(providerName(provider), style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
                Text("${CostFormat.tokens(sum.requests)} req · ${CostFormat.usd(sum.costUsd)}", style = MaterialTheme.typography.labelMedium, color = c.ink)
            }
            for (r in list.sortedByDescending { it.costUsd ?: 0.0 }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(12.dp))
                    Text(r.model, style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${CostFormat.tokens(r.requests)} · ${CostFormat.tokens(r.inputTokens)} in · ${CostFormat.tokens(r.outputTokens)} out · ${CostFormat.usd(r.costUsd)}",
                        style = MaterialTheme.typography.bodySmall, color = c.inkMuted, maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentList(rows: List<AiUsageEntity>) {
    val c = Inkwell.colors
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Column {
        rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = c.outline)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${taskName(r.task)} · ${r.model}", style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${fmt.format(Date(r.timestamp))} · ${providerName(r.provider)} · ${CostFormat.tokens(r.inputTokens)} in · ${CostFormat.tokens(r.outputTokens)} out",
                        style = MaterialTheme.typography.bodySmall, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(CostFormat.usd(r.estimatedCostUsd), style = MaterialTheme.typography.labelMedium, color = c.ink, modifier = Modifier.widthIn(min = 64.dp).padding(start = 8.dp))
            }
        }
    }
}
