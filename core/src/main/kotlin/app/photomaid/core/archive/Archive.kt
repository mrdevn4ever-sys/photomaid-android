package app.photomaid.core.archive

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Auslagern (Ausbau Paket 5): Fotos und Videos auf einen anderen Speicher kopieren, jede Kopie danach neu lesen und mit
 * dem Original vergleichen. Erst dann gilt sie als „geprüft“. Nichts hier löscht ein Original: die Bereinigung bleibt eine
 * eigene, ausdrückliche Entscheidung über den PhotoMaid-Papierkorb.
 *
 * Grenzen, die die App ehrlich sagt:
 * - Eine zweite Kopie auf demselben Gerätespeicher ist kein Auslagern ([TargetKind.SAME_DEVICE]).
 * - Ein Speicherort einer anderen App ([TargetKind.PROVIDER]) kann ein Online-Speicher sein – nie automatisch „lokal“.
 * - „Geprüft“ heißt: die Bytes am Ziel stimmen jetzt überein. Es ist keine Garantie, dass der Datenträger lange hält.
 */

/** Wo der gewählte Zielordner liegt. */
@Serializable
enum class TargetKind {
    /** Interner Gerätespeicher (wie die Fotos selbst) – eine Kopie dort ist kein Auslagern. */
    SAME_DEVICE,
    /** SD-Karte oder USB-Speicher (eigener Datenträger). */
    REMOVABLE,
    /** Speicherort einer anderen App (z. B. Online-Speicher); was dort passiert, entscheidet diese App. */
    PROVIDER;

    /** Nur bei einem eigenen Datenträger bietet PhotoMaid an, die geprüften Originale danach in den Papierkorb zu legen. */
    val allowsCleanupOffer: Boolean get() = this == REMOVABLE
}

object ArchiveTargets {
    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    /** Anbieter, deren Dateien auf dem internen Speicher liegen. */
    private val DEVICE_AUTHORITIES = setOf("com.android.providers.downloads.documents", "com.android.providers.media.documents")
    /** Wurzeln des internen Speichers beim System-Dateianbieter. */
    private val DEVICE_ROOTS = setOf("primary", "home")

    /** Einordnung über Anbieter und Dokument-ID der Ordner-Freigabe (`DocumentsContract.getTreeDocumentId`). */
    fun classify(authority: String?, treeDocumentId: String?): TargetKind = when {
        authority == EXTERNAL_STORAGE -> {
            val root = treeDocumentId?.substringBefore(':')?.lowercase()
            if (root == null || root.isEmpty() || root in DEVICE_ROOTS) TargetKind.SAME_DEVICE else TargetKind.REMOVABLE
        }
        authority in DEVICE_AUTHORITIES -> TargetKind.SAME_DEVICE
        else -> TargetKind.PROVIDER
    }

    /** Pfad unterhalb der Wurzel für die Anzeige („DCIM/Archiv“); leer = Wurzel. */
    fun displayPath(treeDocumentId: String?): String = treeDocumentId?.substringAfter(':', "")?.trim('/').orEmpty()
}

/** Ein zu kopierendes Medium (nur, was für Namen, Ordner und Prüfung nötig ist). */
data class ArchiveSource(
    val id: String,
    val name: String?,
    val mimeType: String?,
    /** Gemeldete Größe; `null` = unbekannt (nie 0). */
    val size: Long?,
    val takenMillis: Long?,
)

@Serializable
data class ArchiveOptions(
    /** Unterordner nach Jahr und Monat der Aufnahme („2026/10“). */
    val byMonth: Boolean = true,
    /** Prüfliste (CSV) mit Name, Datum, Größe, Prüfsumme und eigenen Infos in den Zielordner legen. */
    val writeList: Boolean = true,
)

object ArchivePlan {
    private val forbidden = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
    private const val MAX_NAME = 120

    /**
     * Unterordner für ein Medium. Ohne bekanntes Aufnahmedatum: ein eigener Ordner [undatedLabel] – nie ein erfundenes Datum.
     */
    fun folderFor(s: ArchiveSource, options: ArchiveOptions, undatedLabel: String, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        if (!options.byMonth) return emptyList()
        val t = s.takenMillis ?: return listOf(safeName(undatedLabel, "_"))
        val d = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        // Immer ASCII-Ziffern: Ordnernamen auf dem Datenträger hängen nie von der Gerätesprache ab (z. B. Arabisch).
        return listOf(String.format(Locale.ROOT, "%04d", d.year), String.format(Locale.ROOT, "%02d", d.monthValue))
    }

