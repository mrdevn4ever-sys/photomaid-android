package app.photomaid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.photomaid.design.AppBackground
import app.photomaid.design.ErrorState
import app.photomaid.design.PhotoMaidTheme
import app.photomaid.navigation.RootContent
import app.photomaid.system.AppEntry
import app.photomaid.system.SystemDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val trashLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        SystemDialogs.onResult(it.resultCode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        SystemDialogs.attach(this, trashLauncher)
        val application = application as PhotoMaidApplication
        // Nur im Build-Typ `qa` wirksam (in debug/release sind die Fixtures leer).
        QAFixtures.seedMedia = BuildConfig.QA_FIXTURES && intent.getBooleanExtra("qa-seed-media", false)
        application.startBoot(qaFixtures = intent.getBooleanExtra("qa-fixtures", false))
        if (savedInstanceState == null) readEntry(intent)
        setContent {
            val boot by application.boot.collectAsState()
            when (val b = boot) {
                is BootState.Ready -> {
                    val settings by b.app.settings.collectAsState()
                    PhotoMaidTheme(settings.appearance) { RootContent(b.app) }
                }
                BootState.Failed -> PhotoMaidTheme { AppBackground { ErrorState(stringResource(R.string.store_open_failed), Modifier.align(Alignment.Center)) } }
                BootState.Loading -> PhotoMaidTheme { Loading() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readEntry(intent)
    }

    /** App-Shortcut oder Teilen: Dateiangaben sofort lesen (Übergabe-Rechte gelten nur, solange diese Activity lebt). */
    private fun readEntry(intent: Intent?) {
        lifecycleScope.launch(Dispatchers.IO) { AppEntry.read(this@MainActivity, intent)?.let { AppEntry.post(System.identityHashCode(this@MainActivity), it) } }
    }

    override fun onDestroy() {
        SystemDialogs.detach(this)
        super.onDestroy()
    }
}

@Composable
private fun Loading() = AppBackground { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
