package app.photomaid

import androidx.compose.ui.unit.dp
import app.photomaid.design.EditorLayoutPolicy
import app.photomaid.design.EditorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editor-Redesign: eine Layout-Policy für alle Editoren – nur Fenster, Schrift und Tastatur zählen, nie das Medium. */
class EditorLayoutPolicyTest {
    @Test fun sameWindowGivesSameGeometryAndStageGetsTheLargestShare() {
        val a = EditorLayoutPolicy.compute(393.dp, 820.dp, 1f)
        val b = EditorLayoutPolicy.compute(393.dp, 820.dp, 1f)
        assertEquals(a, b)
        assertEquals(EditorMode.STACKED, a.mode)
        // Bühne ist der größte Block (größer als Werkzeugzone + Navigation + Hilfsleiste zusammen).
        assertTrue("Bühne ${a.stageHeight}", a.stageHeight > a.tool + a.dock + a.actions)
        assertEquals(820.dp, a.header + a.stageHeight + a.actions + a.tool + a.dock)
    }

    @Test fun largeFontAndSmallWindowsKeepAUsableStage() {
        for ((w, h) in listOf(360.dp to 640.dp, 360.dp to 560.dp, 393.dp to 820.dp)) for (fs in listOf(1f, 1.5f, 2f)) {
            val l = EditorLayoutPolicy.compute(w, h, fs)
            assertTrue("$w×$h @$fs: Bühne ${l.stageHeight}", l.stageHeight >= EditorLayoutPolicy.minStage)
            assertTrue("$w×$h @$fs: Werkzeugzone ${l.tool}", l.tool >= 96.dp)
            assertEquals(h, l.header + l.stageHeight + l.actions + l.tool + l.dock)
        }
        // Größere Schrift: Kopfzeile und Navigation wachsen, die Bühne gibt nach – aber kontrolliert.
        assertTrue(EditorLayoutPolicy.compute(393.dp, 820.dp, 2f).dock > EditorLayoutPolicy.compute(393.dp, 820.dp, 1f).dock)
    }

    @Test fun wideLowWindowsPutToolsBesideTheStage() {
        val l = EditorLayoutPolicy.compute(852.dp, 393.dp, 1f)
        assertEquals(EditorMode.SIDE, l.mode)
        assertTrue(l.inspectorWidth >= 340.dp)
        assertEquals(852.dp, l.stageWidth + l.inspectorWidth)
        assertEquals(393.dp - l.header, l.stageHeight)
        // Ein normales Hochformat-Fenster bleibt gestapelt.
        assertEquals(EditorMode.STACKED, EditorLayoutPolicy.compute(600.dp, 960.dp, 1f).mode)
    }

    @Test fun keyboardMovesToolsAboveItAndRestores() {
        val before = EditorLayoutPolicy.compute(393.dp, 820.dp, 1f)
        val kb = EditorLayoutPolicy.compute(393.dp, 820.dp, 1f, ime = 300.dp)
        assertTrue(kb.keyboard)
        assertEquals(0.dp, kb.actions); assertEquals(0.dp, kb.dock)
        assertEquals(820.dp - 300.dp, kb.header + kb.stageHeight + kb.tool)
        assertTrue("Bühne mit Tastatur ${kb.stageHeight}", kb.stageHeight >= EditorLayoutPolicy.minStage)
        // Tastatur zu → wieder exakt die vorherige Geometrie.
        assertEquals(before, EditorLayoutPolicy.compute(393.dp, 820.dp, 1f, ime = 0.dp))
        assertFalse(EditorLayoutPolicy.compute(852.dp, 393.dp, 1f, ime = 200.dp).mode == EditorMode.SIDE)
    }
}
