package app.photomaid

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.memoryCacheMaxSizePercentWhileInBackground
import coil3.request.CachePolicy
import coil3.video.VideoFrameDecoder
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
class PhotoMaidApplication : Application(), SingletonImageLoader.Factory {
    /**
     * Bildlader: Zielgrößen-Dekodierung (nie Originale), EXIF-Rotation, HEIF/WebP über ImageDecoder,
     * Video-Einzelbilder, begrenzter Speichercache (kleiner im Hintergrund). Kein Disk-Cache: private Medien
     * werden nicht zusätzlich auf den Datenträger kopiert (Thumbnails stammen aus MediaStore/Speicher).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(app.photomaid.media.VideoPosterFetcher.Factory()); add(VideoFrameDecoder.Factory()) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .memoryCacheMaxSizePercentWhileInBackground(0.5)
        .diskCachePolicy(CachePolicy.DISABLED)
        .build()

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

