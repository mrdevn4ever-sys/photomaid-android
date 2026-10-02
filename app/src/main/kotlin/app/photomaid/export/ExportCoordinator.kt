package app.photomaid.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.photomaid.BootState
import app.photomaid.PhotoMaidApplication
import app.photomaid.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Lange Exporte (Video) laufen im App-Bereich weiter, auch wenn der Editor geschlossen wird; solange einer läuft, hält
 * ein Vordergrunddienst (Typ „mediaProcessing“, Android 15+) die App mit einer Fortschrittsmeldung am Leben.
 * Immer nur ein Export gleichzeitig (Speicher/Encoder).
 */
class ExportCoordinator(private val context: Context, private val scope: CoroutineScope) {
    data class Running(val label: String, val progress: Float?)

    private val _state = MutableStateFlow<Running?>(null)
    val state: StateFlow<Running?> = _state.asStateFlow()
    private var job: Job? = null
    /** Tests: kein echter Dienst. */
    var useService = true

    val isBusy: Boolean get() = _state.value != null

    fun <T> run(label: String, block: suspend (onProgress: (Float) -> Unit) -> T): Deferred<T> {
        check(!isBusy) { "export-running" }
        _state.value = Running(label, null)
        if (useService) runCatching { ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java)) }
        val d = scope.async {
            try { block { p -> _state.value = _state.value?.copy(progress = p.coerceIn(0f, 1f)) }.also { if (backgrounded) notifyDone(label) } }
            finally { _state.value = null; backgrounded = false }
        }
        job = d
        return d
    }

    fun cancel() { job?.cancel() }

    @Volatile private var backgrounded = false
    /** Der Bildschirm wurde verlassen: nach Erfolg eine kurze Meldung „gespeichert“. */
    fun background() { backgrounded = true }

    private fun notifyDone(label: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(NotificationChannel(ExportService.CHANNEL, context.getString(R.string.export_channel), NotificationManager.IMPORTANCE_LOW))
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { PendingIntent.getActivity(context, 3, it, PendingIntent.FLAG_IMMUTABLE) }
            nm.notify(4712, NotificationCompat.Builder(context, ExportService.CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.export_done)).setContentText(label).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}

/** Vordergrunddienst nur zur Anzeige und zum Am-Leben-Halten; die Arbeit macht [ExportCoordinator]. */
class ExportService : Service() {
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { coordinator()?.cancel(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.export_channel), NotificationManager.IMPORTANCE_LOW))
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, ID, notification(null, null), type)
        val c = coordinator()
        if (c == null) { stopSelf(); return START_NOT_STICKY }
        scope.launch {
            c.state.collect { s ->
                if (s == null) { ServiceCompat.stopForeground(this@ExportService, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
                else nm.notify(ID, notification(s.label, s.progress))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    private fun coordinator(): ExportCoordinator? = ((application as? PhotoMaidApplication)?.boot?.value as? BootState.Ready)?.app?.exports

    private fun notification(label: String?, progress: Float?): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE) }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label ?: getString(R.string.export_running))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .setContentIntent(open)
            .addAction(0, getString(R.string.cancel), cancel)
            .build()
    }

    companion object {
        const val CHANNEL = "export"
        private const val ID = 4711
        private const val ACTION_CANCEL = "app.photomaid.export.CANCEL"
    }
}
