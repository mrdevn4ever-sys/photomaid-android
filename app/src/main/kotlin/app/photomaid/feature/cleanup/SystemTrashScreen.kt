package app.photomaid.feature.cleanup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.core.cleanup.OperationOutcome
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.MediaKind
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.EmptyState
import app.photomaid.design.GlassButton
import app.photomaid.design.PM
import app.photomaid.design.ProminentButton
import app.photomaid.design.ScreenScaffold
import app.photomaid.feature.common.ConfirmationSheet
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.media.MediaWriteResult
import app.photomaid.media.TrashedAsset
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** Tage bis Android ein Medium selbst endgültig löscht (aufgerundet, nie negativ); `null` = unbekannt. */
internal fun daysLeft(expires: Instant?, now: Instant = Instant.now()): Int? =
    expires?.let { ((Duration.between(now, it).toHours() + 23) / 24).toInt().coerceAtLeast(0) }

/**
 * Android-Papierkorb (G4): alles, was Android dort aufbewahrt – auch aus anderen Apps –, mit Restzeit bis zum
 * automatischen Löschen durch Android. Wiederherstellen (eine Systembestätigung) oder endgültig löschen
 * (`createDeleteRequest`, Systembestätigung). Nichts geschieht ohne ausdrückliche Auswahl.
 */
@Composable
fun SystemTrashScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    val token by app.allPhotos.changeToken.collectAsState()
    var items by remember { mutableStateOf<List<TrashedAsset>?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(token) {
        val list = withContext(Dispatchers.IO) { runCatching { app.allPhotos.trashed() }.getOrDefault(emptyList()) }
            // Was am frühesten verschwindet, steht oben.
            .sortedWith(compareBy<TrashedAsset> { it.expires == null }.thenBy { it.expires })
        items = list
        selection = selection.filter { id -> list.any { it.asset.id == id } }.toSet()
    }
    val list = items.orEmpty()
    val chosen = list.filter { it.asset.id in selection }

    fun restore() = scope.launch {
        busy = true
        val ids = chosen.map { it.asset.id }
        val r = app.allPhotos.restoreManyFromTrash(ids)
        val ok = r.filterValues { it == MediaWriteResult.Done }.keys
        // Von GaleriQ entfernte Medien: zurückgeholt = behalten (sonst würden sie nie wieder angeboten).
        for (id in ok) {
            val ref = ItemReference.photo(id)
            if (app.ledger.record(ref).disposition == Disposition.REMOVED) app.decide(ref, DecisionAction.Keep)
        }
        selection = selection - ok
        busy = false
        message = when {
            r.values.all { it == MediaWriteResult.Cancelled } -> null
            ok.size == ids.size -> app.context.resources.getQuantityString(R.plurals.systrash_restored, ok.size, ok.size)
            else -> app.string(R.string.systrash_partial, ok.size, ids.size)
        }
    }
    fun delete() = scope.launch {
        busy = true
        val ids = chosen.map { it.asset.id }
        val r = app.allPhotos.deletePermanently(ids)
        busy = false
        message = when (r) {
            OperationOutcome.Succeeded -> app.context.resources.getQuantityString(R.plurals.result_media_deleted, ids.size, ids.size)
            is OperationOutcome.Partial -> app.string(R.string.systrash_partial, r.removed.size, ids.size)
            OperationOutcome.Cancelled -> null
            else -> app.string(R.string.result_nothing)
        }
        if (r == OperationOutcome.Succeeded) selection = emptySet()
    }

    ScreenScaffold(stringResource(R.string.systrash_title), onBack = nav::back, modifier = Modifier.testTag("systrash"),
        actions = {
            if (list.isNotEmpty()) TextButton({ selection = if (selection.size == list.size) emptySet() else list.map { it.asset.id }.toSet() },
                Modifier.testTag("systrash.all")) {
                Text(stringResource(if (selection.size == list.size) R.string.grid_select_none else R.string.grid_select_all))
            }
        },
        bottomBar = {
            if (chosen.isNotEmpty()) Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = AppSpacing.pageMargin, vertical = AppSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
                ProminentButton(pluralStringResource(R.plurals.systrash_restore, chosen.size, chosen.size), Modifier.testTag("systrash.restore"),
                    enabled = !busy, fillWidth = true, large = true) { restore() }
                GlassButton(pluralStringResource(R.plurals.basket_confirm_delete_action, chosen.size, chosen.size), Modifier.testTag("systrash.delete"),
                    enabled = !busy, tint = c.cleanup, fillWidth = true, large = true) { confirmDelete = true }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            }
        }) { mod ->
        when {
            items == null -> Box(mod, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(mod.padding(AppSpacing.pageMargin).testTag("systrash.empty"), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Rounded.DeleteOutline, stringResource(R.string.systrash_empty_title), stringResource(R.string.systrash_empty_text))
            }
            else -> LazyVerticalGrid(GridCells.Fixed(3), mod.testTag("systrash.grid"), contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.systrash_intro), Modifier.padding(horizontal = AppSpacing.pageMargin - 2.dp, vertical = AppSpacing.xs),
                        style = AppTypography.footnote, color = c.textSecondary)
                }
                items(list, key = { it.asset.id }) { t -> TrashTile(t, t.asset.id in selection) { selection = if (t.asset.id in selection) selection - t.asset.id else selection + t.asset.id } }
            }
        }
    }
    if (confirmDelete) ConfirmationSheet(stringResource(R.string.basket_confirm_delete_title), stringResource(R.string.systrash_delete_text),
        pluralStringResource(R.plurals.basket_confirm_delete_action, chosen.size, chosen.size), onConfirm = { delete() }, onDismiss = { confirmDelete = false })
    message?.let { m -> NoticeDialog(m) { message = null } }
}

@Composable
private fun TrashTile(t: TrashedAsset, selected: Boolean, onToggle: () -> Unit) {
    val c = PM.colors
    val a = t.asset
    val px = with(LocalDensity.current) { 140.dp.roundToPx() }
    val days = daysLeft(t.expires)
    val left = days?.let { pluralStringResource(R.plurals.systrash_days_left, it, it) }
    val kind = stringResource(if (a.kind == MediaKind.VIDEO) R.string.video else R.string.photo)
    Box(Modifier.aspectRatio(1f).background(c.backgroundTint).clickable(role = Role.Checkbox, onClick = onToggle)
        .semantics(mergeDescendants = true) { this.selected = selected; contentDescription = listOfNotNull(kind, left).joinToString(", ") }.testTag("systrash.tile")) {
        AsyncImage(ImageRequest.Builder(LocalContext.current).data(a.uri).size(px, px).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (left != null) Text(left, Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(6.dp))
            .background(if ((days ?: 99) <= 3) c.cleanup else Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp, vertical = 2.dp),
            style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        if (a.kind == MediaKind.VIDEO) Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp))
        if (selected) Box(Modifier.fillMaxSize().border(3.dp, c.accent))
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(if (selected) c.accentFill else Color.Black.copy(alpha = 0.25f))
            .border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
