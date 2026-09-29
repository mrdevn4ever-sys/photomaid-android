package app.photomaid.core

import app.photomaid.core.model.MediaKind
import app.photomaid.core.organize.MediaFolder
import app.photomaid.core.organize.MediaFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFoldersTest {
    @Test fun mediaStoreDirectoryRules() {
        assertTrue(MediaFolders.isAllowed(MediaKind.PHOTO, "Pictures/Urlaub/"))
        assertTrue(MediaFolders.isAllowed(MediaKind.PHOTO, "DCIM/Camera"))
        assertFalse(MediaFolders.isAllowed(MediaKind.PHOTO, "Movies/Clips/")) // Bilder nicht unter Movies
        assertTrue(MediaFolders.isAllowed(MediaKind.VIDEO, "Movies/Clips/"))
        assertFalse(MediaFolders.isAllowed(MediaKind.PHOTO, "Download/Sachen/"))
        assertFalse(MediaFolders.isAllowed(MediaKind.PHOTO, "Pictures/"))
        assertFalse(MediaFolders.isAllowed(MediaKind.PHOTO, "Pictures/.thumbnails/"))
        assertFalse(MediaFolders.isAllowed(MediaKind.FILE, "Pictures/A/"))
    }

    @Test fun newFolderNames() {
        assertEquals("Pictures/Urlaub 2026/", MediaFolders.newFolderPath("  Urlaub   2026 "))
        assertNull(MediaFolders.sanitizeName(""))
        assertNull(MediaFolders.sanitizeName("a/b"))
        assertNull(MediaFolders.sanitizeName(".versteckt"))
        assertNull(MediaFolders.sanitizeName("x".repeat(41)))
        assertNull(MediaFolders.sanitizeName("a:b"))
    }

    @Test fun choicesSortedFilteredAndSearchable() {
        val all = listOf(MediaFolder("DCIM/Camera/", 120), MediaFolder("Pictures/Urlaub/", 12), MediaFolder("Movies/Clips/", 40),
            MediaFolder("Pictures/Arbeit/", 12), MediaFolder("Download/", 3))
        assertEquals(listOf("Camera", "Arbeit", "Urlaub"), MediaFolders.choices(all, MediaKind.PHOTO).map { it.name })
        assertEquals(listOf("Camera", "Clips", "Arbeit", "Urlaub"), MediaFolders.choices(all, MediaKind.VIDEO).map { it.name })
        assertEquals(listOf("Urlaub"), MediaFolders.choices(all, MediaKind.PHOTO, "url").map { it.name })
    }
}
