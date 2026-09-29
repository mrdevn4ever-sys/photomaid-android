package app.photomaid.core.model

import kotlinx.serialization.Serializable

/** Eine Größenangabe, die unbekannt sein darf. Unbekannt ist niemals null. */
@Serializable
sealed interface KnownSize {
    @Serializable
    data class Known(val bytes: Long) : KnownSize

    @Serializable
    data object Unknown : KnownSize

    val bytesOrNull: Long? get() = (this as? Known)?.bytes
}

/** Summe über mehrere Größen: bekannte Bytes und Anzahl unbekannter Einträge getrennt. */
data class SizeTally(
    val knownBytes: Long = 0,
    val knownCount: Int = 0,
    val unknownCount: Int = 0,
) {

    fun adding(size: KnownSize): SizeTally = when (size) {
        is KnownSize.Known -> copy(knownBytes = knownBytes + size.bytes, knownCount = knownCount + 1)
        KnownSize.Unknown -> copy(unknownCount = unknownCount + 1)
    }

    fun merging(other: SizeTally) = SizeTally(
        knownBytes + other.knownBytes, knownCount + other.knownCount, unknownCount + other.unknownCount,
    )

    val totalCount: Int get() = knownCount + unknownCount

    /** `true`, wenn die Bytezahl nur eine Untergrenze ist. */
    val isLowerBound: Boolean get() = unknownCount > 0

    companion object {
        fun of(sizes: Iterable<KnownSize>): SizeTally = sizes.fold(SizeTally()) { t, s -> t.adding(s) }
    }
}
