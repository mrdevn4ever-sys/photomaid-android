package app.photomaid

import app.photomaid.core.rules.ReviewRule
import app.photomaid.core.rules.RuleFilter
import app.photomaid.core.rules.RuleSource
import app.photomaid.core.rules.RuleTrigger
import kotlinx.coroutines.launch

/**
 * Deterministische QA-Umgebung (iOS `QAFixtures`). Existiert ausschließlich im Build-Typ `qa` und wird nur mit
 * dem Intent-Extra `qa-fixtures=true` aktiv (In-Memory-Speicher). Keine persönlichen Daten.
 * Medien für QA werden mit `scripts/qa_seed_emulator.sh` als synthetische Dateien (Präfix QA_) angelegt.
 */
object QAFixtures {
    fun install(app: AppModel) {
        app.scope.launch {
            // Lokale Beispielregeln, damit „Regeln & Routinen“ nicht leer ist (wie iOS).
            app.rules.upsert(ReviewRule(source = RuleSource.Screenshots, filter = RuleFilter.OlderThan(30), trigger = RuleTrigger.Daily), app.reminders)
            app.rules.upsert(ReviewRule(source = RuleSource.Videos, filter = RuleFilter.KnownSizeAtLeast(1_000_000_000), trigger = RuleTrigger.Weekly(1)), app.reminders)
        }
    }
}
