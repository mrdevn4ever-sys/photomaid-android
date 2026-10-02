package app.photomaid

import app.photomaid.core.later.LaterInterval
import app.photomaid.core.model.LocalDay
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
    /** Erster lokaler Nutzungstag (leiser Pro-Hinweis auf „Heute“ erst ab Tag 2). */
    val firstSeenDay: LocalDay? = null,
    /** Deutlicheres Pro-Angebot nach dem ersten erfolgreichen Durchgang wurde einmal gezeigt. */
    val proOfferShown: Boolean = false,
    /** Version des abgeschlossenen Onboardings (0 = alter Ablauf bzw. noch nicht gesehen). */
    val onboardingVersion: Int = 0,
    /** Hinweiskarten-Tutorial auf „Heute“ bzw. im Feed einmal gezeigt (oder übersprungen). */
    val todayTourSeen: Boolean = false,
    val feedTourSeen: Boolean = false,
    /** Summe „gespart“ über alle Leerungen (nur bekannte Größen bestätigt entfernter Elemente). */
    val savedTotalBytes: Long = 0,
    /** Mindestens eine Größe war unbekannt → Summe ist eine Untergrenze („mind.“). */
    val savedTotalLowerBound: Boolean = false,
    /** Vom Aufräumen ausgenommene Ordner (MediaStore `RELATIVE_PATH`, normalisiert, inkl. Unterordner). Keine Medien-IDs. */
    val excludedFolders: List<String> = emptyList(),
    /** Bedienungshilfe: Tasten zum Entscheiden unter dem Medium (Standard aus; Gesten bleiben aktiv). */
    val decisionButtons: Boolean = false,
    /** Reihenfolge im Feed (Neueste zuerst Standard). */
    val feedOrder: app.photomaid.core.feed.FeedOrder = app.photomaid.core.feed.FeedOrder.NEWEST,
    /** Ende der letzten Sitzung mit mindestens einer Entscheidung (Epoch-ms) – für „Neu seit dem letzten Aufräumen“. */
    val lastSessionEndMs: Long = 0,
    /** Zuletzt benutzter Monat im Feed (yyyy-MM) – für „Weitermachen“. Nur der Monat, keine Medien-IDs. */
    val continueMonth: String? = null,
    /** Angepinnte und zuletzt verwendete Zielordner beim Verschieben (Pfade, keine Medien-IDs). */
    val pinnedFolders: List<String> = emptyList(),
    val recentFolders: List<String> = emptyList(),
    /** Weitere Hinweiskarten (je Funktion einmal). */
    val monthsTourSeen: Boolean = false,
    val seriesTourSeen: Boolean = false,
    val gridTourSeen: Boolean = false,
    /** Zuletzt gewählte Speicherart im Editor (Standard beim ersten Mal: Kopie, Original bleibt). */
    val editSaveMode: app.photomaid.core.edit.SaveMode = app.photomaid.core.edit.SaveMode.COPY,
    val editorTourSeen: Boolean = false,
    /**
     * „In PhotoMaid ausblenden“: Ordner (Pfade, inkl. Unterordner) und einzelne Medien (Content-URIs, nur lokal in der
     * App gespeichert). Ausgeblendetes erscheint weder im Tab „Fotos“ noch beim Aufräumen; die Dateien bleiben
     * unverändert und in anderen Apps sichtbar. Kein Tresor.
     */
    val hiddenFolders: List<String> = emptyList(),
    val hiddenMedia: List<String> = emptyList(),
    /** Reihenfolge je Ordner (Schlüssel: Pfad klein geschrieben bzw. `group:<id>`) und Standard für alle übrigen. */
    val folderSorts: Map<String, app.photomaid.core.library.FolderSort> = emptyMap(),
    val defaultFolderSort: app.photomaid.core.library.FolderSort = app.photomaid.core.library.FolderSort.NEWEST,
    val folderListSort: app.photomaid.core.library.FolderListSort = app.photomaid.core.library.FolderListSort.RECENT,
    /** Ordner, in denen nur Favoriten gezeigt werden (gleiche Schlüssel wie [folderSorts]). */
    val folderFavoritesOnly: List<String> = emptyList(),
    /** Eigene Gruppen (nur Name und Ordnerpfade). */
    val folderGroups: List<app.photomaid.core.library.FolderGroup> = emptyList(),
    /** Favoriten-Ansicht: nach Ordnern gruppiert (Standard) oder chronologisch. */
    val favoritesByFolder: Boolean = true,
    /** Diashow: Reihenfolge, Dauer, Übergang, Videos, Wiederholen (keine Medien-IDs). */
    val slideshow: app.photomaid.core.library.SlideshowSettings = app.photomaid.core.library.SlideshowSettings(),
)
