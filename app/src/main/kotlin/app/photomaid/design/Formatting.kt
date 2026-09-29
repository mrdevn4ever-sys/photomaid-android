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

/** Die App ist (wie iOS) ausschließlich deutsch lokalisiert; Datumsformate folgen der Sprache der App. */
val AppLocale: Locale = Locale.GERMAN

/** Ehrliche Formatierung von Größen (iOS `ByteCountFormatter` Stil `.file`: dezimal). Unbekannt bleibt unbekannt. */
object SizeText {
    private fun fmt(v: Double, maxFraction: Int): String {
        val f = DecimalFormat("#,##0" + if (maxFraction > 0) "." + "#".repeat(maxFraction) else "", DecimalFormatSymbols(AppLocale))
        f.roundingMode = RoundingMode.HALF_UP
        return f.format(v)
    }

    fun bytes(b: Long): String = when {
        b < 1_000 -> "$b Byte"
        b < 1_000_000 -> "${fmt(b / 1e3, 0)} KB"
        b < 1_000_000_000 -> "${fmt(b / 1e6, 1)} MB"
        b < 1_000_000_000_000 -> "${fmt(b / 1e9, 2)} GB"
        else -> "${fmt(b / 1e12, 2)} TB"
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
    private val dayFormat = DateTimeFormatter.ofPattern("EEE, d. MMM", AppLocale)

    fun string(day: LocalDay): String = day.toLocalDate().format(dayFormat)
}

object DateText {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    fun abbreviated(i: Instant): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(AppLocale).format(i.atZone(zone))
    fun long(i: Instant): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(AppLocale).format(i.atZone(zone))
    fun longWithTime(i: Instant): String = DateTimeFormatter.ofPattern("d. MMMM yyyy 'um' HH:mm", AppLocale).format(i.atZone(zone))
    fun complete(i: Instant): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(AppLocale).format(i.atZone(zone))
    fun time(i: Instant): String = DateTimeFormatter.ofPattern("HH:mm", AppLocale).format(i.atZone(zone))
    fun duration(seconds: Double): String {
        val d = Duration.ofMillis((seconds * 1000).toLong())
        return "%d:%02d".format(d.toMinutes(), d.seconds % 60)
    }
}

/** Texte zu Später-Intervallen (iOS `LaterText`). */
object LaterText {
    fun inInterval(ctx: Context, i: LaterInterval): String = when (i.unit) {
        LaterUnit.DAYS -> if (i.value == 1) ctx.getString(R.string.later_tomorrow_again) else ctx.getString(R.string.later_in_days_again, i.value)
        LaterUnit.WEEKS -> if (i.value == 1) ctx.getString(R.string.later_in_one_week_again) else ctx.getString(R.string.later_in_weeks_again, i.value)
        LaterUnit.MONTHS -> if (i.value == 1) ctx.getString(R.string.later_in_one_month_again) else ctx.getString(R.string.later_in_months_again, i.value)
    }

    fun unit(ctx: Context, u: LaterUnit, n: Int): String = when (u) {
        LaterUnit.DAYS -> ctx.getString(if (n == 1) R.string.unit_day else R.string.unit_days)
        LaterUnit.WEEKS -> ctx.getString(if (n == 1) R.string.unit_week else R.string.unit_weeks)
        LaterUnit.MONTHS -> ctx.getString(if (n == 1) R.string.unit_month else R.string.unit_months)
    }
}
