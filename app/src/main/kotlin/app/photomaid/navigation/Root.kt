package app.photomaid.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.photomaid.AppModel
import app.photomaid.FeedRequest
import app.photomaid.R
import app.photomaid.design.AppBackground
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.PM
import app.photomaid.design.glassCapsule
import app.photomaid.feature.cleanup.CleanUpHubScreen
import app.photomaid.feature.cleanup.CleanupBasketScreen
import app.photomaid.feature.duplicates.DuplicatesScreen
import app.photomaid.feature.files.FileBrowserScreen
import app.photomaid.feature.files.SourcesScreen
import app.photomaid.feature.history.HistoryScreen
import app.photomaid.feature.later.LaterScreen
import app.photomaid.feature.onboarding.PrivacyAccessScreen
import app.photomaid.feature.organize.OrganizeScreen
import app.photomaid.feature.pro.ProScreen
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.feature.rules.RulesScreen
import app.photomaid.feature.settings.HelpScreen
import app.photomaid.feature.settings.SettingsScreen
import app.photomaid.feature.storage.LargeFilesScreen
import app.photomaid.feature.storage.StorageInsightsScreen
import app.photomaid.feature.today.TodayScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalApp = staticCompositionLocalOf<AppModel> { error("AppModel fehlt") }

/** Navigationsaktionen für Screens (keine Routing-Logik in einzelnen Screens). */
interface Nav {
    fun openFeed(request: FeedRequest)
    fun openSettings()
    fun openPro()
    fun openPrivacy()
    fun push(route: String)
    fun back()
}

object Routes {
    const val TODAY = "today"; const val HUB = "hub"; const val ORGANIZE = "organize"; const val HISTORY = "history"
    const val BASKET = "basket"; const val FILES = "files"; const val SOURCES = "sources"; const val LARGE = "large"
    const val DUPLICATES = "duplicates"; const val STORAGE = "storage"; const val LATER = "later"; const val RULES = "rules"
    const val MAIN = "main"; const val FEED = "feed/{id}"; const val SETTINGS = "settings"; const val PRO = "pro"
    const val PRIVACY = "privacy"; const val HELP = "help"; const val BASKET_MODAL = "basketModal"
    const val SOURCES_MODAL = "sourcesModal"
    fun feed(id: String) = "feed/$id"
}

enum class AppTab(val route: String, val label: Int, val icon: ImageVector) {
    TODAY(Routes.TODAY, R.string.tab_today, Icons.Rounded.Home),
    CLEANUP(Routes.HUB, R.string.tab_cleanup, Icons.Rounded.Layers),
    ORGANIZE(Routes.ORGANIZE, R.string.tab_organize, Icons.Rounded.Folder),
    HISTORY(Routes.HISTORY, R.string.tab_history, Icons.Rounded.History);

    val graph: String get() = "tab_$name"
}

/** Offene Feed-Anfragen (Aufgaben/Itemlisten sind zu groß für Routenargumente). */
object FeedRequests {
    val map = mutableStateMapOf<String, FeedRequest>()
}

@Composable
fun RootContent(app: AppModel) {
    CompositionLocalProvider(LocalApp provides app) {
        val settings by app.settings.collectAsState()
        val scope = rememberCoroutineScope()
        var privacyShield by remember { mutableStateOf(false) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val obs = LifecycleEventObserver { _, e ->
                when (e) {
                    Lifecycle.Event.ON_RESUME -> { privacyShield = false; scope.launch { app.onForeground() } }
                    Lifecycle.Event.ON_PAUSE -> { privacyShield = true; app.player.stop() }
                    Lifecycle.Event.ON_STOP -> app.player.release()
                    else -> Unit
                }
            }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }
        // Rewarded-Vorschau: Restzeit/Ablauf sichtbar halten.
        LaunchedEffect(Unit) { while (true) { delay(30_000); app.entitlements.tick() } }

        if (!settings.privacyIntroSeen) {
            PrivacyAccessScreen(onboarding = true, onDone = { scope.launch { app.updateSettings { it.copy(privacyIntroSeen = true) } } })
        } else {
            RootNav(app)
        }
        val error by app.lastError.collectAsState()
        error?.let { msg ->
            AlertDialog(
                onDismissRequest = app::clearError,
                confirmButton = { TextButton(onClick = app::clearError) { Text(stringResource(R.string.ok)) } },
                title = { Text(stringResource(R.string.notice)) }, text = { Text(msg) },
            )
        }
        // App-Übersicht neutralisieren (iOS: Abdeckung, sobald die Szene nicht aktiv ist).
        AnimatedVisibility(privacyShield, enter = fadeIn(), exit = fadeOut()) {
            AppBackground { Icon(Icons.Rounded.PhotoLibrary, null, tint = PM.colors.accent, modifier = Modifier.size(56.dp).align(Alignment.Center)) }
        }
    }
}

