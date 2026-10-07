package app.photomaid

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.photomaid.feature.settings.HelpScreen
import app.photomaid.feature.settings.SettingsScreen
import app.photomaid.navigation.Routes
import app.photomaid.design.AppearanceChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Funktionstest X4: jede Einstellung wirkt wirklich (Schalter, Auswahl, Bestätigung bei „Endgültig löschen“, Rundgang zurücksetzen).
 * Keine Einstellung trifft eine Entscheidung: das Ledger bleibt leer.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w393dp-h2400dp")
class SettingsFunctionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val nav = RecordingNav()
    private fun s(id: Int) = rule.activity.getString(id)

    private fun waitFor(cond: () -> Boolean) = rule.waitUntil(30_000) { cond() }

    @Test fun everySettingChangesItsValue() {
        val app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 4) })
        rule.setContent { TestHost(app) { SettingsScreen(nav) } }
        val before = app.settings.value

        fun toggle(title: Int) = rule.onNode(hasText(s(title)) and hasClickAction()).performScrollTo().performClick()

        toggle(R.string.set_protect_favorites); waitFor { app.settings.value.protectFavorites != before.protectFavorites }
        toggle(R.string.set_haptics); waitFor { app.settings.value.haptics != before.haptics }
        toggle(R.string.set_hints); waitFor { app.settings.value.showGestureHintOnOpen != before.showGestureHintOnOpen }
        toggle(R.string.set_buttons); waitFor { app.settings.value.decisionButtons != before.decisionButtons }
        rule.onNodeWithTag("settings.buttons").assertIsOn()

        // „Endgültig löschen“: erst die Erklärung, Abbrechen lässt es aus; Bestätigen schaltet ein; Ausschalten sofort.
        assertFalse(before.deletePermanently)
        toggle(R.string.set_delete_permanently); rule.waitForIdle()
        rule.onNodeWithText(s(R.string.set_delete_permanently_confirm_title)).assertExists()
        assertFalse(app.settings.value.deletePermanently)
        rule.onNodeWithText(s(R.string.set_delete_permanently_confirm_action)).performClick()
        waitFor { app.settings.value.deletePermanently }
        rule.onNodeWithTag("settings.deletePermanently").assertIsOn()
        toggle(R.string.set_delete_permanently); waitFor { !app.settings.value.deletePermanently }
        rule.onNodeWithTag("settings.deletePermanently").assertIsOff()

        // Auswahl: Standardtermin für „Später“ und Erscheinungsbild.
        rule.onNode(hasText(s(R.string.set_later_default), substring = true) and hasClickAction()).performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.resources.getQuantityString(R.plurals.set_in_days, 7, 7)).performClick()
        waitFor { app.settings.value.laterDefaultDays == 7 }
        rule.onNode(hasText(s(R.string.set_appearance), substring = true) and hasClickAction()).performScrollTo().performClick()
        rule.onNodeWithText(s(R.string.appearance_dark)).performClick()
        waitFor { app.settings.value.appearance == AppearanceChoice.DARK }

        // Einstiege öffnen nur Ansichten.
        rule.onNodeWithTag("settings.excluded").performScrollTo().performClick()
        assertTrue("push:${Routes.EXCLUDED}" in nav.events)
        rule.onNodeWithText(s(R.string.help)).performScrollTo().performClick()
        assertTrue("push:${Routes.HELP}" in nav.events)
        assertEquals(0, app.ledger.stateFlow.value.records.size)
    }

    @Test fun helpResetsEveryTour() {
        val app = makeTestApp(rule.activity, media = { FakeMediaLibrary(it, count = 4) })
        runBlocking { app.updateSettings { it.copy(todayTourSeen = true, feedTourSeen = true, monthsTourSeen = true, seriesTourSeen = true,
            gridTourSeen = true, editorTourSeen = true, gestureHintSeen = true) } }
        rule.setContent { TestHost(app) { HelpScreen(nav) } }
        rule.onNodeWithTag("help.tours").performClick()
        waitFor { !app.settings.value.todayTourSeen }
        val v = app.settings.value
        assertFalse(v.feedTourSeen || v.monthsTourSeen || v.seriesTourSeen || v.gridTourSeen || v.editorTourSeen || v.gestureHintSeen)
        rule.onNodeWithText(s(R.string.help_tours_reset)).assertExists()
    }
}
