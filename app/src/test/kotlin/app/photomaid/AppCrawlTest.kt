package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.navigation.RootContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Durchklick-Test der ganzen App (Funktionstest 2026-10-07): Auf jeder der fünf Hauptseiten wird jedes bedienbare Element
 * einzeln angetippt und danach mit „Zurück“ verlassen. Erwartet: kein Absturz, man landet wieder auf der Hauptseite, und
 * Öffnen/Ansehen erzeugt nie eine Entscheidung (Ledger bleibt leer). Ergebnisliste: build/outputs/crawl (free.txt, pro.txt)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w393dp-h852dp")
class AppCrawlTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel

    private fun pump(n: Int = 20) {
        rule.mainClock.autoAdvance = false
        repeat(n) { shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(3) }
        rule.mainClock.autoAdvance = true
    }
    private fun all(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
        rule.onAllNodes(isRoot()).fetchSemanticsNodes().forEach(::walk)
        return out
    }
    private fun label(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.TestTag)
        ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString()?.takeIf { it.isNotBlank() }
        ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()?.takeIf { it.isNotBlank() }
    private fun atRoot() = rule.onAllNodesWithTag("apptab.TODAY", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; pump() }
    private fun clickables(): List<Pair<String, SemanticsNode>> = all().mapNotNull { n ->
        if (n.config.getOrNull(SemanticsActions.OnClick) == null || n.config.getOrNull(SemanticsProperties.Disabled) != null) return@mapNotNull null
        val l = label(n) ?: return@mapNotNull null
        if (l.startsWith("apptab.") || n.boundsInRoot.width < 1f) null else l to n
    }.distinctBy { it.first }

    /** Umschalter innerhalb einer Seite (keine Navigation): zuletzt antippen, sonst verschwinden die übrigen Einträge. */
    private val toggles = setOf("Zeitachse", "Ordner", "Favoriten", "Alben", "Bereiche", "Sammlungen", "Alle", "Behalten", "Entfernt", "Später")
    private fun scroll(up: Boolean) {
        rule.onRoot().performTouchInput { if (up) swipeUp(startY = height * 0.7f, endY = height * 0.25f) else swipeDown(startY = height * 0.25f, endY = height * 0.7f) }
        pump(20)
    }
    private fun toTop() = repeat(6) { scroll(up = false) }
    /** Alle Ziele der Seite, auch die erst nach dem Scrollen sichtbaren. */
    private fun discover(): List<String> {
        val seen = LinkedHashSet<String>()
        toTop()
        repeat(8) { seen += clickables().map { it.first }; scroll(up = true) }
        seen += clickables().map { it.first }
        toTop()
        return seen.toList().sortedBy { if (it in toggles) 1 else 0 }
    }
    private fun find(t: String): SemanticsNode? {
        clickables().firstOrNull { it.first == t }?.let { return it.second }
        toTop()
        repeat(8) { clickables().firstOrNull { it.first == t }?.let { return it.second }; scroll(up = true) }
        return null
    }

    private fun crawl(pro: Boolean) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 8, videos = 2, visual = true) },
            purchase = if (pro) FakePurchaseGateway(PurchaseState.PURCHASED) else null)
        app.editEngine.io = kotlinx.coroutines.Dispatchers.Main; app.editEngine.parallel = false
        rule.setContent { TestHost(app) { RootContent(app) } }
        pump(60)
        val report = StringBuilder()
        val problems = mutableListOf<String>()
        for (tab in listOf("TODAY", "LIBRARY", "CLEANUP", "STUDIO", "HISTORY")) {
            rule.onAllNodesWithTag("apptab.$tab", useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick); pump(40)
            val targets = discover()
            report.append("== $tab (${targets.size} Elemente)\n")
            for (t in targets) {
                if (!atRoot()) { rule.onAllNodesWithTag("apptab.$tab", useUnmergedTree = true).fetchSemanticsNodes().takeIf { it.isNotEmpty() }
                    ?.let { rule.onAllNodesWithTag("apptab.$tab", useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick); pump(30) } }
                val node = find(t) ?: run { report.append("  –  $t (nach Umschalten nicht mehr sichtbar)\n"); continue }
                val ok = runCatching {
                    rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("id ${node.id}") { it.id == node.id })[0]
                        .performSemanticsAction(SemanticsActions.OnClick)
                    pump(40)
                    var backs = 0
                    while (!atRoot() && backs < 4) { back(); backs++ }
                    if (!atRoot()) error("nach 4× Zurück nicht auf der Hauptseite")
                }
                val decided = app.ledger.stateFlow.value.records.isNotEmpty()
                report.append(if (ok.isSuccess && !decided) "  OK $t\n" else "  FEHLER $t: ${ok.exceptionOrNull()?.message ?: "Entscheidung entstanden"}\n")
                if (ok.isFailure) problems += "$tab/$t: ${ok.exceptionOrNull()?.javaClass?.simpleName}: ${ok.exceptionOrNull()?.message?.take(160)}"
                if (decided) { problems += "$tab/$t: Öffnen hat eine Entscheidung erzeugt"; break }
            }
        }
        File("build/outputs/crawl").apply { mkdirs() }.resolve(if (pro) "pro.txt" else "free.txt").writeText(report.toString())
        assertTrue("Durchklick-Funde:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun everyEntryOnEveryMainScreenOpensAndReturnsFree() = crawl(pro = false)
    @Test fun everyEntryOnEveryMainScreenOpensAndReturnsPro() = crawl(pro = true)
}
