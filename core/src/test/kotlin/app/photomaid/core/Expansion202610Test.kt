package app.photomaid.core

import app.photomaid.core.ads.FeedAdPolicy
import app.photomaid.core.ads.FeedAdState
import app.photomaid.core.collections.CollectionFilter
import app.photomaid.core.collections.CollectionSource
import app.photomaid.core.collections.SmartCollections
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionError
import app.photomaid.core.decisions.DecisionRules
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.feed.FeedIntent
import app.photomaid.core.feed.FeedOrder
import app.photomaid.core.feed.FeedOrdering
import app.photomaid.core.feed.FeedSession
import app.photomaid.core.later.LaterGroup
import app.photomaid.core.later.LaterStatus
import app.photomaid.core.later.ReviewQueues
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.core.organize.PhotoSeries
import app.photomaid.core.progress.MonthKey
import app.photomaid.core.progress.MonthState
import app.photomaid.core.progress.ProgressRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Ausbau 2026-10: Werbeabstand, Später ohne Termin, Serien, Monate, Reihenfolge, Session-Hilfen. */
class Expansion202610Test {
    private val today = LocalDay(2026, 10, 2)

    // 0.2 Werbeabstand
    @Test fun adCardNeedsSixtySecondsGapOnTopOfInterval() {
        val shown = FeedAdPolicy.onCardShown(FeedAdState(decisionsTotal = 40, sinceLastAd = 7), today, nowMs = 1_000_000)
        val afterSeven = (1..7).fold(shown) { s, _ -> FeedAdPolicy.onDecision(s) }
        assertFalse("Takt erreicht, aber erst 30 s vergangen", FeedAdPolicy.isDue(afterSeven, today, nowMs = 1_030_000))
        assertTrue(FeedAdPolicy.isDue(afterSeven, today, nowMs = 1_060_000))
        // Uhr zurückgestellt: sperrt nicht dauerhaft.
        assertTrue(FeedAdPolicy.isDue(afterSeven, today, nowMs = 500_000))
        // Abstand allein reicht nicht: Takt bleibt Pflicht.
        assertFalse(FeedAdPolicy.isDue(FeedAdPolicy.onDecision(shown), today, nowMs = 2_000_000))
        // Erste Karte überhaupt: kein Abstand nötig.
        assertTrue(FeedAdPolicy.isDue(FeedAdState(decisionsTotal = 40, sinceLastAd = 7), today, nowMs = 1))
    }

    // 1.5 Später ohne Termin
    @Test fun bookmarkIsLaterWithoutDateAndNeverReturnsOnItsOwn() = runBlocking {
        val l = makeLedger()
        val s = ReviewSessionID()
        l.decide(photo(1), DecisionAction.Later(s), s)
        l.decide(photo(1), DecisionAction.Bookmark)
        val r = l.record(photo(1))
        assertEquals(Disposition.LATER, r.disposition)
        assertTrue(r.bookmarked)
        assertNull(r.dueDay)
        assertEquals(LaterStatus.Bookmarked, ReviewQueues.laterStatus(r, today))
        // Weder normale Warteschlange (auch nicht in einer neuen Sitzung) noch „wieder fällig“.
        assertTrue(ReviewQueues.normalQueue(listOf(photo(1)), mapOf(photo(1) to r), ReviewSessionID(), today).isEmpty())
        assertTrue(ReviewQueues.dueQueue(listOf(r), ReviewSessionID(), LocalDay(2030, 1, 1)).isEmpty())
        assertEquals(LaterGroup.Bookmarked, ReviewQueues.laterGroups(listOf(r), today).single().first)
        // „Zurück in den Stapel“ = wieder offen.
        l.decide(photo(1), DecisionAction.RemoveDeferral)
        assertEquals(Disposition.UNDECIDED, l.record(photo(1)).disposition)
        assertFalse(l.record(photo(1)).bookmarked)
    }

    @Test fun scheduleClearsBookmarkAndBookmarkNeedsLater() {
        val bookmarked = ItemRecord(photo(1), 3, Disposition.LATER, null, null, bookmarked = true)
        val scheduled = DecisionRules.transition(bookmarked, DecisionAction.Schedule(today.addingDays(3))).getOrThrow()
        assertFalse(scheduled.bookmarked)
        assertEquals(today.addingDays(3), scheduled.dueDay)
        val err = DecisionRules.transition(ItemRecord(photo(2)), DecisionAction.Bookmark).exceptionOrNull()
        assertTrue(err is DecisionError.InvalidTransition)
    }

    @Test fun undoOfLaterWithBookmarkRestoresOpenItem() = runBlocking {
        val l = makeLedger()
        val s = ReviewSessionID()
        val later = l.decide(photo(1), DecisionAction.Later(s), s)
        l.apply(app.photomaid.core.decisions.DecisionCommand(item = photo(1), expectedRevision = later.record.revision,
            action = DecisionAction.Bookmark, amends = l.events().last().commandID))
        l.undo()
        val r = l.record(photo(1))
        assertEquals(Disposition.UNDECIDED, r.disposition)
        assertFalse(r.bookmarked)
    }

