package app.photomaid.core.duplicates

import app.photomaid.core.model.ItemReference
import app.photomaid.core.model.KnownSize
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Liefert Byte-Ströme für vergleichbare Ressourcen. Nur lokal verfügbare Bytes;
 * nichts wird heimlich heruntergeladen (dann `null`).
 */
fun interface ByteSourceProvider {
    fun openStream(item: ItemReference): InputStream?
}

/** Kandidat für die Duplikatprüfung. Nur vergleichbare Kandidaten dürfen als identisch gelten. */
data class DuplicateCandidate(
    val item: ItemReference,
    val size: KnownSize,
    /** `false` für Motion-/Burst-/bearbeitete Medien u. ä.: werden separat gelistet. */
    val isComparable: Boolean = true,
    /** Gleiche physische Ressource über mehrere Quellen. */
    val aliasKey: String? = null,
)

/** Hash-bestätigte Gruppe identischer Bytes. */
data class DuplicateGroup(val sha256: String, val sizeEach: Long, val members: List<ItemReference>) {
    val id: String get() = sha256

    /** Alle identischen Dateien zusammen (z. B. 3 × 14 MB = 42 MB). */
    val totalBytes: Long get() = sizeEach * members.size

    /** Zusätzliche Kopien, wenn genau eine bleibt. Keine garantierte Ersparnis. */
    val extraCopiesBytes: Long get() = sizeEach * maxOf(0, members.size - 1)

    fun selectedBytes(selection: Set<ItemReference>): Long = sizeEach * selection.intersect(members.toSet()).size

    /** Mindestens eine Kopie bleibt erhalten. */
    fun isValidSelection(selection: Set<ItemReference>): Boolean =
        members.toSet().containsAll(selection) && selection.intersect(members.toSet()).size < members.size

    /** Standardauswahl: alle außer der zu behaltenden Kopie. */
    fun defaultSelection(keeping: ItemReference): Set<ItemReference> = members.filter { it != keeping }.toSet()
}

data class DuplicateScanResult(
    val groups: List<DuplicateGroup> = emptyList(),
    /** Unbekannte Größe oder keine lokal verfügbaren Bytes. */
    val notChecked: List<ItemReference> = emptyList(),
    val notComparable: List<ItemReference> = emptyList(),
    /** Nullbyte-Dateien sind trivial gleich und werden nicht als Gruppe gewertet. */
    val zeroByte: List<ItemReference> = emptyList(),
    val hashedCount: Int = 0,
)

data class DuplicateScanProgress(val processed: Int, val total: Int)

/**
 * Deterministische, unterbrechbare Pipeline ohne KI:
 * 1. Inventar, 2. bekannte Größen als Filter, 3. Teilhash nur zum Aussortieren,
 * 4. vollständiges Streaming-SHA-256. Kein Full-Hash ohne Kandidatenfilter.
 *
 * @param isCancelled wird regelmäßig geprüft; `true` bricht mit [CancellationException] ab.
 */
class DuplicateFinder(
    var chunkSize: Int = 1 shl 20,
    var partialHashBytes: Int = 64 * 1024,
) {
    fun scan(
        candidates: List<DuplicateCandidate>,
        source: ByteSourceProvider,
        isCancelled: () -> Boolean = { false },
        progress: ((DuplicateScanProgress) -> Unit)? = null,
    ): DuplicateScanResult {
        val groups = mutableListOf<DuplicateGroup>()
        val notChecked = mutableListOf<ItemReference>()
        val notComparable = mutableListOf<ItemReference>()
        val zeroByte = mutableListOf<ItemReference>()
        var hashed = 0
        val bySize = linkedMapOf<Long, MutableList<DuplicateCandidate>>()
        val aliasSeen = mutableSetOf<String>()

        for (c in candidates) {
            if (!c.isComparable) { notComparable.add(c.item); continue }
            // Dieselbe physische Ressource nicht als eigene Kopie zählen.
            if (c.aliasKey != null && !aliasSeen.add(c.aliasKey)) continue
            when (val s = c.size) {
                KnownSize.Unknown -> notChecked.add(c.item)
                is KnownSize.Known -> if (s.bytes == 0L) zeroByte.add(c.item) else bySize.getOrPut(s.bytes) { mutableListOf() }.add(c)
            }
        }
        val buckets = bySize.filter { it.value.size > 1 }
        val total = buckets.values.sumOf { it.size }
        var processed = 0

        for ((size, bucket) in buckets.entries.sortedByDescending { it.key }) {
            check(isCancelled)
            val byPartial = linkedMapOf<String, MutableList<DuplicateCandidate>>()
            for (c in bucket) {
                check(isCancelled)
                val h = hash(c.item, source, partialHashBytes, isCancelled)
                if (h != null) byPartial.getOrPut(h) { mutableListOf() }.add(c) else notChecked.add(c.item)
            }
            for ((partial, sub) in byPartial) {
                if (sub.size < 2) continue
                if (size <= partialHashBytes) {
                    // Teilhash deckte die ganze Datei ab: bereits der vollständige SHA-256.
                    hashed += sub.size
                    groups.add(DuplicateGroup(partial, size, sub.map { it.item }.sortedBy { it.description }))
                    continue
                }
                val byFull = linkedMapOf<String, MutableList<ItemReference>>()
                for (c in sub) {
                    check(isCancelled)
                    val h = hash(c.item, source, null, isCancelled)
                    if (h != null) { byFull.getOrPut(h) { mutableListOf() }.add(c.item); hashed++ } else notChecked.add(c.item)
                }
                for ((h, members) in byFull) if (members.size > 1) {
                    groups.add(DuplicateGroup(h, size, members.sortedBy { it.description }))
                }
            }
            processed += bucket.size
            progress?.invoke(DuplicateScanProgress(processed, total))
        }
        groups.sortWith(compareByDescending<DuplicateGroup> { it.extraCopiesBytes }.thenByDescending { it.sha256 })
        return DuplicateScanResult(groups, notChecked, notComparable, zeroByte, hashed)
    }

    /** SHA-256 der ersten `limit` Bytes (oder aller). `null`, wenn keine lokalen Bytes verfügbar. */
    fun hash(item: ItemReference, source: ByteSourceProvider, limit: Int?, isCancelled: () -> Boolean = { false }): String? {
        val stream = source.openStream(item) ?: return null
        stream.use { s ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(chunkSize)
            var remaining = limit?.toLong() ?: Long.MAX_VALUE
            while (remaining > 0) {
                check(isCancelled)
                val n = s.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                md.update(buf, 0, n)
                remaining -= n
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /** Vor einer riskanten Aktion: Bytes erneut vollständig vergleichen. */
    fun bytesIdentical(a: ItemReference, b: ItemReference, source: ByteSourceProvider): Boolean {
        val sa = source.openStream(a) ?: return false
        val sb = source.openStream(b) ?: run { sa.close(); return false }
        sa.use { x ->
            sb.use { y ->
                val ba = ByteArray(chunkSize)
                val bb = ByteArray(chunkSize)
                while (true) {
                    val na = x.readNBytes(ba, 0, ba.size)
                    val nb = y.readNBytes(bb, 0, bb.size)
                    if (na != nb) return false
                    if (na == 0) return true
                    if (!ba.copyOf(na).contentEquals(bb.copyOf(nb))) return false
                }
            }
        }
    }

    private fun check(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("duplicate scan cancelled")
    }
}
