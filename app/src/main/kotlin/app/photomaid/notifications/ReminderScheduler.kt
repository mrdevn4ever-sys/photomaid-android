package app.photomaid.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.photomaid.MainActivity
import app.photomaid.R
import app.photomaid.core.model.LocalDay
import app.photomaid.core.rules.ReviewRuleID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Opt-in-Erinnerungen (iOS `ReminderScheduler`). Begrenzte Anzahl (max. 8 ausstehend), keine Dateinamen
 * auf dem Sperrbildschirm, keine Fundzahlen. Zeitplanung über WorkManager (überlebt Neustart).
 * Zustellzeit ist nicht garantiert exakt (Doze) – dafür keine Berechtigung für exakte Alarme nötig.
 */
class ReminderScheduler(private val context: Context, private val everAsked: () -> Boolean) {
    enum class Access { NOT_DETERMINED, ALLOWED, DENIED }

    private val _access = MutableStateFlow(Access.NOT_DETERMINED)
    val access: StateFlow<Access> = _access.asStateFlow()

    companion object {
        const val MAX_PENDING = 8
        const val CHANNEL = "reminders"
        private const val TAG = "photomaid-reminder"
        const val KEY_KIND = "kind"
    }

    /**
     * Opt-in wie iOS: „Erlaubt“ erst nach ausdrücklicher Nutzeraktion ([everAsked]) UND wenn das System
     * Mitteilungen zulässt (Android 13+: `POST_NOTIFICATIONS`, darunter: App-Mitteilungen nicht deaktiviert).
     */
    fun refresh() {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val granted = enabled && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        _access.value = when {
            !everAsked() -> Access.NOT_DETERMINED
            granted -> Access.ALLOWED
            else -> Access.DENIED
        }
    }

    fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun pendingCount(): Int = runCatching {
        WorkManager.getInstance(context).getWorkInfosByTag(TAG).get().count { it.state == WorkInfo.State.ENQUEUED }
    }.getOrDefault(0)

    private fun alreadyScheduled(name: String): Boolean = runCatching {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.ENQUEUED }
    }.getOrDefault(false)

    /** Erinnerung am Termintag (09:00 lokal). Idempotent je Tag. */
    fun scheduleDueReminder(day: LocalDay) {
        if (_access.value != Access.ALLOWED) return
        val name = "later.$day"
        if (!alreadyScheduled(name) && pendingCount() >= MAX_PENDING) return
        val zone = ZoneId.systemDefault()
        val at = day.toLocalDate().atTime(LocalTime.of(9, 0)).atZone(zone)
        val delay = Duration.between(ZonedDateTime.now(zone), at).coerceAtLeast(Duration.ZERO)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_KIND to "due"))
            .addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, req)
    }

    /** Wöchentliche Erinnerung einer Regel (Wochentag 1 = Sonntag … 7 = Samstag, 10:00). Nur Hinweis, keine Fundzahl. */
    fun scheduleRuleReminder(ruleID: ReviewRuleID, weekday: Int) {
        if (_access.value != Access.ALLOWED) return
        val name = "rule.${ruleID.rawValue}"
        if (!alreadyScheduled(name) && pendingCount() >= MAX_PENDING) return
        val dow = DayOfWeek.of(((weekday + 5) % 7) + 1) // 1=So → SUNDAY(7), 2=Mo → MONDAY(1)
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var next = now.with(TemporalAdjusters.nextOrSame(dow)).with(LocalTime.of(10, 0))
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_KIND to "rule"))
            .addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun removeRuleReminder(ruleID: ReviewRuleID) {
        WorkManager.getInstance(context).cancelUniqueWork("rule.${ruleID.rawValue}")
    }
}

/** Zeigt die Erinnerung. Enthält nur einen allgemeinen Text – keine Dateinamen, keine Zahlen. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val body = if (inputData.getString(ReminderScheduler.KEY_KIND) == "rule") R.string.notif_rule_body else R.string.notif_due_body
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, ReminderScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(ctx.getString(body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id.hashCode(), n) }
        return Result.success()
    }
}
