package app.photomaid.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
@JvmInline
value class ConnectedSourceID(@Serializable(UUIDSerializer::class) val rawValue: UUID = UUID.randomUUID()) {
    override fun toString(): String = rawValue.toString()
}

/**
 * Herkunft eines Items. Fotos/Videos bleiben logische MediaStore-Einträge,
 * Dateien gehören immer zu genau einer vom Nutzer verbundenen Quelle.
 */
@Serializable
sealed interface ItemProvider {
    @Serializable
    data object PhotoLibrary : ItemProvider

    @Serializable
    data class ConnectedSource(val id: ConnectedSourceID) : ItemProvider

    @Serializable
    data object AppContainer : ItemProvider
}

/**
 * Stabile lokale Identität eines Items: Provider + lokale Kennung.
 *
 * - MediaStore: Content-URI des Eintrags (`content://media/<volume>/<images|video>/media/<_ID>`).
 *   Die `_ID` bleibt bei Umsortierung, Bearbeitung und Neustart gleich; nie Listenposition.
 * - Dateien: SAF-Dokument-ID innerhalb der verbundenen Quelle.
 */
@Serializable
data class ItemReference(val provider: ItemProvider, val localIdentifier: String) {
    override fun toString(): String = when (provider) {
        ItemProvider.PhotoLibrary -> "photo:$localIdentifier"
        is ItemProvider.ConnectedSource -> "file[${provider.id}]:$localIdentifier"
        ItemProvider.AppContainer -> "app:$localIdentifier"
    }

    val description: String get() = toString()

    companion object {
        fun photo(id: String) = ItemReference(ItemProvider.PhotoLibrary, id)
    }
}

@Serializable
enum class MediaKind { PHOTO, VIDEO, SCREENSHOT, FILE }
