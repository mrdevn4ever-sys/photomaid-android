package app.photomaid.core

import app.photomaid.core.cleanup.CleanupOperationKind
import app.photomaid.core.cleanup.OperationIntent
import app.photomaid.core.cleanup.OperationState
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionEvent
import app.photomaid.core.decisions.DecisionSnapshot
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.decisions.HistoryKind
import app.photomaid.core.decisions.ItemRecord
import app.photomaid.core.model.CommandID
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.OperationID
import app.photomaid.core.model.PhotoMaidJson
import app.photomaid.core.model.ReviewSessionID
import app.photomaid.core.rules.ReviewRule
import app.photomaid.core.rules.RuleFilter
import app.photomaid.core.rules.RuleSource
import app.photomaid.core.rules.RuleTrigger
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Android-Zusatz: persistierte Payloads überleben Neustart (JSON-Roundtrip, stabile Identitäten). */
class SerializationTest {
    @Test fun recordsEventsIntentsRoundTrip() {
        val s = ReviewSessionID()
        val r = ItemRecord(file("Download/a.pdf"), 3, Disposition.LATER, LocalDay(2026, 7, 1), s)
        assertEquals(r, PhotoMaidJson.decodeFromString<ItemRecord>(PhotoMaidJson.encodeToString(r)))
        val e = DecisionEvent(commandID = CommandID(), item = photo(1), kind = HistoryKind.DECISION,
            from = DecisionSnapshot(Disposition.UNDECIDED), to = DecisionSnapshot(Disposition.KEPT), revision = 1,
            at = Instant.ofEpochMilli(1_780_000_000_000), sessionID = s)
        assertEquals(e, PhotoMaidJson.decodeFromString<DecisionEvent>(PhotoMaidJson.encodeToString(e)))
        val i = OperationIntent(OperationID(), CleanupOperationKind.PHOTO_LIBRARY_DELETE, mapOf(photo(1) to 2, file("x") to 1),
            LocalDay(2026, 6, 10), 2, OperationState.SUBMITTED, Instant.ofEpochMilli(1))
        assertEquals(i, PhotoMaidJson.decodeFromString<OperationIntent>(PhotoMaidJson.encodeToString(i)))
        val rule = ReviewRule(source = RuleSource.ConnectedSource(SOURCE_ID), filter = RuleFilter.KnownSizeAtLeast(5), trigger = RuleTrigger.Weekly(2))
        assertEquals(rule, PhotoMaidJson.decodeFromString<ReviewRule>(PhotoMaidJson.encodeToString(rule)))
        val a: DecisionAction = DecisionAction.Schedule(LocalDay(2026, 1, 1))
        assertEquals(a, PhotoMaidJson.decodeFromString<DecisionAction>(PhotoMaidJson.encodeToString(a)))
    }

    private val SOURCE_ID = (file("x").provider as app.photomaid.core.model.ItemProvider.ConnectedSource).id
}
