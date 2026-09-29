package app.photomaid.system

import android.app.Activity
import android.content.IntentSender
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.CompletableDeferred
import java.lang.ref.WeakReference

/**
 * Brücke zu System-Bestätigungsdialogen (MediaStore-Papierkorb-Anfrage) und zur aktuellen Activity
 * (Play-Billing-Flow). Die Activity registriert den Launcher in `onCreate`; nach Recreation (Rotation)
 * liefert die Activity-Result-API das Ergebnis an den neuen Launcher – der offene Aufruf bleibt gültig.
 * Nach Prozessende bleibt die Operation im Journal `SUBMITTED` und wird beim Start abgeglichen.
 */
object SystemDialogs {
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: CompletableDeferred<Int>? = null
    private var activityRef: WeakReference<Activity>? = null

    fun attach(activity: Activity, l: ActivityResultLauncher<IntentSenderRequest>) {
        activityRef = WeakReference(activity)
        launcher = l
    }

    fun detach(activity: Activity) {
        if (activityRef?.get() === activity) { activityRef = null; launcher = null }
    }

    val activity: Activity? get() = activityRef?.get()

    fun onResult(resultCode: Int) {
        pending?.complete(resultCode)
        pending = null
    }

    /** Startet den Systemdialog und wartet auf das Ergebnis. `null`, wenn keine Activity verfügbar ist. */
    suspend fun launch(sender: IntentSender): Int? {
        val l = launcher ?: return null
        val d = CompletableDeferred<Int>()
        pending?.complete(Activity.RESULT_CANCELED)
        pending = d
        runCatching { l.launch(IntentSenderRequest.Builder(sender).build()) }.onFailure { pending = null; return null }
        return d.await()
    }
}
