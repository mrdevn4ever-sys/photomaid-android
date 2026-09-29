package app.photomaid

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
import app.photomaid.design.AppBackground
import app.photomaid.design.ErrorState
import app.photomaid.design.PhotoMaidTheme
import app.photomaid.navigation.RootContent
import app.photomaid.system.SystemDialogs

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
        application.startBoot(qaFixtures = intent.getBooleanExtra("qa-fixtures", false))
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

    override fun onDestroy() {
        SystemDialogs.detach(this)
        super.onDestroy()
    }
}

@Composable
private fun Loading() = AppBackground { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
