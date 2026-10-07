package app.photomaid.feature.review

import android.widget.NumberPicker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import app.photomaid.R
import app.photomaid.core.later.LaterInterval
import app.photomaid.core.later.LaterUnit
import app.photomaid.core.model.LocalDay
import app.photomaid.design.AppSpacing
import app.photomaid.design.AppTypography
import app.photomaid.design.DayText
import app.photomaid.design.GlassButton
import app.photomaid.design.LaterText
import app.photomaid.design.PM
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** UTC-Mitternacht (Material-DatePicker rechnet in UTC-Millisekunden). */
private fun LocalDate.utcMillis() = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
private fun Long.utcDay(): LocalDay = LocalDay.of(Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate())

@OptIn(ExperimentalMaterial3Api::class)
private class FromToday(private val today: LocalDate) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today.utcMillis()
}

/**
 * Drehrad (iOS: Wheel-Picker wie beim Timer): „In [n] [Tagen | Wochen | Monaten]“, optional ein bestimmtes
 * Datum im Kalender. Android-nativ: `NumberPicker` (Rad) bzw. Material-Kalender.
 * Abbrechen, Zurück oder Wegwischen liefert `null` = nichts ändern (keine Entscheidung, kein Termin).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaterPickerSheet(initial: LaterInterval, today: LocalDay, onNoDate: (() -> Unit)? = null, finish: (LocalDay?) -> Unit) {
    val c = PM.colors
    val ctx = LocalContext.current
    var value by rememberSaveable { mutableIntStateOf(initial.value) }
    var unit by rememberSaveable { mutableStateOf(initial.unit) }
    var useDate by rememberSaveable { mutableStateOf(false) }
    val todayDate = today.toLocalDate()
    val dateState = rememberDatePickerState(initialSelectedDateMillis = todayDate.plusDays(14).utcMillis(), selectableDates = FromToday(todayDate))
    val interval = LaterInterval(value, unit)
    val result: LocalDay = if (useDate) dateState.selectedDateMillis?.utcDay() ?: interval.dueDay(today) else interval.dueDay(today)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var finished by remember { mutableStateOf(false) }
    fun done(day: LocalDay?) { if (!finished) { finished = true; finish(day) } }

    ModalBottomSheet(onDismissRequest = { done(null) }, sheetState = sheet, containerColor = c.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.m),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.m)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ done(null) }) { Text(stringResource(R.string.cancel)) }
                Text(stringResource(R.string.later), style = AppTypography.headline, color = c.textPrimary, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton({ done(result) }) { Text(stringResource(R.string.done), fontWeight = FontWeight.SemiBold) }
            }
            if (useDate) {
                DatePicker(dateState, showModeToggle = false, title = null, headline = null)
            } else {
                val countLabel = stringResource(R.string.picker_count)
                val unitLabel = stringResource(R.string.picker_unit)
                val range = LaterInterval.range(unit)
                Row(Modifier.fillMaxWidth()) {
                    AndroidView(
                        factory = { NumberPicker(it).apply { wrapSelectorWheel = false; textColor = c.textPrimary.toArgb() } },
                        update = { p ->
                            p.minValue = range.first; p.maxValue = range.last
                            p.value = value.coerceIn(range)
                            p.setOnValueChangedListener { _, _, n -> value = n }
                        },
                        modifier = Modifier.weight(1f).semantics { contentDescription = countLabel },
                    )
                    AndroidView(
                        factory = { NumberPicker(it).apply { wrapSelectorWheel = false; textColor = c.textPrimary.toArgb() } },
                        update = { p ->
                            val units = LaterUnit.entries
                            p.displayedValues = null
                            p.minValue = 0; p.maxValue = units.size - 1
                            p.displayedValues = units.map { LaterText.unit(ctx, it, value) }.toTypedArray()
                            p.value = units.indexOf(unit)
                            p.setOnValueChangedListener { _, _, n ->
                                unit = units[n]
                                value = value.coerceAtMost(LaterInterval.range(unit).last)
                            }
                        },
                        modifier = Modifier.weight(1f).semantics { contentDescription = unitLabel },
                    )
                }
            }
            Text(stringResource(R.string.picker_show_on, DayText.string(result)), style = AppTypography.subheadline, color = c.textSecondary)
            GlassButton(stringResource(if (useDate) R.string.picker_choose_interval else R.string.picker_specific_date)) { useDate = !useDate }
            // „Später ohne Termin“: kommt nie von selbst zurück, nur über „Später › Ohne Termin“.
            if (onNoDate != null) {
                TextButton({ if (!finished) { finished = true; onNoDate() } }, Modifier.testTag("later.nodate")) {
                    Text(stringResource(R.string.picker_no_date), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Text(stringResource(R.string.picker_no_date_hint), style = AppTypography.caption, color = c.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** Datum ändern (Später-Verwaltung). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaterDateDialog(today: LocalDay, onDismiss: () -> Unit, confirm: (LocalDay) -> Unit) {
    val todayDate = today.toLocalDate()
    val state = rememberDatePickerState(initialSelectedDateMillis = todayDate.plusDays(14).utcMillis(), selectableDates = FromToday(todayDate))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton({ state.selectedDateMillis?.let { confirm(it.utcDay()) }; onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state, title = { Text(stringResource(R.string.change_date), Modifier.padding(start = 24.dp_, top = 16.dp_)) }) }
}

private val Int.dp_ get() = androidx.compose.ui.unit.Dp(this.toFloat())
