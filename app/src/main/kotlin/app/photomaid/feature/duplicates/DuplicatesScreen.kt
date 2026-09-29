package app.photomaid.feature.duplicates

import app.photomaid.feature.pro.TemporaryProTag
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.design.EmptyState
import app.photomaid.core.capabilities.WorkUnitGrant
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.duplicates.ByteSourceProvider
import app.photomaid.core.duplicates.DuplicateCandidate
import app.photomaid.core.duplicates.DuplicateFinder
import app.photomaid.core.duplicates.DuplicateGroup
import app.photomaid.core.model.ItemProvider
import app.photomaid.core.model.ItemReference
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.Badge
import app.photomaid.design.GlassButton
import app.photomaid.design.ListSection
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.ProminentButton
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SizeText
import app.photomaid.design.SurfaceCard
import app.photomaid.feature.common.ThumbnailView
import app.photomaid.media.MediaCategory
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.PageEndSpacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Scan exakter Duplikate (iOS `DuplicateScanModel`): Medien (MediaStore) und verbundene Dateien über
 * [DuplicateFinder] (Größe → Teilhash → Streaming-SHA-256). Hashes bleiben nur im Speicher (wie iOS).
 */
class DuplicateScanState {
    var groups by mutableStateOf<List<DuplicateGroup>>(emptyList())
    var notChecked by mutableStateOf(0)
    var notComparable by mutableStateOf(0)
    var progress by mutableFloatStateOf(0f)
    var isScanning by mutableStateOf(false)
    var finishedAt by mutableStateOf<Instant?>(null)
    val selection = mutableStateMapOf<String, Set<ItemReference>>()
    var names by mutableStateOf<Map<ItemReference, String>>(emptyMap())
    var job: Job? = null

    suspend fun run(app: AppModel) {
        // Die begonnene Arbeitseinheit läuft zu Ende, auch wenn die Vorschau währenddessen abläuft.
        if (isScanning || WorkUnitGrant.begin({ it.exactDuplicates }, app.capabilities, Instant.now()) == null) return
        isScanning = true
        progress = 0f
        try {
            val ctx = currentCoroutineContext()
            val result = withContext(Dispatchers.IO) {
                val files = app.sources.sources.value.flatMap { s -> runCatching { app.sources.scan(s.id) }.getOrDefault(emptyList()) }
                val media = app.photos.assets(MediaCategory.PHOTOS) + app.photos.assets(MediaCategory.VIDEOS)
                names = files.associate { it.reference to it.name }
                val candidates = media.map { DuplicateCandidate(it.reference, it.size, aliasKey = "media|${it.id}") } +
                    files.map { DuplicateCandidate(it.reference, it.size, aliasKey = it.aliasKey) }
                val provider = ByteSourceProvider { item ->
                    if (item.provider == ItemProvider.PhotoLibrary) app.photos.openStream(item.localIdentifier) else app.sources.openStream(item)
                }
                DuplicateFinder().scan(candidates, provider, isCancelled = { !ctx.isActive }) { p ->
                    progress = if (p.total > 0) p.processed.toFloat() / p.total else 1f
                }
            }
            groups = result.groups
            notChecked = result.notChecked.size
            notComparable = result.notComparable.size
            for (g in groups) if (g.id !in selection) selection[g.id] = g.defaultSelection(g.members[0])
            progress = 1f
            finishedAt = Instant.now()
        } catch (_: java.util.concurrent.CancellationException) {
        } finally {
            isScanning = false
        }
    }

    fun toggle(g: DuplicateGroup, m: ItemReference) {
        val sel = selection[g.id] ?: emptySet()
        val next = if (m in sel) sel - m else sel + m
        if (g.isValidSelection(next)) selection[g.id] = next // mindestens eine Kopie bleibt
    }
}