    /** Dateiname ohne Zeichen, die Speicher wie SD-Karten (FAT/exFAT) ablehnen; nie leer. */
    fun safeName(raw: String?, fallback: String): String {
        val cleaned = raw.orEmpty().replace(forbidden, "_").trim().trim('.').trim()
        val name = cleaned.ifEmpty { fallback }
        if (name.length <= MAX_NAME) return name
        val ext = extension(name)
        val base = name.dropLast(ext.length)
        return base.take(MAX_NAME - ext.length) + ext
    }

    /** „.jpg“ inklusive Punkt, sonst leer. */
    fun extension(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0 && i >= name.length - 6) name.substring(i) else ""
    }

    /** Fallback-Name, wenn das Medium keinen hat: „PhotoMaid_0007.jpg“ (Endung aus dem Dateityp). */
    fun fallbackName(index: Int, mimeType: String?): String = String.format(Locale.ROOT, "PhotoMaid_%04d%s", index + 1, extensionFor(mimeType))

    fun extensionFor(mime: String?): String = when (mime?.lowercase()) {
        "image/jpeg" -> ".jpg"; "image/png" -> ".png"; "image/heic" -> ".heic"; "image/heif" -> ".heif"; "image/webp" -> ".webp"
        "image/gif" -> ".gif"; "image/x-adobe-dng", "image/dng" -> ".dng"; "video/mp4" -> ".mp4"; "video/quicktime" -> ".mov"
        "video/3gpp" -> ".3gp"; "video/webm" -> ".webm"; "video/x-matroska" -> ".mkv"
        else -> ""
    }

    /** „IMG.jpg“ → „IMG (2).jpg“, solange der Name im Ordner vergeben ist (ohne Groß/klein). */
    fun uniqueName(taken: Set<String>, wanted: String): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (wanted.lowercase() !in lower) return wanted
        val ext = extension(wanted)
        val base = wanted.dropLast(ext.length)
        var i = 2
        while ("$base ($i)$ext".lowercase() in lower) i++
        return "$base ($i)$ext"
    }
}

/** Freier Platz am Ziel: nur gemessene Zahlen. Unbekannte Größen oder unbekannter Platz → [UNKNOWN], nie „passt“. */
enum class SpaceCheck { ENOUGH, NOT_ENOUGH, UNKNOWN }

object ArchiveSpace {
    fun check(sizes: List<Long?>, availableBytes: Long?): SpaceCheck {
        val known = sizes.filterNotNull().sum()
        if (availableBytes == null) return SpaceCheck.UNKNOWN
        if (known > availableBytes) return SpaceCheck.NOT_ENOUGH
        return if (sizes.any { it == null }) SpaceCheck.UNKNOWN else SpaceCheck.ENOUGH
    }
}

/** Zustand einer Datei im Journal. Nur [VERIFIED] und [ALREADY_THERE] zählen als geprüfte Kopie. */
@Serializable
enum class EntryState { PENDING, COPYING, VERIFIED, ALREADY_THERE, FAILED, NOT_DONE }

@Serializable
enum class EntryReason {
    /** Original nicht lesbar (z. B. gelöscht oder nicht mehr freigegeben). */
    READ_FAILED,
    /** Ziel hat das Schreiben abgelehnt (voll, schreibgeschützt, entfernt). */
    WRITE_FAILED,
    /** Die neu gelesene Kopie weicht vom Original ab – sie wurde wieder entfernt. */
    MISMATCH,
    /** Abgebrochen, bevor die Datei fertig war. */
    CANCELLED,
    /** App wurde unterbrochen (z. B. beendet); eine halbe Kopie wurde entfernt. */
    INTERRUPTED,
    /** Nicht genug Platz am Ziel. */
    NO_SPACE,
}

@Serializable
data class JournalEntry(
    val sourceId: String,
    /** Name am Ziel (nach Bereinigung und Konfliktauflösung). */
    val name: String,
    val folder: List<String> = emptyList(),
    /** Gemeldete Größe des Originals (`null` = unbekannt). */
    val size: Long? = null,
    val state: EntryState = EntryState.PENDING,
    val reason: EntryReason? = null,
    /** Ziel-Dokument (für das Entfernen einer halben Kopie nach Unterbrechung). */
    val targetUri: String? = null,
    /** Gemessene Bytes der geprüften Kopie. */
    val bytes: Long? = null,
    /** SHA-256 (hex) von Original und geprüfter Kopie. */
    val sha256: String? = null,
) {
    val isVerified: Boolean get() = state == EntryState.VERIFIED || state == EntryState.ALREADY_THERE
}

