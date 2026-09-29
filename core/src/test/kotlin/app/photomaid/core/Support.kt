package app.photomaid.core

import app.photomaid.core.decisions.CommitOutcome
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.DecisionCommand
import app.photomaid.core.decisions.DecisionLedger
import app.photomaid.core.decisions.InMemoryLedgerPersistence
import app.photomaid.core.duplicates.ByteSourceProvider
import app.photomaid.core.model.Clock
import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.ReviewSessionID
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class TestClock(iso: String, tz: String = "Europe/Zurich") : Clock {
    @Volatile private var _zone: ZoneId = ZoneId.of(tz)
    @Volatile private var _now: Instant = date(iso, tz)
    override val now: Instant get() = _now
    override val zone: ZoneId get() = _zone

    fun set(iso: String) { _now = date(iso, _zone.id) }
    fun advance(seconds: Long) { _now = _now.plusSeconds(seconds) }
    fun setTimeZone(id: String) { _zone = ZoneId.of(id) }

    companion object {
        /** "2026-03-28T23:30" in der angegebenen Zeitzone. */
        fun date(iso: String, tz: String): Instant = LocalDateTime.parse(iso).atZone(ZoneId.of(tz)).toInstant()
    }
}

fun photo(n: Int) = ItemReference.photo("QA-$n")

fun makeLedger(
    clock: TestClock = TestClock("2026-06-10T10:00"),
    persistence: InMemoryLedgerPersistence = InMemoryLedgerPersistence(),
) = DecisionLedger(persistence, clock)

suspend fun DecisionLedger.decide(item: ItemReference, action: DecisionAction, session: ReviewSessionID? = null): CommitOutcome {
    val r = record(item)
    return apply(DecisionCommand(item = item, expectedRevision = r.revision, action = action), session)
}

class MemoryByteSource : ByteSourceProvider {
    val contents = mutableMapOf<ItemReference, ByteArray>()
    var opened = 0
    override fun openStream(item: ItemReference): InputStream? {
        val d = contents[item] ?: return null
        opened++
        return ByteArrayInputStream(d)
    }
}

/** Deterministischer Zufallsgenerator (SplitMix64, identisch zu iOS `SeededRNG`). */
class SeededRNG(seed: Long) {
    private var state = seed + -0x61c8864680b583ebL
    fun next(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
    fun nextInt(bound: Int): Int = java.lang.Long.remainderUnsigned(next(), bound.toLong()).toInt()
    fun <T> pick(list: List<T>): T = list[nextInt(list.size)]
}

inline fun <reified T : Throwable> assertThrowsSuspend(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("expected ${T::class.simpleName}, got $e", e)
    }
    throw AssertionError("expected ${T::class.simpleName}, nothing thrown")
}
