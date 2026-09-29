package app.photomaid.feature.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.photomaid.R
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.ListSection
import app.photomaid.design.PM
import app.photomaid.design.PlainButton
import app.photomaid.design.RowDivider
import app.photomaid.design.ScreenScaffold
import app.photomaid.feature.common.NoticeDialog
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.TabBarClearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Verbundene Quellen verwalten. Entfernen löscht keine Dateien. */
@Composable
fun SourcesScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val scope = rememberCoroutineScope()
    val sources by app.sources.sources.collectAsState()
    val problems by app.sources.problems.collectAsState()
    var errorText by remember { mutableStateOf<String?>(null) }
    val pick = rememberFolderPicker(asDownloads = false) { errorText = it }
    ScreenScaffold(stringResource(R.string.connected_sources), onBack = nav::back) { mod ->
        Column(mod.verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.pageMargin), verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            ListSection(footer = stringResource(R.string.sources_footer)) {
                sources.forEachIndexed { i, s ->
                    if (i > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = AppSpacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                                Text(s.displayName, style = AppTypography.headline, color = c.textPrimary)
                                if (s.isDownloads) Text(stringResource(R.string.downloads), style = AppTypography.caption, color = c.accent)
                            }
                            Text(stringResource(if (s.isWritable) R.string.sources_rw else R.string.sources_ro), style = AppTypography.caption, color = c.textSecondary)
                            if (problems[s.id] != null) Text(stringResource(R.string.sources_unreachable), style = AppTypography.caption, color = c.warning)
                        }
                        // iOS: Wischaktion „Verbindung entfernen“. Android: sichtbare Aktion.
                        TextButton({ scope.launch { withContext(Dispatchers.IO) { app.sources.remove(s.id) } } }) {
                            Text(stringResource(R.string.sources_remove), color = c.cleanup, fontWeight = FontWeight.Medium, style = AppTypography.footnote)
                        }
                    }
                }
                if (sources.isNotEmpty()) RowDivider()
                PlainButton(stringResource(R.string.add_folder), onClick = pick)
            }
            Spacer(Modifier.height(TabBarClearance))
        }
    }
    NoticeDialog(errorText) { errorText = null }
}