/** Screen 9 – Exakte Duplikate. Eine Kopie bleibt; andere ausdrücklich in den Papierkorb. Kein Auto-Delete. */
@Composable
fun DuplicatesScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember { DuplicateScanState() }
    // Ablauf des 30-Minuten-Tests: bei jedem Takt neu auflösen (sperrt das Werkzeug beim nächsten Check).
    val entitlementTick by app.entitlements.now.collectAsState()
    entitlementTick
    val caps = app.capabilities
    DisposableEffect(Unit) { onDispose { model.job?.cancel() } }

    ScreenScaffold(stringResource(R.string.duplicates), onBack = nav::back) { mod ->
        LazyColumn(mod.padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            item {
                SurfaceCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        Icon(Icons.Rounded.FilterNone, null, tint = c.indigo, modifier = Modifier.size(32.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.dup_find), style = AppTypography.headline, color = c.textPrimary)
                                if (!caps.exactDuplicates) Badge(stringResource(R.string.pro_badge))
                            }
                            Text(stringResource(R.string.dup_explain), style = AppTypography.footnote, color = c.textSecondary)
                            TemporaryProTag(caps)
                        }
                    }
                    Spacer(Modifier.height(AppSpacing.s))
                    if (caps.exactDuplicates) {
                        if (model.isScanning) {
                            Text(stringResource(R.string.dup_checking), style = AppTypography.subheadline, color = c.textPrimary)
                            LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.xs), color = c.accent)
                            GlassButton(stringResource(R.string.cancel)) { model.job?.cancel() }
                        } else {
                            ProminentButton(stringResource(if (model.finishedAt == null) R.string.dup_start else R.string.dup_again)) {
                                model.job = scope.launch { model.run(app) }
                            }
                        }
                    } else ProminentButton(stringResource(R.string.pro_view), onClick = nav::openPro)
                }
            }
            items(model.groups, key = { it.id }) { g ->
                val sel = model.selection[g.id] ?: emptySet()
                ListSection(header = stringResource(R.string.dup_header, g.members.size, SizeText.bytes(g.totalBytes)),
                    footer = stringResource(R.string.dup_footer, SizeText.bytes(g.selectedBytes(sel)))) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = AppSpacing.xs), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        for (m in g.members) {
                            val selected = m in sel
                            val label = stringResource(if (selected) R.string.dup_selected_trash else R.string.dup_will_keep)
                            Box(Modifier.size(88.dp).clickable { model.toggle(g, m) }.semantics { contentDescription = label }) {
                                if (m.provider == ItemProvider.PhotoLibrary) ThumbnailView(m.localIdentifier, 88.dp)
                                else Column(Modifier.size(88.dp).clip(RoundedCornerShape(10.dp)).background(c.backgroundTint).padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Icon(Icons.Rounded.Description, null, tint = c.textSecondary)
                                    Text(model.names[m] ?: m.localIdentifier.substringAfterLast('/'), style = AppTypography.caption2, color = c.textPrimary,
                                        maxLines = 2, textAlign = TextAlign.Center)
                                }
                                Icon(if (selected) Icons.Rounded.RemoveCircle else Icons.Rounded.CheckCircle, null,
                                    tint = if (selected) c.cleanup else c.keep, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                            }
                        }
                    }
                    PlainButton(pluralStringResource(R.plurals.dup_move, sel.size, sel.size), enabled = g.isValidSelection(sel) && sel.isNotEmpty()) {
                        scope.launch { for (m in sel) app.decide(m, DecisionAction.MarkForCleanup) }
                    }
                }
            }
            if (model.finishedAt != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
                    if (model.groups.isEmpty()) EmptyState(Icons.Rounded.FilterNone, stringResource(R.string.dup_none), stringResource(R.string.dup_none_message))
                    if (model.notComparable > 0) Text(pluralStringResource(R.plurals.dup_not_comparable, model.notComparable, model.notComparable), style = AppTypography.caption, color = c.textSecondary)
                    if (model.notChecked > 0) Text(pluralStringResource(R.plurals.dup_not_local, model.notChecked, model.notChecked), style = AppTypography.caption, color = c.textSecondary)
                }
            }
            item { Text(stringResource(R.string.dup_how), style = AppTypography.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = AppSpacing.xxs)) }
            item { PageEndSpacer() }
        }
    }
    @Suppress("UNUSED_EXPRESSION") ctx
}
