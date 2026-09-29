package app.photomaid

import android.app.Application
import app.photomaid.data.PhotoMaidDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BootState {
    data object Loading : BootState
    data class Ready(val app: AppModel) : BootState
    data object Failed : BootState
}

/** Prozessweiter Einstieg (iOS `AppBoot`): öffnet den lokalen Speicher abseits des Main-Threads. */
class PhotoMaidApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _boot = MutableStateFlow<BootState>(BootState.Loading)
    val boot: StateFlow<BootState> = _boot.asStateFlow()
    private var started = false

    /** @param qaFixtures nur im Build-Typ `qa` wirksam: In-Memory-Speicher + synthetische Fixtures. */
    fun startBoot(qaFixtures: Boolean) {
        if (started) return
        started = true
        val qa = BuildConfig.QA_FIXTURES && qaFixtures
        scope.launch {
            try {
                val app = withContext(Dispatchers.IO) {
                    val db = PhotoMaidDatabase.open(this@PhotoMaidApplication, inMemory = qa)
                    AppModel(this@PhotoMaidApplication, db, isQAFixtureMode = qa).also { it.loadBlocking() }
                }
                app.start()
                if (qa) QAFixtures.install(app)
                _boot.value = BootState.Ready(app)
            } catch (e: Exception) {
                _boot.value = BootState.Failed
            }
        }
    }
}

