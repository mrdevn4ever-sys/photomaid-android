package app.photomaid.navigation

import app.photomaid.feature.workflow.WorkflowRunScreen
import app.photomaid.feature.workflow.WorkflowEditorScreen
import app.photomaid.feature.workflow.WorkflowsScreen
import app.photomaid.feature.workflow.WorkflowRequests
import app.photomaid.feature.workflow.WorkflowRunRequest
import app.photomaid.feature.workflow.WorkflowRequest
import app.photomaid.feature.organize.OrganizeScreen
import app.photomaid.feature.share.ShareRequests
import app.photomaid.feature.document.PdfRequest
import app.photomaid.feature.document.PdfRequests
import app.photomaid.feature.document.PdfScreen
import app.photomaid.feature.share.ShareScreen
import app.photomaid.system.AppEntry
import app.photomaid.system.EntryRequest
import app.photomaid.core.share.ShortcutTarget
import app.photomaid.feature.organize.OrganizeTarget
import app.photomaid.feature.months.MonthsScreen
import app.photomaid.feature.editor.EditorScreen
import app.photomaid.feature.editor.EditsScreen
import app.photomaid.feature.grid.MediaGridScreen
import app.photomaid.feature.settings.ExcludedFoldersScreen
import app.photomaid.feature.library.FolderScreen
import app.photomaid.feature.library.FolderTarget
import app.photomaid.feature.library.HiddenScreen
import app.photomaid.feature.studio.MovieRequest
import app.photomaid.feature.studio.MovieRequests
import app.photomaid.feature.studio.ToolRequest
import app.photomaid.feature.studio.ToolRequests
import app.photomaid.feature.studio.ToolsScreen
import app.photomaid.feature.studio.MovieScreen
import app.photomaid.feature.cleanup.SystemTrashScreen
import app.photomaid.feature.library.SlideshowRequest
import app.photomaid.feature.library.SlideshowRequests
import app.photomaid.feature.library.SlideshowScreen
import app.photomaid.design.CoachStep
import app.photomaid.design.CoachHost
import app.photomaid.design.coachTarget
import app.photomaid.feature.onboarding.ONBOARDING_VERSION
import app.photomaid.feature.onboarding.OnboardingFlow
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.system.SystemDialogs
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import app.photomaid.design.AppControl
import app.photomaid.design.AppMotion
import app.photomaid.design.LocalReduceMotion
import androidx.navigation.NavBackStackEntry
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedContentTransitionScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.photomaid.design.glass
import app.photomaid.design.glassCapsule
import app.photomaid.feature.cleanup.CleanUpHubScreen
import app.photomaid.feature.cleanup.CleanupBasketScreen
import app.photomaid.feature.duplicates.DuplicatesScreen
import app.photomaid.feature.files.FileBrowserScreen
import app.photomaid.feature.files.SourcesScreen
import app.photomaid.feature.history.HistoryScreen
import app.photomaid.feature.later.LaterScreen
import app.photomaid.feature.onboarding.PrivacyAccessScreen
import app.photomaid.feature.pro.ProScreen
import app.photomaid.feature.review.ReviewFeedScreen
import app.photomaid.feature.settings.HelpScreen
import app.photomaid.feature.settings.SettingsScreen
import app.photomaid.feature.storage.LargeFilesScreen
import app.photomaid.feature.storage.StorageInsightsScreen
import app.photomaid.feature.today.TodayScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.photomaid.feature.studio.CollageRequest
import app.photomaid.feature.studio.CollageRequests
import app.photomaid.feature.studio.CollageScreen
import app.photomaid.feature.studio.MediaPickerScreen
import app.photomaid.feature.studio.PickRequest
import app.photomaid.feature.studio.PickRequests
import app.photomaid.feature.studio.StudioScreen
import app.photomaid.feature.library.LibraryScreen
import app.photomaid.feature.library.ViewerRequest
import app.photomaid.feature.library.ViewerRequests
import app.photomaid.feature.library.ViewerScreen

val LocalApp = staticCompositionLocalOf<AppModel> { error("AppModel fehlt") }

