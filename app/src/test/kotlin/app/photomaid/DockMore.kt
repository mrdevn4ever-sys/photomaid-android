package app.photomaid

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction

/**
 * Editor-Navigation „vier Hauptwerkzeuge + Mehr“: Werkzeuge unter „Mehr“ erscheinen erst nach dem Antippen von „Mehr“
 * als Unter-Tabs (gleiche Kennung `tab.X` / `ctab.X`). Tests tippen „Mehr“ an, wenn das Ziel gerade nicht sichtbar ist.
 */
fun ComposeTestRule.openMoreFor(tag: String, settle: () -> Unit) {
    val prefix = when { tag.startsWith("tab.") -> "tab."; tag.startsWith("ctab.") -> "ctab."; else -> return }
    if (tag == "${prefix}MORE" || onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return
    val more = onAllNodesWithTag("${prefix}MORE", useUnmergedTree = true)
    if (more.fetchSemanticsNodes().isEmpty()) return
    more[0].performSemanticsAction(SemanticsActions.OnClick); settle()
}
