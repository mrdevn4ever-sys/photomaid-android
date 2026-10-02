package app.photomaid.design

import android.content.Context
import app.photomaid.R
import app.photomaid.core.later.LaterInterval
import app.photomaid.core.later.LaterUnit
import app.photomaid.core.model.KnownSize
import app.photomaid.core.model.LocalDay
import app.photomaid.core.model.SizeTally
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Sprache der App (16 Sprachen seit 2026-10-04; per App-Sprache in Android 13+ wählbar). Zahlen, Größen und Daten
 * folgen ihr – immer zur Laufzeit gelesen, weil sich die App-Sprache ändern kann.
 */
val AppLocale: Locale get() = Locale.getDefault()

/** Ehrliche Formatierung von Größen (iOS `ByteCountFormatter` Stil `.file`: dezimal). Unbekannt bleibt unbekannt. */
object SizeText {
    private fun fmt(v: Double, maxFraction: Int): String {
        val f = DecimalFormat("#,##0" + if (maxFraction > 0) "." + "#".repeat(maxFraction) else "", DecimalFormatSymbols(AppLocale))
        f.roundingMode = RoundingMode.HALF_UP
        return f.format(v)
    }

    /** Einheiten je Sprache (dezimal wie Android/iOS): Französisch „o/Ko/Mo“, Kyrillisch „Б/КБ/МБ“, Deutsch „Byte“. */
    private fun units(): List<String> = when (AppLocale.language) {
        "de" -> listOf("Byte", "KB", "MB", "GB", "TB")
        "fr", "ro" -> listOf("o", "Ko", "Mo", "Go", "To")
        "ru", "uk" -> listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        else -> listOf("B", "KB", "MB", "GB", "TB")
    }

    fun bytes(b: Long): String {
        val u = units()
        return when {
            b < 1_000 -> "${fmt(b.toDouble(), 0)} ${u[0]}"
            b < 1_000_000 -> "${fmt(b / 1e3, 0)} ${u[1]}"
            b < 1_000_000_000 -> "${fmt(b / 1e6, 1)} ${u[2]}"
            b < 1_000_000_000_000 -> "${fmt(b / 1e9, 2)} ${u[3]}"
            else -> "${fmt(b / 1e12, 2)} ${u[4]}"
        }
    }

    fun size(ctx: Context, s: KnownSize): String = when (s) {
        is KnownSize.Known -> bytes(s.bytes)
        KnownSize.Unknown -> ctx.getString(R.string.size_unknown)
    }

    /** „mind. 1,2 GB“, wenn unbekannte Größen enthalten sind. */
    fun tally(ctx: Context, t: SizeTally): String = when {
        t.knownCount == 0 && t.unknownCount > 0 -> ctx.getString(R.string.size_unknown)
        t.isLowerBound -> ctx.getString(R.string.size_at_least, bytes(t.knownBytes))
        else -> bytes(t.knownBytes)
    }
}

object DayText {
    /** „Mi., 7. Okt.“ / „Wed, Oct 7“ – bestes Muster der jeweiligen Sprache. */
    fun string(day: LocalDay): String {
        val l = AppLocale
        return day.toLocalDate().format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(l, "EEEdMMM"), l))
    }
}

object MonthText {
    /** „September 2026“ – Monatsname in der Form der jeweiligen Sprache (z. B. Russisch: Nominativ). */
    fun title(m: app.photomaid.core.progress.MonthKey): String {
        val l = AppLocale
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(l, "MMMMyyyy")
        return m.yearMonth.atDay(1).format(DateTimeFormatter.ofPattern(pattern, l)).replaceFirstChar { if (it.isLowerCase()) it.titlecase(l) else it.toString() }
    }
}

object DateText {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    fun abbreviated(i: Instant): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(AppLocale).format(i.atZone(zone))
    fun long(i: Instant): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(AppLocale).format(i.atZone(zone))
    fun longWithTime(i: Instant): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT).withLocale(AppLocale).format(i.atZone(zone))
    fun complete(i: Instant): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(AppLocale).format(i.atZone(zone))
    fun time(i: Instant): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(AppLocale).format(i.atZone(zone))
    fun duration(seconds: Double): String {
        val d = Duration.ofMillis((seconds * 1000).toLong())
        return "%d:%02d".format(d.toMinutes(), d.seconds % 60)
    }
}

/** Texte zu Später-Intervallen (iOS `LaterText`). */
object LaterText {
    fun inInterval(ctx: Context, i: LaterInterval): String = when (i.unit) {
        LaterUnit.DAYS -> if (i.value == 1) ctx.getString(R.string.later_tomorrow_again) else ctx.resources.getQuantityString(R.plurals.later_in_days_again, i.value, i.value)
        LaterUnit.WEEKS -> if (i.value == 1) ctx.getString(R.string.later_in_one_week_again) else ctx.resources.getQuantityString(R.plurals.later_in_weeks_again, i.value, i.value)
        LaterUnit.MONTHS -> if (i.value == 1) ctx.getString(R.string.later_in_one_month_again) else ctx.resources.getQuantityString(R.plurals.later_in_months_again, i.value, i.value)
    }

    fun unit(ctx: Context, u: LaterUnit, n: Int): String = when (u) {
        // Sprachabhängige Mengenform (z. B. Polnisch/Russisch/Arabisch), nicht nur 1 ↔ viele.
        LaterUnit.DAYS -> ctx.resources.getQuantityString(R.plurals.unit_days, n)
        LaterUnit.WEEKS -> ctx.resources.getQuantityString(R.plurals.unit_weeks, n)
        LaterUnit.MONTHS -> ctx.resources.getQuantityString(R.plurals.unit_months, n)
    }
}