/** Navigationsaktionen für Screens (keine Routing-Logik in einzelnen Screens). */
interface Nav {
    fun openFeed(request: FeedRequest)
    /** Raster-Übersicht derselben Quelle (Auswählen, mehrere verschieben). */
    fun openGrid(request: FeedRequest) {}
    /** Editor für ein Medium (Stift-Symbol in der Aktionsleiste). Nie eine Entscheidung. */
    fun openEditor(assetId: String) {}
    /** Studio: Medienauswahl (Wählen ist nie eine Entscheidung) und Collage. */
    fun openPicker(request: PickRequest) {}
    fun openCollage(request: CollageRequest) {}
    /** Mediathek: Vollbild-Betrachter (Ansehen ist nie eine Entscheidung). */
    fun openViewer(request: ViewerRequest) {}
    /** Mediathek: Ordner bzw. eigene Gruppe öffnen. */
    fun openFolder(target: FolderTarget) {}
    /** Organisieren: Album, Schlagwort oder Auswahlkorb (nie eine Entscheidung). */
    fun openOrganize(target: OrganizeTarget) {}
    /** Arbeitsabläufe: Liste (optional mit Auswahl) und Ausführen. Nie eine Entscheidung über die Quellen. */
    fun openWorkflows(request: WorkflowRequest) {}
    fun openWorkflowRun(request: WorkflowRunRequest) {}
    /** Paket 4: PDF / Kontaktbogen aus Fotos. */
    fun openPdf(request: PdfRequest) {}
    /** Diashow (nur Ansehen). */
    fun openSlideshow(request: SlideshowRequest) {}
    /** Studio P6: Video zusammenfügen / Diashow als Video. */
    fun openMovie(request: MovieRequest) {}
    fun openTool(request: ToolRequest) {}
    fun openSettings()
    fun openPro()
    fun openPrivacy()
    fun push(route: String)
    fun back()
}

object Routes {
    const val TODAY = "today"; const val HUB = "hub"; const val HISTORY = "history"
    const val BASKET = "basket"; const val FILES = "files"; const val SOURCES = "sources"; const val LARGE = "large"
    const val DUPLICATES = "duplicates"; const val STORAGE = "storage"; const val LATER = "later"
    const val MAIN = "main"; const val FEED = "feed/{id}"; const val SETTINGS = "settings"; const val PRO = "pro"
    const val PRIVACY = "privacy"; const val HELP = "help"; const val BASKET_MODAL = "basketModal"
    const val SOURCES_MODAL = "sourcesModal"; const val EXCLUDED = "excluded"; const val MONTHS = "months"
    const val GRID = "grid/{id}"; const val EDITOR = "editor/{id}"; const val EDITS = "edits"; const val STUDIO = "studio"
    const val LIBRARY = "library"; const val VIEWER = "viewer/{id}"; const val FOLDER = "folder/{target}"; const val HIDDEN = "hidden"; const val SLIDESHOW = "slideshow/{id}"; const val SYSTEM_TRASH = "systemTrash"; const val MOVIE = "movie/{id}"; const val TOOLS = "tools/{id}"
    const val PICKER = "picker/{id}"; const val COLLAGE = "collage/{id}"
    fun editor(id: String) = "editor/$id"
    fun grid(id: String) = "grid/$id"
    fun feed(id: String) = "feed/$id"
}

enum class AppTab(val route: String, val label: Int, val icon: ImageVector) {
    TODAY(Routes.TODAY, R.string.tab_today, Icons.Rounded.Home),
    LIBRARY(Routes.LIBRARY, R.string.tab_library, Icons.Rounded.PhotoLibrary),
    CLEANUP(Routes.HUB, R.string.tab_cleanup, Icons.Rounded.Layers),
    STUDIO(Routes.STUDIO, R.string.tab_studio, Icons.Rounded.Palette),
    HISTORY(Routes.HISTORY, R.string.tab_history, Icons.Rounded.History);

    val graph: String get() = "tab_$name"
}

/** Offene Feed-Anfragen (Aufgaben/Itemlisten sind zu groß für Routenargumente). */
object FeedRequests {
    val map = mutableStateMapOf<String, FeedRequest>()
}

