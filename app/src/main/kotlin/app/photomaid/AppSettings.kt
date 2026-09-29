package app.photomaid

import app.photomaid.core.later.LaterInterval
import app.photomaid.design.AppearanceChoice
import kotlinx.serialization.Serializable

/** Port von `AppSettingsData`. Tolerant: fehlende/neue Felder behalten ihren Standard. */
@Serializable
data class AppSettingsData(
    val haptics: Boolean = true,
    /**
     * Gestenhilfe zusätzlich bei jedem Öffnen des Feeds einblenden. Standard aus: die Hilfe erscheint von selbst
     * nur beim ersten Feed ([gestureHintSeen]), damit sie das Medium nicht ständig verdeckt.
     */
    val showGestureHintOnOpen: Boolean = false,
    /** Gestenhilfe wurde einmal gezeigt (Erstnutzung). */
    val gestureHintSeen: Boolean = false,
    val appearance: AppearanceChoice = AppearanceChoice.SYSTEM,
    val largeFileThresholdMB: Int = 100,
    val privacyIntroSeen: Boolean = false,
    /** Standardabstand beim Wisch nach rechts (Tage). */
    val laterDefaultDays: Int = LaterInterval.defaultQuick.value,
    /** Android: Medienberechtigung wurde mindestens einmal angefragt (unterscheidet „nicht angefragt“ von „abgelehnt“). */
    val mediaPermissionAsked: Boolean = false,
    /** Android: Nutzer hat Mitteilungen ausdrücklich erlaubt (Opt-in wie iOS). */
    val notificationsOptIn: Boolean = false,
    /**
     * Favoriten schützen: Wisch nach links auf einen Systemfavoriten legt ihn nicht in den Papierkorb, sondern fragt
     * nach. Standard AN (neu, vorher gab es kein Favoritenverhalten; siehe docs/PRODUCT_UX_AUDIT.md).
     */
    val protectFavorites: Boolean = true,
)
