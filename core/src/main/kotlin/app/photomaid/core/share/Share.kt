package app.photomaid.core.share

/**
 * Teilen-Ziel und App-Shortcuts (Ausbau 2026-10). Grundsatz: Ein Einstieg von außen öffnet nur eine Ansicht oder eine
 * Auswahl von Aktionen – er entscheidet nie (kein Behalten, kein Papierkorb), löscht nie und legt keine dauerhafte
 * Kopie an. Erst ein ausdrücklicher Schritt in der App (Ablauf starten, Speichern im Editor, zum Album hinzufügen)
 * wirkt – auf demselben Weg wie aus der Mediathek.
 */
object ShareRules {
    /** Höchstzahl je Übergabe (wie die Medienauswahl). */
    const val MAX_ITEMS = 500

    /** Nur Fotos und Videos; alles andere wird nicht angenommen. */
    fun accepts(mime: String?): Boolean {
        val m = mime?.trim()?.lowercase() ?: return false
        return m.startsWith("image/") || m.startsWith("video/")
    }
}

/** Eine übergebene Datei, bevor sie einem Medium der Mediathek zugeordnet ist. */
data class SharedItem(val uri: String, val mime: String?, val name: String? = null, val size: Long? = null)

/** Kandidat aus der Mediathek für den Abgleich. */
data class LibraryFingerprint(val id: String, val name: String?, val size: Long?)

object ShareMatch {
    /**
     * Zuordnung über den Dateinamen UND die Größe – nur, wenn genau ein Medium passt. Fehlt eins von beiden oder passen
     * mehrere, gibt es keine Zuordnung (nie raten).
     */
    fun unique(name: String?, size: Long?, candidates: List<LibraryFingerprint>): String? {
        val n = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val s = size?.takeIf { it > 0 } ?: return null
        val hits = candidates.filter { it.size == s && it.name?.trim()?.lowercase() == n }
        return hits.singleOrNull()?.id
    }
}

/**
 * Ergebnis einer Übergabe: [matched] = Medien-IDs der Mediathek (Reihenfolge wie übergeben, ohne Doppelte),
 * [unmatched] = Dateien, die nicht eindeutig in der Mediathek gefunden wurden (werden sichtbar gezählt),
 * [rejected] = keine Fotos/Videos, [overflow] = über [ShareRules.MAX_ITEMS].
 */
data class ShareIntake(val matched: List<String>, val unmatched: Int, val rejected: Int, val overflow: Int) {
    val isEmpty: Boolean get() = matched.isEmpty()

    companion object {
        fun of(items: List<SharedItem>, resolve: (SharedItem) -> String?): ShareIntake {
            var rejected = 0; var unmatched = 0
            val accepted = items.filter { ShareRules.accepts(it.mime).also { ok -> if (!ok) rejected++ } }
            val kept = accepted.take(ShareRules.MAX_ITEMS)
            val ids = LinkedHashSet<String>()
            for (i in kept) { val id = resolve(i); if (id == null) unmatched++ else ids += id }
            return ShareIntake(ids.toList(), unmatched, rejected, accepted.size - kept.size)
        }
    }
}

/** Aktionen für übergebene Medien. Keine davon ist eine Aufräum-Entscheidung. */
enum class ShareAction { WORKFLOW, EDIT, PDF, ALBUM, BASKET }

object ShareActions {
    /** Bearbeiten nur für genau ein Medium; alles andere ab einem zugeordneten Medium. */
    fun available(matched: Int): List<ShareAction> = when {
        matched <= 0 -> emptyList()
        matched == 1 -> listOf(ShareAction.WORKFLOW, ShareAction.EDIT, ShareAction.PDF, ShareAction.ALBUM, ShareAction.BASKET)
        else -> listOf(ShareAction.WORKFLOW, ShareAction.PDF, ShareAction.ALBUM, ShareAction.BASKET)
    }
}

/**
 * Ziel eines App-Shortcuts (Launcher-Langdruck, angeheftete Verknüpfung, Direktes Teilen). Nur Ansichten öffnen –
 * kein Ziel löscht, entscheidet oder startet etwas ohne weiteren Tipp.
 */
sealed interface ShortcutTarget {
    val id: String

    /** Aufräumen starten (Feed über alle Medien). */
    data object Clean : ShortcutTarget { override val id = "clean" }
    /** Fällige „Später“-Medien. */
    data object Due : ShortcutTarget { override val id = "due" }
    /** Auswahlkorb (Organisieren). */
    data object Basket : ShortcutTarget { override val id = "basket" }
    /** Liste der Arbeitsabläufe. */
    data object Workflows : ShortcutTarget { override val id = "workflows" }
    /** Ein gespeicherter Ablauf: ohne Medien erst die Auswahl, mit geteilten Medien direkt die Vorschau „Das passiert“. */
    data class Workflow(val workflowId: String) : ShortcutTarget { override val id = PREFIX + workflowId }

    companion object {
        const val PREFIX = "wf:"
        /** Feste Einträge in Launcher-Reihenfolge. */
        val fixed: List<ShortcutTarget> = listOf(Clean, Due, Basket, Workflows)

        fun parse(id: String?): ShortcutTarget? = when {
            id == null -> null
            id.startsWith(PREFIX) -> id.removePrefix(PREFIX).takeIf { it.isNotBlank() }?.let { Workflow(it) }
            else -> fixed.firstOrNull { it.id == id }
        }

        /**
         * Feste Einträge plus die ersten Abläufe, insgesamt höchstens [max] (Launcher-Grenze je Activity).
         * Abläufe kommen nach den festen Einträgen; die Reihenfolge entspricht der Liste in der App.
         */
        fun plan(workflowIds: List<String>, max: Int, maxWorkflows: Int = 4): List<ShortcutTarget> {
            val fixedPart = fixed.take(max.coerceAtLeast(0))
            val room = (max - fixedPart.size).coerceAtLeast(0).coerceAtMost(maxWorkflows)
            return fixedPart + workflowIds.distinct().take(room).map { Workflow(it) }
        }
    }
}