/** Offene Editor-Aufrufe (Medien-IDs sind Content-URIs und taugen nicht als Routenargument). */
object EditorRequests {
    val map = mutableStateMapOf<String, String>()
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
                    // Systembestätigung (Favorit, Verschieben, Papierkorb) pausiert die App nur kurz: kein Sichtschutz-Flackern,
                    // Video nur anhalten statt stoppen.
                    Lifecycle.Event.ON_PAUSE -> if (SystemDialogs.inProgress) app.player.pause() else { privacyShield = true; app.player.stop() }
                    Lifecycle.Event.ON_STOP -> if (!SystemDialogs.inProgress) app.player.release()
                    else -> Unit
                }
            }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }
        // Rewarded-Vorschau: Restzeit/Ablauf sichtbar halten.
        LaunchedEffect(Unit) { while (true) { delay(30_000); app.entitlements.tick() } }

        // Werbe-Einwilligung (Google UMP) erst nach dem Onboarding und nie für Lifetime Pro; danach SDK-Start.
        val purchase by app.purchases.state.collectAsState()
        LaunchedEffect(settings.privacyIntroSeen, purchase) {
            if (settings.privacyIntroSeen && purchase != PurchaseState.PURCHASED && !app.isQAFixtureMode)
                SystemDialogs.activity?.let { app.adSdk.start(it) }
        }
        if (!settings.privacyIntroSeen) {
            OnboardingFlow(onDone = {
                scope.launch { app.updateSettings { it.copy(privacyIntroSeen = true, onboardingVersion = ONBOARDING_VERSION) } }
            })
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
            override fun openGrid(request: FeedRequest) { FeedRequests.map[request.id] = request; root.navigate(Routes.grid(request.id)) }
            override fun openEditor(assetId: String) {
                val key = java.util.UUID.randomUUID().toString(); EditorRequests.map[key] = assetId; root.navigate(Routes.editor(key))
            }
            override fun openPicker(request: PickRequest) {
                PickRequests.map[request.id] = request; root.navigate("picker/${request.id}")
            }
            override fun openViewer(request: ViewerRequest) {
                ViewerRequests.map[request.id] = request; root.navigate("viewer/${request.id}")
            }
            override fun openMovie(request: MovieRequest) { MovieRequests.map[request.id] = request; root.navigate("movie/${request.id}") }
            override fun openTool(request: ToolRequest) { ToolRequests.map[request.id] = request; root.navigate("tools/${request.id}") }
            override fun openSlideshow(request: SlideshowRequest) {
                SlideshowRequests.map[request.id] = request; root.navigate("slideshow/${request.id}")
            }
            override fun openWorkflows(request: WorkflowRequest) { WorkflowRequests.lists[request.id] = request; root.navigate("workflows/${request.id}") }
            override fun openWorkflowRun(request: WorkflowRunRequest) { WorkflowRequests.runs[request.id] = request; root.navigate("workflow/run/${request.id}") }
            override fun openPdf(request: PdfRequest) {
                PdfRequests.map[request.id] = request; root.navigate("pdf/${request.id}")
            }
            override fun openOrganize(target: OrganizeTarget) {
                root.navigate("organize/" + android.net.Uri.encode(target.encode()))
            }
            override fun openFolder(target: FolderTarget) {
                root.navigate("folder/" + android.net.Uri.encode(target.encode()))
            }
            override fun openCollage(request: CollageRequest) {
                CollageRequests.map[request.id] = request; root.navigate("collage/${request.id}")
            }
            override fun openSettings() = root.navigate(Routes.SETTINGS)
            override fun openPro() = root.navigate(Routes.PRO)
            override fun openPrivacy() = root.navigate(Routes.PRIVACY)
            override fun push(route: String) = root.navigate(route)
            override fun back() { root.popBackStack() }
        }
    }
    val reduce = LocalReduceMotion.current
    NavHost(root, startDestination = Routes.MAIN,
        // Nutzerwunsch (Gerätetest-Video): keine Bewegung, kein Schrumpfen. Öffnen = sehr kurzes, leichtes
        // Überblenden; Zurück = sofort.
        enterTransition = { if (reduce) EnterTransition.None else NavMotion.fadeIn() },
        exitTransition = { if (reduce) ExitTransition.None else NavMotion.fadeOut() },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Routes.MAIN) { MainTabs(nav) }
        composable(Routes.FEED) { entry ->
            val id = entry.arguments?.getString("id")
            val req = id?.let { FeedRequests.map[it] }
            // Anfrage erst entfernen, wenn der Eintrag wirklich verlassen ist (nicht schon beim Schließen):
            // sonst sieht die Ausblend-Komposition `req == null` und würde ein zweites Mal zurücknavigieren
            // (leerer Back-Stack → leerer Bildschirm, Gerätetest RMX3933).
            DisposableEffect(entry) {
                val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_DESTROY) id?.let { FeedRequests.map.remove(it) } }
                entry.lifecycle.addObserver(obs)
                onDispose { entry.lifecycle.removeObserver(obs) }
            }
            if (req == null) {
                // Nach Prozessende ist die Anfrage verloren: zurück zu den Tabs (Entscheidungen sind gespeichert).
                // Nur, solange dieser Eintrag oben liegt – nie einen anderen Eintrag wegnavigieren.
                LaunchedEffect(entry) { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            } else {
                ReviewFeedScreen(req, nav, onClose = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() },
                    openBasket = { root.navigate(Routes.BASKET_MODAL) })
            }
        }
        composable(Routes.BASKET_MODAL) { CleanupBasketScreen(nav, showsDone = true) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.SOURCES_MODAL) { SourcesScreen(nav) }
        composable(Routes.HELP) { HelpScreen(nav) }
        composable(Routes.EXCLUDED) { ExcludedFoldersScreen(nav) }
        composable(Routes.GRID) { entry ->
            val req = entry.arguments?.getString("id")?.let { FeedRequests.map[it] }
            if (req == null) LaunchedEffect(entry) { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            else MediaGridScreen(req, nav, onClose = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() })
        }
        composable(Routes.EDITOR) { entry ->
            val key = entry.arguments?.getString("id")
            val assetId = key?.let { EditorRequests.map[it] }
            DisposableEffect(entry) {
                val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_DESTROY) key?.let { EditorRequests.map.remove(it) } }
                entry.lifecycle.addObserver(obs)
                onDispose { entry.lifecycle.removeObserver(obs) }
            }
            if (assetId == null) LaunchedEffect(entry) { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            else EditorScreen(assetId, nav, onClose = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() })
        }
        composable(Routes.PICKER) { entry ->
            val key = entry.arguments?.getString("id")
            val req = key?.let { PickRequests.map[it] }
            RemoveOnDestroy(entry) { key?.let { PickRequests.map.remove(it) } }
            val close = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            if (req == null) LaunchedEffect(entry) { close() }
            // Erst schließen, dann weiter (z. B. Collage öffnen): die Auswahl liegt nie unter dem nächsten Schritt.
            else MediaPickerScreen(req.copy(onDone = { ids -> close(); req.onDone(ids) }), onClose = { close() })
        }
        composable(Routes.VIEWER) { entry ->
            val key = entry.arguments?.getString("id")
            val req = key?.let { ViewerRequests.map[it] }
            RemoveOnDestroy(entry) { key?.let { ViewerRequests.map.remove(it) } }
            val close = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            if (req == null) LaunchedEffect(entry) { close() }
            else ViewerScreen(req, nav, onClose = { close() })
        }
        composable(Routes.FOLDER) { entry ->
            val target = entry.arguments?.getString("target")?.let(FolderTarget::decode)
            val close = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            if (target == null) LaunchedEffect(entry) { close() }
            else FolderScreen(target, nav, onClose = { close() })
        }
        composable("workflows/{id}") { entry ->
            val key = entry.arguments?.getString("id"); val req = key?.let { WorkflowRequests.lists[it] }
            val close = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            if (req == null) LaunchedEffect(entry) { close() } else WorkflowsScreen(req, nav, onClose = { close() })
        }
        composable("workflow/edit/{id}") { entry ->
            val id = entry.arguments?.getString("id")
            val close = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            if (id == null) LaunchedEffect(entry) { close() } else WorkflowEditorScreen(id, nav, onClose = { close() })
        }
        composable("workflow/run/{id}") { entry ->
            val key = entry.arguments?.getString("id"); val req = key?.let { WorkflowRequests.runs[it] }
            val close = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            if (req == null) LaunchedEffect(entry) { close() } else WorkflowRunScreen(req, nav, onClose = { key?.let { WorkflowRequests.runs.remove(it) }; close() })
        }
        composable("pdf/{id}") { entry ->
            val key = entry.arguments?.getString("id"); val req = key?.let { PdfRequests.map[it] }
            RemoveOnDestroy(entry) { key?.let { PdfRequests.map.remove(it) } }
            val close = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            if (req == null) LaunchedEffect(entry) { close() } else PdfScreen(req, nav, onClose = { close() })
        }
        composable("share/{id}") { entry ->
            val key = entry.arguments?.getString("id"); val req = key?.let { ShareRequests.map[it] }
            RemoveOnDestroy(entry) { key?.let { ShareRequests.map.remove(it) } }
            val close = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            if (req == null) LaunchedEffect(entry) { close() } else ShareScreen(req, nav, onClose = { close() })
        }
        composable("organize/{target}") { entry ->
            val target = entry.arguments?.getString("target")?.let(OrganizeTarget::decode)
            val close = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            if (target == null) LaunchedEffect(entry) { close() }
            else OrganizeScreen(target, nav, onClose = { close() })
        }
        composable(Routes.HIDDEN) { HiddenScreen(nav) }
        composable(Routes.SYSTEM_TRASH) { SystemTrashScreen(nav) }
        composable(Routes.MOVIE) { entry ->
            val key = entry.arguments?.getString("id")
            val req = key?.let { MovieRequests.map[it] }
            val close = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            if (req == null) LaunchedEffect(entry) { close() }
            else MovieScreen(req, nav, onClose = { key?.let { MovieRequests.map.remove(it) }; close() })
        }
        composable(Routes.TOOLS) { entry ->
            val key = entry.arguments?.getString("id")
            val req = key?.let { ToolRequests.map[it] }
            val close = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            if (req == null) LaunchedEffect(entry) { close() }
            else ToolsScreen(req, nav, onClose = { key?.let { ToolRequests.map.remove(it) }; close() })
        }
        composable(Routes.SLIDESHOW) { entry ->
            val key = entry.arguments?.getString("id")
            val req = key?.let { SlideshowRequests.map[it] }
            RemoveOnDestroy(entry) { key?.let { SlideshowRequests.map.remove(it) } }
            val close = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            if (req == null) LaunchedEffect(entry) { close() }
            else SlideshowScreen(req, onClose = { close() })
        }
        composable(Routes.COLLAGE) { entry ->
            val key = entry.arguments?.getString("id")
            val req = key?.let { CollageRequests.map[it] }
            RemoveOnDestroy(entry) { key?.let { CollageRequests.map.remove(it) } }
            val close = { if (root.currentBackStackEntry?.id == entry.id) root.popBackStack() }
            if (req == null) LaunchedEffect(entry) { close() }
            else CollageScreen(req, nav, pick = nav::openPicker, onClose = { close() })
        }
        composable(Routes.PRO) { ProScreen(nav) }
        composable(Routes.PRIVACY) { PrivacyAccessScreen(onboarding = false, onDone = { root.popBackStack() }) }
    }
    // Einstiege von außen (App-Shortcut, Teilen): erst hier, nach dem Onboarding, und genau einmal abholen.
    val owner = System.identityHashCode(LocalContext.current.findActivity())
    LaunchedEffect(nav, owner) {
        AppEntry.pending.collect { if (it != null) AppEntry.take(owner)?.let { r -> openEntry(app, nav, r) } }
    }
}

