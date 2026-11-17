package com.tx24.spicyplayer.wear

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.ambient.AmbientLifecycleObserver.AmbientLifecycleCallback
import com.tx24.spicyplayer.wear.data.WatchRepository
import com.tx24.spicyplayer.wear.settings.WearSettings
import com.tx24.spicyplayer.wear.ui.WearApp
import com.tx24.spicyplayer.wear.ui.theme.SpicyWearTheme
import com.tx24.spicyplayer.wear.ui.theme.songAccent
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The one screen: lyrics, the transport over them, and the settings behind a button.
 *
 * Ambient mode keeps the current line, plain white on black, without the sweep or the moving
 * background: a watch's dimmed screen repaints rarely, and a line that lags a beat reads better
 * than a flicker.
 */
const val ACTION_DEMO = "com.tx24.spicyplayer.wear.DEMO"

class WearActivity : ComponentActivity() {
    private val ambient = MutableStateFlow(false)
    private var ambientObserver: AmbientLifecycleObserver? = null
    private var repository: WatchRepository? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val repo = WatchRepository.of(this)
        repository = repo
        val settings = WearSettings(this)
        repo.romanizationWanted = settings.romanization
        repo.start()
        // Debug builds: `adb shell am start -a com.tx24.spicyplayer.wear.DEMO` shows a made-up song.
        if (intent?.action == ACTION_DEMO) repo.startDemo()

        ambientObserver = AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: androidx.wear.ambient.AmbientLifecycleObserver.AmbientDetails) {
                    ambient.value = true
                }

                override fun onExitAmbient() {
                    ambient.value = false
                }

                override fun onUpdateAmbient() {
                    // The clock and the line follow from the position, which the loop re-reads.
                }
            },
        ).also { lifecycle.addObserver(it) }

        lifecycleScope.launch {
            while (true) {
                repo.tickDemo()
                delay(120)
            }
        }

        setContent {
            val state by repo.state.collectAsStateWithLifecycle()
            val isAmbient by ambient.collectAsStateWithLifecycle()
            val accent = songAccent(state.track?.colors.orEmpty())
            SpicyWearTheme(accent) {
                WearApp(
                    state = state,
                    repository = repo,
                    settings = settings,
                    ambient = isAmbient,
                    onOpenSettings = { startActivity(Intent(this, WearSettingsActivity::class.java)) },
                )
            }
            // The screen stays up while the lyrics are read, unless the watch is told otherwise.
            LaunchedEffect(state.clock?.playing, settings.keepScreenOn, isAmbient) {
                val keep = settings.keepScreenOn && state.clock?.playing == true && !isAmbient
                if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        repository?.start()
        repository?.askForState()
    }

    override fun onStop() {
        super.onStop()
        repository?.stop()
        repository?.command(com.tx24.spicyplayer.wear.proto.WearCommandType.BYE)
    }
}
