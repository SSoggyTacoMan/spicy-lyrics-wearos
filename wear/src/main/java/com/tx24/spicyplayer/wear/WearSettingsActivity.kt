package com.tx24.spicyplayer.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.tx24.spicyplayer.wear.data.WatchRepository
import com.tx24.spicyplayer.wear.settings.Alignment
import com.tx24.spicyplayer.wear.settings.BackgroundStyle
import com.tx24.spicyplayer.wear.settings.EffectLevel
import com.tx24.spicyplayer.wear.settings.TextSize
import com.tx24.spicyplayer.wear.settings.WearSettings
import com.tx24.spicyplayer.wear.ui.theme.LocalAccent
import com.tx24.spicyplayer.wear.ui.theme.SpicyWearTheme
import com.tx24.spicyplayer.wear.ui.theme.songAccent
import com.tx24.spicyplayer.wear.ui.theme.WearColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The watch's own settings: how the lyrics read on this screen. They live on the watch, since the
 * screen is the watch's, and keep while the phone is away.
 */
class WearSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = WearSettings(this)
        val repository = WatchRepository.of(this)
        setContent {
            val state by repository.state.collectAsStateWithLifecycle()
            SpicyWearTheme(songAccent(state.track?.colors.orEmpty())) {
                SettingsScreen(settings) { finish() }
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: WearSettings, onDone: () -> Unit) {
    AppScaffold {
        val listState = rememberScalingLazyListState()
        ScreenScaffold(scrollState = listState, timeText = { TimeText() }) { padding ->
            // A plain read is enough: every setting writes through to the preferences at once.
            var size by remember { mutableStateOf(settings.textSize) }
            val portedSettings = settings
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                item { ListHeader { Text("Lyrics") } }
                item {
                    CycleButton(
                        label = "Text size",
                        value = size.label,
                        onClick = {
                            size = next(TextSize.entries, size)
                            portedSettings.textSize = size
                        },
                    )
                }
                item {
                    var alignment by remember { mutableStateOf(settings.alignment) }
                    CycleButton(
                        label = "Alignment",
                        value = alignment.label,
                        onClick = {
                            alignment = next(Alignment.entries, alignment)
                            portedSettings.alignment = alignment
                        },
                    )
                }
                item {
                    var effects by remember { mutableStateOf(settings.effects) }
                    CycleButton(
                        label = "Motion",
                        value = effects.label,
                        onClick = {
                            effects = next(EffectLevel.entries, effects)
                            portedSettings.effects = effects
                        },
                    )
                }
                item {
                    var background by remember { mutableStateOf(settings.background) }
                    CycleButton(
                        label = "Background",
                        value = background.label,
                        onClick = {
                            background = next(BackgroundStyle.entries, background)
                            portedSettings.background = background
                        },
                    )
                }
                item {
                    Toggle("Moving background", settings.motion) { settings.motion = it }
                }
                item { ListHeader { Text("Under the lyrics") } }
                item { Toggle("Romanization", settings.romanization) { settings.romanization = it } }
                item { Toggle("Translation", settings.translation) { settings.translation = it } }
                item { Toggle("Background vocals", settings.backgroundVocals) { settings.backgroundVocals = it } }
                item { ListHeader { Text("Screen") } }
                item { Toggle("Keep the screen on", settings.keepScreenOn) { settings.keepScreenOn = it } }
                item { Toggle("Tap a line to seek", settings.tapToSeek) { settings.tapToSeek = it } }
                item {
                    var delay by remember { mutableIntStateOf(settings.delayMs) }
                    CycleButton(
                        label = "Lyrics delay",
                        value = if (delay == 0) "None" else "${if (delay > 0) "+" else ""}$delay ms",
                        onClick = {
                            delay = when {
                                delay >= 500 -> -500
                                else -> delay + 50
                            }
                            portedSettings.delayMs = delay
                        },
                    )
                }
                item {
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxSize(0.92f),
                        colors = ButtonDefaults.buttonColors(containerColor = WearColors.SurfaceHigh, contentColor = WearColors.Text),
                    ) {
                        Text("Done", textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var value by remember { mutableStateOf(checked) }
    SwitchButton(
        checked = value,
        onCheckedChange = {
            value = it
            onChange(it)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxSize(0.92f),
        colors = androidx.wear.compose.material3.SwitchButtonDefaults.switchButtonColors(
            checkedContainerColor = LocalAccent.current,
            checkedContentColor = Color.Black,
            uncheckedContainerColor = WearColors.SurfaceHigh,
            uncheckedContentColor = WearColors.Text,
        ),
    )
}

@Composable
private fun CycleButton(label: String, value: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(0.92f),
        colors = ButtonDefaults.buttonColors(containerColor = WearColors.SurfaceHigh, contentColor = WearColors.Text),
        label = { Text(label) },
        secondaryLabel = { Text(value, color = LocalAccent.current) },
    )
}

private fun <T> next(values: List<T>, current: T): T = values[(values.indexOf(current) + 1).mod(values.size)]
