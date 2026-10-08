package app.photomaid.feature.archive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.archive.BackupContents
import app.photomaid.archive.BackupService
import app.photomaid.archive.RestoreResult
import app.photomaid.core.archive.BackupCheck
import app.photomaid.core.archive.PhotoMaidBackup
import app.photomaid.core.archive.RestorePlan
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DateText
import app.photomaid.design.ListSection
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.ProminentButton
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.design.SizeText
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.PageEndSpacer
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/**
 * Sichern und Wiederherstellen der PhotoMaid-Daten (Paket 5). Eine kleine Datei ohne Fotos, die der Nutzer selbst ablegt;
 * Wiederherstellen zeigt erst „Das passiert“ und überschreibt nie, was hier schon entschieden oder eingetragen ist.
 */
@Composable
fun BackupScreen(nav: Nav, onClose: () -> Unit = nav::back) {
    val app = LocalApp.current
    val c = PM.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<PhotoMaidBackup?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf<PhotoMaidBackup?>(null) }
    var plan by remember { mutableStateOf<RestorePlan?>(null) }
    var applying by remember { mutableStateOf<Float?>(null) }
    var restored by remember { mutableStateOf<RestoreResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { current = BackupService.create(app) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val b = current ?: BackupService.create(app)
            message = app.string(if (BackupService.write(ctx, uri, b)) R.string.backup_saved else R.string.backup_save_failed)
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            restored = null; plan = null; loaded = null; error = null
            when (val r = BackupService.read(ctx, uri)) {
                is BackupCheck.Ok -> { loaded = r.backup; plan = BackupService.plan(app, r.backup) }
                is BackupCheck.TooNew -> error = app.string(R.string.backup_too_new)
                BackupCheck.NotABackup -> error = app.string(R.string.backup_not_backup)
                null -> error = app.string(R.string.backup_unreadable)
            }
        }
    }

    ScreenScaffold(stringResource(R.string.backup_title), onBack = { if (applying == null) onClose() }, modifier = Modifier.testTag("backup")) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            ListSection(header = stringResource(R.string.backup_save_header)) {
                Note(R.string.backup_save_text)
                current?.let { b -> Text(stringResource(R.string.backup_contains, contentsText(BackupService.contents(b))), style = AppTypography.footnote,
                    color = c.textPrimary, modifier = Modifier.padding(bottom = AppSpacing.xs).testTag("backup.contains")) }
                Note(R.string.backup_privacy)
                RowDivider()
                PlainButton(stringResource(R.string.backup_save_button), Modifier.testTag("backup.save"), enabled = current != null) {
                    save.launch(app.string(R.string.backup_file_name, LocalDate.now().toString()))
                }
                message?.let { Text(it, style = AppTypography.footnote, color = c.textSecondary, modifier = Modifier.padding(bottom = AppSpacing.xs).testTag("backup.message")) }
            }
            ListSection(header = stringResource(R.string.backup_restore_header)) {
                Note(R.string.backup_restore_text)
                RowDivider()
                PlainButton(stringResource(R.string.backup_open_button), Modifier.testTag("backup.open"), enabled = applying == null) {
                    open.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
                error?.let { Text(it, style = AppTypography.footnote, color = c.cleanup, modifier = Modifier.padding(bottom = AppSpacing.xs).testTag("backup.error")) }
            }
            val b = loaded; val p = plan
            if (b != null && p != null) RestorePreview(app, b, p, applying, restored) {
                scope.launch {
                    applying = 0f
                    restored = runCatching { BackupService.apply(app, b, p) { applying = it } }.getOrNull()
                    if (restored == null) error = app.string(R.string.error_change_not_saved)
                    applying = null
                    current = BackupService.create(app)
                }
            }
            PageEndSpacer()
        }
    }
}