@Serializable
data class ArchiveJournal(
    val id: String,
    /** Anzeigename des Ziels („SD-Karte › Archiv“). */
    val targetLabel: String,
    val targetKind: TargetKind,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val options: ArchiveOptions = ArchiveOptions(),
    val entries: List<JournalEntry> = emptyList(),
    /** Prüfliste geschrieben (Name am Ziel), sonst `null`. */
    val listName: String? = null,
) {
    val finished: Boolean get() = finishedAt != null
    val verified: List<JournalEntry> get() = entries.filter { it.isVerified }
    val summary: ArchiveSummary get() = ArchiveSummary.of(entries)

    fun update(sourceId: String, change: (JournalEntry) -> JournalEntry) =
        copy(entries = entries.map { if (it.sourceId == sourceId) change(it) else it })
}

/** Zahlen für die Ergebnisseite – nur gemessene Bytes geprüfter Kopien. */
data class ArchiveSummary(
    val total: Int,
    val verified: Int,
    val alreadyThere: Int,
    val failed: Int,
    val notDone: Int,
    val verifiedBytes: Long,
) {
    companion object {
        fun of(entries: List<JournalEntry>) = ArchiveSummary(
            total = entries.size,
            verified = entries.count { it.state == EntryState.VERIFIED },
            alreadyThere = entries.count { it.state == EntryState.ALREADY_THERE },
            failed = entries.count { it.state == EntryState.FAILED },
            notDone = entries.count { it.state == EntryState.NOT_DONE || it.state == EntryState.PENDING || it.state == EntryState.COPYING },
            verifiedBytes = entries.filter { it.isVerified }.sumOf { it.bytes ?: 0L },
        )
    }
}

object ArchiveJournalRules {
    /**
     * Nach einem Abbruch oder Prozessende: Was nicht geprüft ist, gilt nicht. Eine begonnene Kopie ([EntryState.COPYING])
     * wird als [EntryReason.INTERRUPTED] beendet – ihre (halbe) Zieldatei muss entfernt werden; Offenes wird
     * [EntryState.NOT_DONE]. Liefert das Journal und die zu entfernenden Ziel-Dokumente (nur eigene, halbe Kopien).
     */
    fun close(j: ArchiveJournal, reason: EntryReason, now: Long): Pair<ArchiveJournal, List<String>> {
        val remove = mutableListOf<String>()
        val entries = j.entries.map { e ->
            when (e.state) {
                EntryState.COPYING -> { e.targetUri?.let(remove::add); e.copy(state = EntryState.NOT_DONE, reason = reason, targetUri = null) }
                EntryState.PENDING -> e.copy(state = EntryState.NOT_DONE, reason = reason)
                else -> e
            }
        }
        return j.copy(entries = entries, finishedAt = j.finishedAt ?: now) to remove
    }
}

/** Hex-Darstellung einer Prüfsumme. */
fun ByteArray.toHex(): String = joinToString("") { String.format(Locale.ROOT, "%02x", it) }

/**
 * Prüfliste als CSV (RFC 4180, UTF-8 mit BOM für Tabellenprogramme). Zellen, die mit = + - @ beginnen, bekommen ein
 * vorangestelltes Apostroph, damit ein Tabellenprogramm sie nie als Formel ausführt.
 */
object ArchiveList {
    data class Row(val folder: String, val name: String, val taken: String, val bytes: String, val sha256: String,
                   val albums: String, val tags: String, val note: String)

    fun render(header: List<String>, rows: List<Row>): String {
        val sb = StringBuilder("﻿")
        sb.append(header.joinToString(",") { cell(it) }).append("\r\n")
        for (r in rows) sb.append(listOf(r.folder, r.name, r.taken, r.bytes, r.sha256, r.albums, r.tags, r.note).joinToString(",") { cell(it) }).append("\r\n")
        return sb.toString()
    }

    fun cell(raw: String): String {
        val v = if (raw.isNotEmpty() && raw[0] in "=+-@\t\r") "'$raw" else raw
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }
}
