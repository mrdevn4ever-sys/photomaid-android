package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.core.capabilities.PurchaseState
import app.photomaid.design.AppearanceChoice
import app.photomaid.feature.editor.EditorScreen
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Abschluss Editor-Redesign (Prüflog #3): tatsächliche Sichtbarkeit statt bloßer Existenz.
 * - Nach frischem Öffnen ist der zentrale Regler (Anpassen: Belichtung, Zuschnitt: Begradigen) bei normaler Schrift
 *   VOLLSTÄNDIG innerhalb der Werkzeugzone – Name, Regler und Wert; hoch/quer, hell/dunkel.
 * - Große Schrift: jedes Element der Werkzeugzone ist durch Scrollen vollständig erreichbar.
 * - Navigation: der gewählte Eintrag liegt vollständig im sichtbaren Bereich der Leiste (auch 200 %, Arabisch).
 * Gemessen werden die UNGEKÜRZTEN Grenzen (Position + Größe), nicht die von Scroll-Eltern beschnittenen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolZoneVisibilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: AppModel
    private lateinit var lib: FakeMediaLibrary
    private val nav = RecordingNav()

    private fun pumpUntil(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        rule.mainClock.autoAdvance = false
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeByFrame(); Thread.sleep(5)
            if (cond()) break
            check(System.currentTimeMillis() < end) { "Bedingung nicht erfüllt" }
        }
        rule.mainClock.autoAdvance = true
    }
    private fun settle() = repeat(20) { pumpUntil { true } }
    private fun nodes(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
    private fun full(n: SemanticsNode) = Rect(n.positionInRoot, n.size.toSize())
    private fun dp(r: Rect) = r.let { val d = rule.density.density; "[${(it.left / d).toInt()},${(it.top / d).toInt()} – ${(it.right / d).toInt()},${(it.bottom / d).toInt()}] dp" }
    private fun inside(inner: Rect, outer: Rect) =
        inner.left >= outer.left - 1f && inner.top >= outer.top - 1f && inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f
    private fun click(tag: String) {
        rule.openMoreFor(tag) { settle() }
        rule.onAllNodesWithTag(tag, useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick); settle()
    }

    private fun open(dark: Boolean = false) {
        app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, visual = true).also { l -> lib = l } },
            purchase = FakePurchaseGateway(PurchaseState.PURCHASED)) { a ->
            a.updateSettings { it.copy(editorTourSeen = true, appearance = if (dark) AppearanceChoice.DARK else AppearanceChoice.LIGHT) } }
        app.editEngine.io = Dispatchers.Main; app.editEngine.parallel = false
        val photo = lib.all.first { it.displayName == "landscape_sunset" }
        rule.setContent { TestHost(app) { EditorScreen(photo.id, nav, onClose = {}) } }
        pumpUntil { nodes("tab.ADJUST").isNotEmpty() }; settle()
    }

    /** Name, Regler und Wert der Gruppe [group] liegen vollständig in der Werkzeugzone – ohne Scrollen. */
    private fun centralSliderFullyVisible(tab: String, group: String, problems: MutableList<String>) {
        click("tab.$tab")
        val zone = full(nodes("editor.toolzone").first())
        val g = rule.onAllNodes(hasTestTag(group), useUnmergedTree = true).fetchSemanticsNodes().first()
        fun part(m: SemanticsMatcher) = rule.onAllNodes(m and hasAnyAncestor(hasTestTag(group)), useUnmergedTree = true).fetchSemanticsNodes().first()
        val slider = part(hasTestTag("editor.slider")); val value = part(hasTestTag("editor.value"))
        for ((what, r) in listOf("Gruppe" to full(g), "Regler" to full(slider), "Wert" to full(value)))
            if (!inside(r, zone)) problems += "$tab/$group $what ${dp(r)} ragt aus der Werkzeugzone ${dp(zone)}"
    }

    private fun centralSliders(dark: Boolean = false) {
        open(dark)
        val problems = mutableListOf<String>()
        centralSliderFullyVisible("ADJUST", "slider.EXPOSURE", problems)
        centralSliderFullyVisible("CROP", "slider.STRAIGHTEN", problems)
        // Werkzeugwechsel hin und zurück: weiterhin vollständig sichtbar (frischer Zustand je Werkzeug).
        centralSliderFullyVisible("ADJUST", "slider.EXPOSURE", problems)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test @Config(qualifiers = "de-w393dp-h852dp") fun centralSliderVisiblePortraitLight() = centralSliders()
    @Test @Config(qualifiers = "de-w360dp-h800dp-night") fun centralSliderVisiblePortraitDark() = centralSliders(dark = true)
    @Test @Config(qualifiers = "de-w852dp-h393dp-land") fun centralSliderVisibleLandscapeLight() = centralSliders()
    @Test @Config(qualifiers = "de-w800dp-h360dp-land-night") fun centralSliderVisibleLandscapeDark() = centralSliders(dark = true)

    /** Große Schrift: jedes bedienbare Element der Werkzeugzone lässt sich vollständig ins Sichtfeld scrollen. */
    @Test @Config(qualifiers = "de-w393dp-h852dp", fontScale = 2.0f) fun everythingReachableByScrollingAtLargeFont() {
        open()
        val problems = mutableListOf<String>()
        for (tab in listOf("ADJUST", "CROP", "FILTER", "MARKUP", "RETOUCH", "FRAME", "RESIZE")) {
            click("tab.$tab")
            val zone = full(nodes("editor.toolzone").first())
            val tags = rule.onAllNodes(hasAnyAncestor(hasTestTag("editor.toolzone")) and SemanticsMatcher("getestet") {
                it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.TestTag) &&
                    (it.config.contains(SemanticsActions.OnClick) || it.config.contains(SemanticsActions.SetProgress)) }, useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }.distinct()
            for (t in tags) {
                val node = rule.onAllNodes(hasTestTag(t) and hasAnyAncestor(hasTestTag("editor.toolzone")), useUnmergedTree = true)[0]
                // Waagrechte Reihen scrollen ebenfalls: performScrollTo bringt das Element in beiden Richtungen ins Bild.
                runCatching { node.performScrollTo() }; settle()
                // performScrollTo bewegt nur den nächsten Scroll-Elternteil (z. B. eine waagrechte Reihe) – dann zusätzlich die Zone selbst senkrecht.
                var r = full(node.fetchSemanticsNode())
                val delta = when { r.bottom > zone.bottom -> r.bottom - zone.bottom; r.top < zone.top -> r.top - zone.top; else -> 0f }
                if (delta != 0f) {
                    rule.onAllNodes(androidx.compose.ui.test.hasParent(hasTestTag("editor.toolzone")) and SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy),
                        useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
                    settle(); r = full(node.fetchSemanticsNode())
                }
                if (r.height > zone.height + 1f) continue // höher als die Zone selbst (z. B. Raster) – nur Anfang prüfbar
                if (!inside(Rect(zone.left, r.top, zone.right, r.bottom), zone)) problems += "$tab/$t ${dp(r)} nicht vollständig erreichbar (Zone ${dp(zone)})"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Gewählter Navigationseintrag liegt vollständig im sichtbaren Bereich der Leiste (holt sich selbst ins Bild). */
    private fun selectedDockEntryVisible() {
        open()
        val problems = mutableListOf<String>()
        val dock = full(nodes("editor.tabs").first())
        for (tab in listOf("CROP", "ADJUST", "FILTER", "MARKUP", "MORE", "CROP")) {
            click("tab.$tab")
            val n = rule.onAllNodes(hasTestTag("tab.$tab") and hasAnyAncestor(hasTestTag("editor.tabs")), useUnmergedTree = true).fetchSemanticsNodes().first()
            val r = full(n)
            if (!inside(r, dock)) problems += "Navigation: $tab ${dp(r)} nicht vollständig sichtbar (Leiste ${dp(dock)})"
            if (n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Selected) { false } != true) problems += "Navigation: $tab nicht als gewählt gemeldet"
        }
        // Unter „Mehr“: gewählter Unterreiter ebenfalls vollständig in der Werkzeugzone.
        for (tab in listOf("RESIZE", "FRAME", "RETOUCH")) {
            click("tab.$tab")
            val zone = full(nodes("editor.toolzone").first())
            val r = full(rule.onAllNodes(hasTestTag("tab.$tab") and hasAnyAncestor(hasTestTag("editor.toolzone")), useUnmergedTree = true).fetchSemanticsNodes().first())
            if (!inside(r, zone)) problems += "Unterreiter $tab ${dp(r)} nicht vollständig sichtbar (Zone ${dp(zone)})"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test @Config(qualifiers = "de-w393dp-h852dp", fontScale = 2.0f) fun selectedDockEntryVisibleLargeFont() = selectedDockEntryVisible()
    @Test @Config(qualifiers = "ar-w393dp-h852dp", fontScale = 1.5f) fun selectedDockEntryVisibleArabic() = selectedDockEntryVisible()
    @Test @Config(qualifiers = "de-w360dp-h640dp", fontScale = 1.3f) fun selectedDockEntryVisibleSmallWindow() = selectedDockEntryVisible()
}
