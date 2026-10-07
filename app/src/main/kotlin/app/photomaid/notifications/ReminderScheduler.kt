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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.photomaid.MainActivity
import app.photomaid.R
import app.photomaid.core.model.LocalDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
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
    /** In diesem Prozess schon eingeplante Tage: jeder Wisch nach rechts trifft meist denselben Standardtag. */
    private val scheduledThisProcess = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun scheduleDueReminder(day: LocalDay) {
        if (_access.value != Access.ALLOWED) return
        val name = "later.$day"
        // Gerätebefund Lauf 7 (RECHTS p90 26 ms): WorkManager-Abfragen + Einplanen bei JEDEM „Später“ kosteten
        // CPU während der nächsten Animation. Gleicher Tag (KEEP-Policy) → nichts zu tun.
        if (name in scheduledThisProcess) return
        if (!alreadyScheduled(name) && pendingCount() >= MAX_PENDING) return
        val zone = ZoneId.systemDefault()
        val at = day.toLocalDate().atTime(LocalTime.of(9, 0)).atZone(zone)
        val delay = Duration.between(ZonedDateTime.now(zone), at).coerceAtLeast(Duration.ZERO)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_KIND to "due"))
            .addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, req)
        scheduledThisProcess += name
    }

}

/** Zeigt die Erinnerung. Enthält nur einen allgemeinen Text – keine Dateinamen, keine Zahlen. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        // Regel-Erinnerungen gibt es nicht mehr (Sammlungen statt Routinen): Altaufträge früherer Testbuilds still beenden.
        if (inputData.getString(ReminderScheduler.KEY_KIND) == "rule") {
            WorkManager.getInstance(ctx).cancelWorkById(id)
            return Result.success()
        }
        val body = R.string.notif_due_body
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
