package app.photomaid.feature.studio

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * „Bearbeiten und teilen“ ohne Galeriekopie: Exporte liegen nur kurz im App-Cache (`cache/shared/`) und werden über
 * einen FileProvider geteilt. Beim nächsten Start bzw. nach einer Stunde gelöscht. Nie hochgeladen – das System
 * entscheidet mit dem Nutzer, wohin geteilt wird.
 */
object ShareCache {
    private const val DIR = "shared"
    private const val MAX_AGE_MS = 60 * 60 * 1000L

    fun dir(ctx: Context): File = File(ctx.cacheDir, DIR).apply { mkdirs() }

    /** Verschiebt [file] unter [displayName] in den Teilen-Ordner und liefert die teilbare URI. */
    fun stage(ctx: Context, file: File, displayName: String): Uri {
        val safe = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
        val target = File(dir(ctx), safe)
        if (!file.renameTo(target)) { file.copyTo(target, overwrite = true); file.delete() }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.share", target)
    }

    /** Alte geteilte Dateien entfernen (Start der App, nach dem Teilen). */
    fun cleanup(ctx: Context, now: Long = System.currentTimeMillis(), all: Boolean = false) {
        dir(ctx).listFiles()?.forEach { if (all || now - it.lastModified() > MAX_AGE_MS) it.delete() }
    }
}
