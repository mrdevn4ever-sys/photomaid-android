package app.photomaid.system

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.photomaid.R
import app.photomaid.core.batch.Workflow
import app.photomaid.core.share.ShortcutTarget

/**
 * App-Shortcuts (Langdruck auf das App-Symbol, anheftbar) und Direktes Teilen je gespeichertem Ablauf.
 * Alle öffnen nur eine Ansicht; keiner löscht oder entscheidet. Keine Medien-IDs in Shortcuts – nur feste Ziele und
 * die (lokale) Ablauf-ID mit dem vom Nutzer vergebenen Namen.
 */
object AppShortcuts {
    /** Muss zur Kategorie in `res/xml/shortcuts.xml` (share-target) passen. */
    const val CATEGORY_WORKFLOW_SHARE = "app.photomaid.category.WORKFLOW_SHARE"

    fun sync(context: Context, workflows: List<Workflow>) {
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).takeIf { it > 0 } ?: 4
            val byId = workflows.associateBy { it.id }
            val plan = ShortcutTarget.plan(workflows.map { it.id }, max)
            val infos = plan.mapIndexedNotNull { rank, t -> info(context, t, rank, byId) }
            runCatching { cleanup(context, infos.map { it.id }.toSet(), byId.keys) }
            ShortcutManagerCompat.setDynamicShortcuts(context, infos)
        }.onFailure { android.util.Log.w("PhotoMaid", "Shortcuts nicht aktualisiert: ${it.javaClass.simpleName}") }
    }

    /** Nicht mehr vorhandene Abläufe: aus dem Teilen-Menü nehmen, angeheftete deaktivieren (nicht still löschen). */
    private fun cleanup(context: Context, keep: Set<String>, workflowIds: Set<String>) {
        val stale = (ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED or ShortcutManagerCompat.FLAG_MATCH_CACHED))
            .map { it.id }.filter { it.startsWith(ShortcutTarget.PREFIX) && it !in keep && it.removePrefix(ShortcutTarget.PREFIX) !in workflowIds }
        if (stale.isEmpty()) return
        ShortcutManagerCompat.removeLongLivedShortcuts(context, stale)
        ShortcutManagerCompat.disableShortcuts(context, stale, context.getString(R.string.shortcut_workflow_gone))
    }

    private fun info(context: Context, t: ShortcutTarget, rank: Int, workflows: Map<String, Workflow>): ShortcutInfoCompat? {
        val (short, long, icon) = when (t) {
            ShortcutTarget.Clean -> Triple(context.getString(R.string.shortcut_clean), context.getString(R.string.shortcut_clean_long), R.drawable.ic_shortcut_clean)
            ShortcutTarget.Due -> Triple(context.getString(R.string.shortcut_due), context.getString(R.string.shortcut_due_long), R.drawable.ic_shortcut_due)
            ShortcutTarget.Basket -> Triple(context.getString(R.string.org_basket), context.getString(R.string.org_basket), R.drawable.ic_shortcut_basket)
            ShortcutTarget.Workflows -> Triple(context.getString(R.string.wf_title), context.getString(R.string.wf_title), R.drawable.ic_shortcut_workflow)
            is ShortcutTarget.Workflow -> {
                val w = workflows[t.workflowId] ?: return null
                val name = w.name.ifBlank { context.getString(R.string.wf_title) }
                Triple(name.take(24), name.take(60), R.drawable.ic_shortcut_workflow)
            }
        }
        val b = ShortcutInfoCompat.Builder(context, t.id)
            .setShortLabel(short).setLongLabel(long).setRank(rank)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(AppEntry.shortcutIntent(context, t))
        if (t is ShortcutTarget.Workflow) b.setLongLived(true).setCategories(setOf(CATEGORY_WORKFLOW_SHARE))
        return b.build()
    }
}