/** Ein Einstieg öffnet nur eine Ansicht – keine Entscheidung, nichts wird gelöscht oder ohne weiteren Tipp gestartet. */
internal fun openEntry(app: AppModel, nav: Nav, r: EntryRequest) {
    when (r) {
        is EntryRequest.Share -> { val id = java.util.UUID.randomUUID().toString(); ShareRequests.map[id] = r; nav.push("share/$id") }
        is EntryRequest.Shortcut -> when (val t = r.target) {
            ShortcutTarget.Clean -> nav.openFeed(FeedRequest(FeedRequest.Source.AllMedia))
            ShortcutTarget.Due -> nav.openFeed(FeedRequest(FeedRequest.Source.Due))
            ShortcutTarget.Basket -> nav.openOrganize(OrganizeTarget.Basket)
            ShortcutTarget.Workflows -> nav.openWorkflows(WorkflowRequest(java.util.UUID.randomUUID().toString(), null))
            is ShortcutTarget.Workflow -> {
                val w = app.workflows.get(t.workflowId)
                if (w == null) nav.openWorkflows(WorkflowRequest(java.util.UUID.randomUUID().toString(), null))
                else nav.openPicker(PickRequest(java.util.UUID.randomUUID().toString(), photos = true, videos = true, min = 1, max = 500,
                    title = R.string.wf_pick, library = true) { picked ->
                    nav.openWorkflowRun(WorkflowRunRequest(java.util.UUID.randomUUID().toString(), w.id, picked))
                })
            }
        }
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
    val app = LocalApp.current
    val settings by app.settings.collectAsState()
    val access by app.photos.access.collectAsState()
    val tourScope = rememberCoroutineScope()
    val atRootNow = AppTab.entries.any { t -> entry?.destination?.route == "${t.name}/${t.route}" } || entry == null
    // Hinweiskarten-Tutorial einmal auf „Heute“ (nach dem Onboarding, mit Medienzugriff); Überspringen merkt es sich auch.
    val tourSteps = listOf(
        CoachStep("today.review", stringResource(R.string.tour_today_hero_title), stringResource(R.string.tour_today_hero_text)),
        CoachStep("today.status", stringResource(R.string.tour_today_status_title), stringResource(R.string.tour_today_status_text)),
        CoachStep("today.quick", stringResource(R.string.tour_today_quick_title), stringResource(R.string.tour_today_quick_text)),
        // Rundgang durch alle Bereiche (Reihenfolge wie die Leiste), zum Schluss Einstellungen & Hilfe.
        CoachStep("tab.LIBRARY", stringResource(R.string.tour_lib_title), stringResource(R.string.tour_lib_text)),
        CoachStep("tab.CLEANUP", stringResource(R.string.tour_today_tab_title), stringResource(R.string.tour_today_tab_text)),
        CoachStep("tab.STUDIO", stringResource(R.string.tour_studio_title), stringResource(R.string.tour_studio_text)),
        CoachStep("tab.HISTORY", stringResource(R.string.tour_history_title), stringResource(R.string.tour_history_text)),
        CoachStep("today.settings", stringResource(R.string.tour_settings_title), stringResource(R.string.tour_settings_text)),
    )
    CoachHost(tourSteps, active = !settings.todayTourSeen && tab == AppTab.TODAY && atRootNow && access.canRead,
        onFinish = { tourScope.launch { app.updateSettings { it.copy(todayTourSeen = true) } } }, modifier = Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize()) {
        val reduce = LocalReduceMotion.current
        // Innerhalb eines Tabs: Unterseite öffnen = sehr kurzes, leichtes Überblenden; Zurück und Tabwechsel = sofort.
        fun AnimatedContentTransitionScope<NavBackStackEntry>.sameTab() =
            initialState.destination.parent?.route == targetState.destination.parent?.route
        NavHost(controller, startDestination = AppTab.TODAY.graph,
            enterTransition = { if (!reduce && sameTab()) NavMotion.fadeIn() else EnterTransition.None },
            exitTransition = { if (!reduce && sameTab()) NavMotion.fadeOut() else ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            for (t in AppTab.entries) {
                navigation(startDestination = "${t.name}/${t.route}", route = t.graph) {
                    composable("${t.name}/${t.route}") {
                        when (t) {
                            AppTab.TODAY -> TodayScreen(nav)
                            AppTab.LIBRARY -> LibraryScreen(nav)
                            AppTab.CLEANUP -> CleanUpHubScreen(nav)
                            AppTab.STUDIO -> StudioScreen(nav)
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
                    composable("${t.name}/${Routes.MONTHS}") { MonthsScreen(nav) }
                    composable("${t.name}/${Routes.EDITS}") { EditsScreen(nav) }
                }
            }
        }
        // Tabbar nur auf den Tab-Startseiten. Unterseiten (Papierkorb, Dateien, Später, …) haben ihren Zurück-Knopf;
        // so verdeckt die Leiste dort weder Inhalte noch Aktionsknöpfe am unteren Rand.
        val atRoot = AppTab.entries.any { t -> entry?.destination?.route == "${t.name}/${t.route}" } || entry == null
        AnimatedVisibility(atRoot, Modifier.align(Alignment.BottomCenter), enter = fadeIn(NavMotion.tab()), exit = fadeOut(NavMotion.tab())) {
        GlassTabBar(tab) { selected ->
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
    }
}

/** Anfrage eines Eintrags erst beim endgültigen Verlassen entfernen (nicht schon beim Ausblenden). */
@Composable
private fun RemoveOnDestroy(entry: NavBackStackEntry, remove: () -> Unit) {
    DisposableEffect(entry) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_DESTROY) remove() }
        entry.lifecycle.addObserver(obs)
        onDispose { entry.lifecycle.removeObserver(obs) }
    }
}

/** Schwebende Glas-Tabbar (iOS 26+-Tabbar mit Liquid Glass). */
@Composable
fun GlassTabBar(selected: AppTab, modifier: Modifier = Modifier, onSelect: (AppTab) -> Unit) {
    val c = PM.colors
    // Übergang über der schwebenden Leiste: Inhalte laufen weich aus statt sichtbar darunter weiter.
    Box(modifier.fillMaxWidth().drawBehind {
        val ext = 40.dp.toPx()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, (ext * 0.8f / (size.height + ext)) to c.backgroundTint.copy(alpha = 0.97f), 1f to c.backgroundTint,
            startY = -ext, endY = size.height), topLeft = Offset(0f, -ext), size = Size(size.width, size.height + ext))
    }, contentAlignment = Alignment.BottomCenter) { TabBarRow(selected, onSelect) }
}

@Composable
private fun TabBarRow(selected: AppTab, onSelect: (AppTab) -> Unit) {
    val c = PM.colors
    // Fünf Tabs auf schmalen Telefonen (< 360 dp): weniger Rand, etwas kleinere Beschriftung – nichts abgeschnitten.
    val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = if (narrow) AppSpacing.xs else AppSpacing.l, vertical = AppSpacing.xs)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .glass(c.copy(glass = c.glassBar), CircleShape, elevation = 10.dp)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (t in AppTab.entries) {
            val isSel = t == selected
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = AppControl.tabBarItem)
                    // Abgerundetes Rechteck statt Kreis: bei schmalen Tabs schneidet ein Kreis die Beschriftung an den Enden ab.
                    .clip(RoundedCornerShape(22.dp))
                    // Auswahl als Akzent-Tönung (im Light Mode war die weiße Kapsel auf weißer Leiste kaum sichtbar).
                    .then(if (isSel) Modifier.background(c.accent.copy(alpha = 0.12f), RoundedCornerShape(22.dp)) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(t) }
                    .coachTarget("tab.${t.name}")
                    .semantics { this.selected = isSel }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(t.icon, null, tint = if (isSel) c.accent else c.textPrimary, modifier = Modifier.size(24.dp))
                // Lange Wörter (z. B. „Прибирання“, „Aujourd’hui“) werden auf schmalen Telefonen kleiner statt abgeschnitten.
                val label = stringResource(t.label)
                var fit by remember(label) { mutableStateOf(AppTypography.caption.fontSize) }
                Text(label, Modifier.fillMaxWidth().padding(horizontal = 3.dp),
                    style = AppTypography.caption.copy(fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium, fontSize = fit),
                    color = if (isSel) c.accent else c.textPrimary, maxLines = 1, softWrap = false,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    onTextLayout = { r -> if (r.hasVisualOverflow && fit.value > 9f) fit = (fit.value - 0.5f).sp })
            }
        }
    }
}

