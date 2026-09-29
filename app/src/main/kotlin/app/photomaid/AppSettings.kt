package app.photomaid

import app.photomaid.core.later.LaterInterval
import app.photomaid.design.AppearanceChoice
import kotlinx.serialization.Serializable

/** Port von `AppSettingsData`. Tolerant: fehlende/neue Felder behalten ihren Standard. */
@Serializable
data class AppSettingsData(
    val haptics: Boolean = true,
    /** Gestenhilfe bei jedem Öffnen des Feeds kurz einblenden (abwählbar). */
    val showGestureHintOnOpen: Boolean = true,
    val appearance: AppearanceChoice = AppearanceChoice.SYSTEM,
    val largeFileThresholdMB: Int = 100,
    val privacyIntroSeen: Boolean = false,
    /** Standardabstand beim Wisch nach rechts (Tage). */
    val laterDefaultDays: Int = LaterInterval.defaultQuick.value,
    /** Android: Medienberechtigung wurde mindestens einmal angefragt (unterscheidet „nicht angefragt“ von „abgelehnt“). */
    val mediaPermissionAsked: Boolean = false,
    /** Android: Nutzer hat Mitteilungen ausdrücklich erlaubt (Opt-in wie iOS). */
    val notificationsOptIn: Boolean = false,
)