    // 2 Serien
    @Test fun seriesNeedSameFolderShortGapsAndThreeMembers() {
        val t0 = Instant.parse("2026-09-01T10:00:00Z")
        fun m(id: String, s: Long, folder: String? = "DCIM/Camera/") = PhotoSeries.Member(id, folder, t0.plusSeconds(s))
        val items = listOf(
            m("a", 0), m("b", 4), m("c", 12), m("d", 19), // Kette mit Abständen ≤ 10 s → eine Serie (4)
            m("e", 60), m("f", 65),                      // nur 2 → keine Serie
            m("g", 0, "Pictures/WhatsApp/"), m("h", 1, "Pictures/WhatsApp/"), m("i", 2, "pictures/whatsapp"), // gleicher Ordner (Schreibweise egal)
            PhotoSeries.Member("x", "DCIM/Camera/", null), PhotoSeries.Member("y", null, t0),
        )
        val g = PhotoSeries.groups(items)
        assertEquals(listOf(listOf("a", "b", "c", "d"), listOf("g", "h", "i")).map { it.toSet() }.toSet(), g.map { s -> s.map { it.id }.toSet() }.toSet())
        assertTrue(PhotoSeries.index(items)["e"] == null)
        assertEquals(4, PhotoSeries.index(items)["c"]!!.size)
        // Abstand > 10 s trennt.
        assertTrue(PhotoSeries.groups(listOf(m("p", 0), m("q", 11), m("r", 22))).isEmpty())
    }

    // 1.1 Monate
    @Test fun monthProgressCountsRealDecisionsOnly() {
        val zone = ZoneId.of("Europe/Zurich")
        val sep = Instant.parse("2026-09-15T10:00:00Z"); val aug = Instant.parse("2026-08-31T22:30:00Z") // = 1. Sept. 00:30 in Zürich
        val jul = Instant.parse("2026-07-10T10:00:00Z")
        val s = ReviewSessionID()
        val recs = mapOf(
            "k" to ItemRecord(photo(1), 1, Disposition.KEPT),
            "t" to ItemRecord(photo(2), 1, Disposition.MARKED_FOR_CLEANUP),
            "l" to ItemRecord(photo(3), 1, Disposition.LATER, today.addingDays(2)),
            "b" to ItemRecord(photo(4), 1, Disposition.LATER, null, bookmarked = true),
            "s" to ItemRecord(photo(5), 1, Disposition.LATER, null, s), // nur übersprungen → offen
            "r" to ItemRecord(photo(6), 1, Disposition.REMOVED),        // noch vorhanden = zurückgeholt → offen
        )
        val items = listOf("k" to sep, "t" to sep, "l" to sep, "b" to aug, "s" to sep, "r" to sep, "u" to sep, "j" to jul)
        val months = ProgressRules.months(items, { it.second }, { recs[it.first] }, zone)
        assertEquals(listOf(MonthKey(2026, 9), MonthKey(2026, 7)), months.map { it.month })
        val september = months.first()
        assertEquals(8 - 1, september.total) // 7 Medien im September (inkl. 1. Sept. 00:30 Ortszeit)
        assertEquals(4, september.reviewed) // behalten, Papierkorb, mit Termin, beiseitegelegt
        assertEquals(2, september.later)
        assertEquals(MonthState.IN_PROGRESS, september.state)
        assertEquals(MonthState.NOT_STARTED, months.last().state)
        assertEquals(MonthKey(2026, 9), MonthKey.parse("2026-09"))
        assertEquals("2026-09", MonthKey(2026, 9).toString())
        assertNull(MonthKey.parse("kaputt"))
    }

    // 3.3 Reihenfolge
    @Test fun orderingKeepsUnknownLastAndShuffleIsStable() {
        data class A(val id: String, val d: Instant?, val size: Long?)
        val t = Instant.parse("2026-09-01T00:00:00Z")
        val list = listOf(A("1", t, 5), A("2", t.plusSeconds(10), null), A("3", null, 9), A("4", t.minusSeconds(10), 7))
        fun ids(o: FeedOrder, seed: Long = 1) = FeedOrdering.apply(list, o, { it.d }, { it.size }, { it.id }, seed).map { it.id }
        assertEquals(listOf("2", "1", "4", "3"), ids(FeedOrder.NEWEST))
        assertEquals(listOf("4", "1", "2", "3"), ids(FeedOrder.OLDEST))
        assertEquals(listOf("3", "4", "1", "2"), ids(FeedOrder.LARGEST))
        assertEquals(ids(FeedOrder.RANDOM, 42), ids(FeedOrder.RANDOM, 42))
        assertEquals(list.map { it.id }.toSet(), ids(FeedOrder.RANDOM, 42).toSet())
    }

    // Session-Hilfen (Ordner ausnehmen, Serienauswahl, Reihenfolge-Wechsel): nie eine Entscheidung
    @Test fun sessionDropAndReorderOnlyTouchUpcoming() {
        val items = (1..6).map { photo(it) }
        val session = FeedSession(items = items)
        session.jump(2)
        session.dropUpcoming(setOf(photo(1), photo(3), photo(5)))
        assertEquals(listOf(photo(1), photo(2), photo(4), photo(6)), session.items) // photo(1) schon gesehen: bleibt
        assertEquals(photo(4), session.current)
        session.reorderUpcoming { it.reversed() }
        assertEquals(listOf(photo(1), photo(2), photo(6), photo(4)), session.items)
        session.reorderUpcoming { it.drop(1) } // unvollständige Liste wird ignoriert
        assertEquals(4, session.items.size)
        session.recordDecision(photo(6), FeedIntent.MARK_FOR_CLEANUP)
        session.recordDecision(photo(4), FeedIntent.PREVIOUS)
        assertEquals(1, session.summary.marked)
        assertEquals(1, session.summary.reviewed)
    }

    // 0.6 Fertige Sammlung
    @Test fun photosOlderThanOneYearIsBuiltIn() {
        assertTrue(SmartCollections.builtIns.any { it.source == CollectionSource.PHOTOS && it.filter == CollectionFilter.OlderThan(365) && it.builtIn })
    }
}