/** Navigationsbewegung: kurz und weich statt des langen Standard-Überblendens von Navigation Compose. */
private object NavMotion {
    private const val FADE_MS = 150
    fun fadeIn() = fadeIn(tween(FADE_MS))
    fun fadeOut() = fadeOut(tween(FADE_MS))
    fun <T> tab() = AppMotion.fast<T>()
}

/** Listenende auf Unterseiten (ohne Tabbar): Abstand zur Systemnavigation plus ruhiger Rand. */
@Composable
fun PageEndSpacer() = Spacer(Modifier.navigationBarsPadding().height(AppSpacing.xl))

/**
 * Platz am Listenende der Tab-Startseiten, damit Inhalte nicht unter der schwebenden Tabbar liegen:
 * Leiste (≈ 78 dp) + Luft. Die Systemnavigation (3-Tasten-Leiste) kommt über [TabBarEndSpacer] dazu.
 */
val TabBarClearance = 96.dp

/** Listenende auf Tab-Startseiten: Tabbar + Systemnavigation (Gerätebefund: 3-Tasten-Leiste verdeckte den Speicher). */
@Composable
fun TabBarEndSpacer(minus: androidx.compose.ui.unit.Dp = 0.dp) = Spacer(Modifier.navigationBarsPadding().height(TabBarClearance - minus))

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
