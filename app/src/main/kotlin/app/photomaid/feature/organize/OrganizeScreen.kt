package app.photomaid.feature.organize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.design.AppBackground
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DateText
import app.photomaid.design.LargeTitleHeader
import app.photomaid.design.PM
import app.photomaid.design.SourceRow
import app.photomaid.design.SurfaceCard
import app.photomaid.navigation.LocalApp
import app.photomaid.navigation.Nav
import app.photomaid.navigation.Routes
import app.photomaid.navigation.TabBarClearance

/** Ordnen: Später, Regeln/Routinen, verbundene Quellen, offene Prüfaufgaben. */
@Composable
fun OrganizeScreen(nav: Nav) {
    val app = LocalApp.current
    val c = PM.colors
    val read by app.read.collectAsState()
    val sources by app.sources.sources.collectAsState()
    val rules by app.rules.state.collectAsState()
    AppBackground {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = AppSpacing.pageMargin),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            LargeTitleHeader(stringResource(R.string.tab_organize))
            SurfaceCard(Modifier.testTag("organize.later"), onClick = { nav.push(Routes.LATER) }) {
                SourceRow(Icons.Rounded.Bookmark, c.later, stringResource(R.string.later), subtitle = stringResource(R.string.later_manage),
                    value = read.laterRecords.size.toString())
            }
            SurfaceCard(Modifier.testTag("organize.rules"), onClick = { nav.push(Routes.RULES) }) {
                SourceRow(Icons.Rounded.SettingsSuggest, c.accent, stringResource(R.string.rules_title), subtitle = stringResource(R.string.rules_subtitle))
            }
            SurfaceCard(onClick = { nav.push(Routes.SOURCES) }) {
                SourceRow(Icons.Rounded.Folder, c.accent, stringResource(R.string.connected_sources), subtitle = stringResource(R.string.connected_sources_sub),
                    value = sources.size.toString())
            }
            if (rules.tasks.isNotEmpty()) {
                Text(stringResource(R.string.open_tasks), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.padding(top = AppSpacing.s))
                for (task in rules.tasks) {
                    SurfaceCard(onClick = { nav.openFeed(FeedRequest(FeedRequest.Source.Task(task))) }) {
                        SourceRow(Icons.Rounded.Checklist, c.accent, stringResource(R.string.review_task),
                            subtitle = stringResource(R.string.count_items, task.items.size), detail = DateText.abbreviated(task.createdAt))
                    }
                }
            }
            Spacer(Modifier.height(TabBarClearance))
        }
    }
}
