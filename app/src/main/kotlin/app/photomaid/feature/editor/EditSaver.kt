package app.photomaid.feature.editor

import app.photomaid.AppModel
import app.photomaid.R
import app.photomaid.core.decisions.DecisionAction
import app.photomaid.core.decisions.Disposition
import app.photomaid.core.edit.EditNames
import app.photomaid.core.edit.EditRecipe
import app.photomaid.core.edit.EditRecord
import app.photomaid.core.edit.PxSize
import app.photomaid.core.edit.SaveMode
import app.photomaid.core.edit.TrimRange
import app.photomaid.core.model.ItemReference
import app.photomaid.media.EditError
import app.photomaid.media.EditSaveRequest
import app.photomaid.media.EditSaveResult
import app.photomaid.media.MediaAsset
import app.photomaid.feature.studio.ShareCache

/** Ergebnis einer gespeicherten Bearbeitung (an den Feed und die Liste „Bearbeitet“). */
sealed interface EditOutcome {
    val sourceId: String
    data class Saved(
        override val sourceId: String,
        val newId: String,
        val mode: SaveMode,
        /** Original liegt im Android-Papierkorb. */
        val replaced: Boolean,
        /** Ersetzen gewählt, Papierkorb-Bestätigung aber abgelehnt: beide Dateien bleiben. */
        val replaceDeclined: Boolean,
        val bytes: Long,
        val video: Boolean,
        val hdrDropped: Boolean = false,
    ) : EditOutcome
}

/**
 * Speichern einer Bearbeitung: Export (Cache) → Mediathek (neue Datei, atomar) → bei „Original ersetzen“ Original in den
 * Android-Papierkorb → Ledger (die neue Fassung gilt als „Behalten“; das ersetzte Original verlässt Papierkorb/Später)
 * → Liste „Bearbeitet“ → Meldung an den Feed. Das Original wird nie überschrieben.
 */
object EditSaver {
    /** [targetPath] = anderer Zielordner für die Kopie („direkt einsortieren“); `null` = Ordner des Originals. */
    suspend fun savePhoto(app: AppModel, asset: MediaAsset, recipe: EditRecipe, full: PxSize, mode: SaveMode, removeLocation: Boolean,
                          targetPath: String? = null): EditOutcome.Saved {
        val export = app.editEngine.export(asset.uri, asset.mimeType, recipe, full, removeLocation, SOFTWARE)
        try {
            val r = app.photos.saveEdited(EditSaveRequest(
                sourceId = asset.id, file = export.file, mime = export.format.mime,
                displayName = EditNames.copyName(asset.displayName, app.string(R.string.edit_copy_suffix), export.format.extension),
                relativePath = if (mode == SaveMode.COPY) targetPath ?: asset.relativePath else asset.relativePath,
                dateTakenMs = asset.creationDate?.toEpochMilli(), video = false,
                replace = mode == SaveMode.REPLACE, favorite = mode == SaveMode.REPLACE && asset.isFavorite,
                replaceName = EditNames.sameName(asset.displayName, export.format.extension),
            ))
            return finish(app, asset, r, mode, video = false, hdrDropped = export.hdrDropped)
        } finally { export.file.delete() }
    }

    suspend fun saveVideo(app: AppModel, asset: MediaAsset, range: TrimRange, mode: SaveMode, onProgress: (Float) -> Unit): EditOutcome.Saved {
        val file = app.videoTrimmer.trim(asset.uri, range, onProgress)
        try {
            val r = app.photos.saveEdited(EditSaveRequest(
                sourceId = asset.id, file = file, mime = "video/mp4",
                displayName = EditNames.copyName(asset.displayName, app.string(R.string.edit_copy_suffix), "mp4"),
                relativePath = asset.relativePath, dateTakenMs = asset.creationDate?.toEpochMilli(), video = true,
                replace = mode == SaveMode.REPLACE, favorite = mode == SaveMode.REPLACE && asset.isFavorite,
                replaceName = EditNames.sameName(asset.displayName, "mp4"),
            ))
            return finish(app, asset, r, mode, video = true, hdrDropped = false)
        } finally { file.delete() }
    }

    private suspend fun finish(app: AppModel, asset: MediaAsset, r: EditSaveResult, mode: SaveMode, video: Boolean, hdrDropped: Boolean): EditOutcome.Saved {
        val saved = r as? EditSaveResult.Saved ?: throw EditError.WriteFailed((r as EditSaveResult.Failed).reason)
        // Ausdrücklich gespeichert = behalten (Nutzerentscheidung 2026-10-02), sonst erschiene die Fassung als neues offenes Medium.
        runCatching { app.decide(ItemReference.photo(saved.newId), DecisionAction.Keep) }
        if (saved.replaced) {
            val orig = ItemReference.photo(asset.id)
            val rec = app.ledger.record(orig)
            runCatching {
                when (rec.disposition) {
                    Disposition.MARKED_FOR_CLEANUP -> app.decide(orig, DecisionAction.Unmark)
                    Disposition.LATER -> app.decide(orig, DecisionAction.RemoveDeferral)
                    else -> Unit
                }
            }
        }
        app.edits.add(EditRecord(saved.newId, asset.id, if (saved.replaced) SaveMode.REPLACE else SaveMode.COPY, video, System.currentTimeMillis()))
        val out = EditOutcome.Saved(asset.id, saved.newId, mode, saved.replaced, saved.replaceDeclined, saved.bytes, video, hdrDropped)
        app.editEvents.tryEmit(out)
        return out
    }

    /**
     * „Bearbeiten und teilen“: Export nur in den Cache und als teilbare URI zurück – keine Galeriekopie, kein Ledger-
     * Eintrag. Standort standardmäßig entfernt (privater Export).
     */
    suspend fun sharePhoto(app: AppModel, asset: MediaAsset, recipe: EditRecipe, full: PxSize, removeLocation: Boolean = true): android.net.Uri {
        val export = app.editEngine.export(asset.uri, asset.mimeType, recipe, full, removeLocation, SOFTWARE)
        val name = EditNames.copyName(asset.displayName, app.string(R.string.edit_copy_suffix), export.format.extension)
        return ShareCache.stage(app.context, export.file, name)
    }

    const val SOFTWARE = "PhotoMaid"
}