/** „Das passiert“ vor dem Übernehmen, danach das Ergebnis. Auch für Tests direkt nutzbar. */
@Composable
internal fun RestorePreview(app: AppModel, b: PhotoMaidBackup, p: RestorePlan, applying: Float?, restored: RestoreResult?, onApply: () -> Unit) {
    val c = PM.colors
    val res = LocalResources.current
    var showMissing by remember { mutableStateOf(false) }
    Column(Modifier.testTag("backup.preview"), verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
        Text(stringResource(R.string.backup_from, DateText.longWithTime(Instant.ofEpochMilli(b.createdAt))), style = AppTypography.subheadline, color = c.textSecondary)
        if (restored == null) {
            Text(stringResource(R.string.wf_preview_title), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            val steps = buildList {
                if (p.decisions.isNotEmpty()) add(res.getQuantityString(R.plurals.backup_step_decisions, p.decisions.size, p.decisions.size))
                if (p.decisionsKeptLocal > 0) add(res.getQuantityString(R.plurals.backup_step_kept_local, p.decisionsKeptLocal, p.decisionsKeptLocal))
                if (p.newAlbums > 0) add(res.getQuantityString(R.plurals.backup_step_albums_new, p.newAlbums, p.newAlbums))
                if (p.extendedAlbums > 0) add(res.getQuantityString(R.plurals.backup_step_albums_extend, p.extendedAlbums, p.extendedAlbums))
                if (p.tags.isNotEmpty()) add(res.getQuantityString(R.plurals.backup_step_tags, p.tags.size, p.tags.size))
                if (p.notes.isNotEmpty()) add(res.getQuantityString(R.plurals.backup_step_notes, p.notes.size, p.notes.size))
                if (p.basket.isNotEmpty()) add(res.getQuantityString(R.plurals.backup_step_basket, p.basket.size, p.basket.size))
                if (p.hidden.isNotEmpty()) add(res.getQuantityString(R.plurals.backup_step_hidden, p.hidden.size, p.hidden.size))
                add(app.string(R.string.backup_step_settings))
            }
            ListSection(Modifier.testTag("backup.steps")) {
                steps.forEachIndexed { i, t ->
                    if (i > 0) RowDivider(AppSpacing.m)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = AppSpacing.m, vertical = AppSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
                        Text("${i + 1}", style = AppTypography.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                        Text(t, style = AppTypography.body, color = c.textPrimary)
                    }
                }
            }
        }
        if (p.missing.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.backup_missing, p.missing.size, p.missing.size), style = AppTypography.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = c.textPrimary, modifier = Modifier.testTag("backup.missing"))
            Note(R.string.backup_missing_note)
            PlainButton(stringResource(if (showMissing) R.string.backup_missing_hide else R.string.backup_missing_show), Modifier.testTag("backup.missing.toggle")) {
                showMissing = !showMissing
            }
            if (showMissing) ListSection(Modifier.testTag("backup.missing.list")) {
                p.missing.take(500).forEachIndexed { i, m ->
                    if (i > 0) RowDivider(AppSpacing.m)
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = AppSpacing.m, vertical = AppSpacing.xs)) {
                        Text(m.name ?: stringResource(R.string.medium), style = AppTypography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text(listOfNotNull(m.taken?.let { DateText.abbreviated(Instant.ofEpochMilli(it)) }, m.size?.let { SizeText.bytes(it) }).joinToString(" · "),
                            style = AppTypography.footnote, color = c.textSecondary)
                    }
                }
            }
        }
        val r = restored
        when {
            r != null -> {
                Text(stringResource(R.string.backup_done), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.semantics { heading() })
                val parts = buildList {
                    if (r.decisions > 0) add(res.getQuantityString(R.plurals.backup_part_decisions, r.decisions, r.decisions))
                    val albums = r.albumsCreated + r.albumsExtended
                    if (albums > 0) add(res.getQuantityString(R.plurals.backup_part_albums, albums, albums))
                    if (r.tagged > 0) add(res.getQuantityString(R.plurals.backup_part_tagged, r.tagged, r.tagged))
                    if (r.notes > 0) add(res.getQuantityString(R.plurals.backup_part_notes, r.notes, r.notes))
                    if (r.basket > 0) add(res.getQuantityString(R.plurals.backup_part_basket, r.basket, r.basket))
                    if (r.hidden > 0) add(res.getQuantityString(R.plurals.backup_part_hidden, r.hidden, r.hidden))
                    if (r.workflows > 0) add(res.getQuantityString(R.plurals.backup_part_workflows, r.workflows, r.workflows))
                    if (r.presets > 0) add(res.getQuantityString(R.plurals.backup_part_presets, r.presets, r.presets))
                    if (r.collections > 0) add(res.getQuantityString(R.plurals.backup_part_collections, r.collections, r.collections))
                }
                Text(if (parts.isEmpty()) stringResource(R.string.backup_nothing) else parts.joinToString(" · "), style = AppTypography.body, color = c.textPrimary,
                    modifier = Modifier.testTag("backup.result"))
                if (r.failed > 0) Text(pluralStringResource(R.plurals.backup_failed_some, r.failed, r.failed), style = AppTypography.footnote, color = c.cleanup)
            }
            p.isEmpty -> Text(stringResource(R.string.backup_nothing), style = AppTypography.body, color = c.textSecondary, modifier = Modifier.testTag("backup.nothing"))
            else -> {
                if (applying != null) {
                    if (applying > 0f) LinearProgressIndicator(progress = { applying }, modifier = Modifier.fillMaxWidth()) else CircularProgressIndicator(Modifier.size(24.dp))
                }
                ProminentButton(stringResource(R.string.backup_apply), Modifier.testTag("backup.apply"), fillWidth = true, enabled = applying == null) { onApply() }
            }
        }
    }
}

@Composable
private fun contentsText(k: BackupContents): String {
    val res = LocalResources.current
    return buildList {
        add(res.getQuantityString(R.plurals.backup_part_decisions, k.decisions, k.decisions))
        if (k.albums > 0) add(res.getQuantityString(R.plurals.backup_part_albums, k.albums, k.albums))
        if (k.tagged > 0) add(res.getQuantityString(R.plurals.backup_part_tagged, k.tagged, k.tagged))
        if (k.notes > 0) add(res.getQuantityString(R.plurals.backup_part_notes, k.notes, k.notes))
        if (k.basket > 0) add(res.getQuantityString(R.plurals.backup_part_basket, k.basket, k.basket))
        if (k.hidden > 0) add(res.getQuantityString(R.plurals.backup_part_hidden, k.hidden, k.hidden))
        if (k.workflows > 0) add(res.getQuantityString(R.plurals.backup_part_workflows, k.workflows, k.workflows))
        if (k.presets > 0) add(res.getQuantityString(R.plurals.backup_part_presets, k.presets, k.presets))
        if (k.collections > 0) add(res.getQuantityString(R.plurals.backup_part_collections, k.collections, k.collections))
    }.joinToString(" · ")
}

@Composable private fun Note(t: Int) = Text(stringResource(t), style = AppTypography.footnote, color = PM.colors.textSecondary,
    modifier = Modifier.padding(vertical = AppSpacing.xs))
