package app.photomaid.core

import app.photomaid.core.library.RawPairs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPairsTest {
    private data class F(val name: String?, val folder: String?, val mime: String?)

    @Test fun pairsSameNameSameFolderOnly() {
        val jpg = F("IMG_0042.JPG", "DCIM/Camera/", "image/jpeg")
        val dng = F("IMG_0042.dng", "DCIM/Camera", "image/x-adobe-dng")
        val otherFolder = F("IMG_0042.JPG", "Pictures/Kopie/", "image/jpeg")
        val lonelyRaw = F("IMG_0050.CR3", "DCIM/Camera/", null)
        val heic = F("DSC1.HEIC", "DCIM/Camera/", "image/heic")
        val arw = F("DSC1.ARW", "DCIM/Camera/", "image/x-sony-arw")
        val p = RawPairs.pairs(listOf(jpg, dng, otherFolder, lonelyRaw, heic, arw), { it.name }, { it.folder }, { it.mime })
        assertEquals(mapOf(jpg to dng, heic to arw), p)
    }

    @Test fun recognisesRawByExtensionOrMime() {
        assertTrue(RawPairs.isRaw("a.NEF", null))
        assertTrue(RawPairs.isRaw("a", "image/x-adobe-dng"))
        assertTrue(RawPairs.isRaw("a.bin", "image/x-canon-cr2-raw".replace("cr2-raw", "raw")))
        assertFalse(RawPairs.isRaw("a.jpg", "image/jpeg"))
        assertFalse(RawPairs.isRaw(null, null))
        assertTrue(RawPairs.pairs(listOf(F(null, null, null)), { it.name }, { it.folder }, { it.mime }).isEmpty())
    }
}