@Composable
private fun RootNav(app: AppModel) {
    val root = rememberNavController()
    val nav = remember(root) {
        object : Nav {
            override fun openFeed(request: FeedRequest) { FeedRequests.map[request.id] = request; root.navigate(Routes.feed(request.id)) }
            override fun openSettings() = root.navigate(Routes.SETTINGS)
            override fun openPro() = root.navigate(Routes.PRO)
            override fun openPrivacy() = root.navigate(Routes.PRIVACY)
            override fun push(route: String) = root.navigate(route)
            override fun back() { root.popBackStack() }
        }
    }
    NavHost(root, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) { MainTabs(nav) }
        composable(Routes.FEED) { entry ->
            val id = entry.arguments?.getString("id")
            val req = id?.let { FeedRequests.map[it] }
            if (req == null) {
                // Nach Prozessende ist die Anfrage verloren: zurück zu den Tabs (Entscheidungen sind gespeichert).
                LaunchedEffect(Unit) { root.popBackStack() }
            } else {
                ReviewFeedScreen(req, nav, onClose = { root.popBackStack(); FeedRequests.map.remove(id) },
                    openBasket = { root.navigate(Routes.BASKET_MODAL) })
            }
        }
        composable(Routes.BASKET_MODAL) { CleanupBasketScreen(nav, showsDone = true) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.SOURCES_MODAL) { SourcesScreen(nav) }
        composable(Routes.HELP) { HelpScreen(nav) }
        composable(Routes.PRO) { ProScreen(nav) }
        composable(Routes.PRIVACY) { PrivacyAccessScreen(onboarding = false, onDone = { root.popBackStack() }) }
    }
}

/**
 * Vier Haupttabs; jeder Tab ist ein eigener verschachtelter Graph mit eigenem Back-Stack
 * (iOS: NavigationStack je Tab). Zustand bleibt beim Wechsel über saveState/restoreState erhalten.
 */
@Composable
private fun MainTabs(rootNav: Nav) {
    val controller = rememberNavController()
    val entry by controller.currentBackStackEntryAsState()
    val tab = AppTab.entries.firstOrNull { t -> entry?.destination?.hierarchy?.any { it.route == t.graph } == true } ?: AppTab.TODAY
    val nav = remember(controller, rootNav) {
        object : Nav by rootNav {
            override fun push(route: String) {
                val current = AppTab.entries.firstOrNull { t -> controller.currentDestination?.hierarchy?.any { it.route == t.graph } == true } ?: AppTab.TODAY
                controller.navigate("${current.name}/$route")
            }
            override fun back() { controller.popBackStack() }
        }
    }
    Box(Modifier.fillMaxSize()) {
        NavHost(controller, startDestination = AppTab.TODAY.graph) {
            for (t in AppTab.entries) {
                navigation(startDestination = "${t.name}/${t.route}", route = t.graph) {
                    composable("${t.name}/${t.route}") {
                        when (t) {
                            AppTab.TODAY -> TodayScreen(nav)
                            AppTab.CLEANUP -> CleanUpHubScreen(nav)
                            AppTab.ORGANIZE -> OrganizeScreen(nav)
                            AppTab.HISTORY -> HistoryScreen(nav)
                        }
                    }
                    composable("${t.name}/${Routes.BASKET}") { CleanupBasketScreen(nav, showsDone = false) }
                    composable("${t.name}/${Routes.FILES}") { FileBrowserScreen(nav) }
                    composable("${t.name}/${Routes.SOURCES}") { SourcesScreen(nav) }
                    composable("${t.name}/${Routes.LARGE}") { LargeFilesScreen(nav) }
                    composable("${t.name}/${Routes.DUPLICATES}") { DuplicatesScreen(nav) }
                    composable("${t.name}/${Routes.STORAGE}") { StorageInsightsScreen(nav) }
                    composable("${t.name}/${Routes.LATER}") { LaterScreen(nav) }
                    composable("${t.name}/${Routes.RULES}") { RulesScreen(nav) }
                }
            }
        }
        GlassTabBar(tab, Modifier.align(Alignment.BottomCenter)) { selected ->
            if (selected == tab) {
                controller.popBackStack("${selected.name}/${selected.route}", inclusive = false)
            } else {
                controller.navigate(selected.graph) {
                    popUpTo(controller.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }
}

/** Schwebende Glas-Tabbar (iOS 26+-Tabbar mit Liquid Glass). */
@Composable
fun GlassTabBar(selected: AppTab, modifier: Modifier = Modifier, onSelect: (AppTab) -> Unit) {
    val c = PM.colors
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = AppSpacing.l, vertical = AppSpacing.xs)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .glassCapsule(elevation = 10.dp)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (t in AppTab.entries) {
            val isSel = t == selected
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 54.dp)
                    .clip(CircleShape)
                    .then(if (isSel) Modifier.glassCapsule(elevation = 0.dp) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(t) }
                    .semantics { this.selected = isSel }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(t.icon, null, tint = if (isSel) c.accent else c.textPrimary, modifier = Modifier.size(24.dp))
                Text(stringResource(t.label), style = AppTypography.caption2.copy(fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (isSel) c.accent else c.textPrimary)
            }
        }
    }
}

/** Platz am Listenende, damit Inhalte nicht unter der schwebenden Tabbar liegen. */
val TabBarClearance = 96.dp
