package com.tx24.spicyplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import com.tx24.spicyplayer.ui.components.SettingRow
import com.tx24.spicyplayer.ui.components.ToggleRow
import com.tx24.spicyplayer.ui.theme.SpicyColors
import com.tx24.spicyplayer.ui.theme.SpicyType
import com.tx24.spicyplayer.wear.WearBridge
import kotlinx.coroutines.delay

/**
 * Settings → Device → Watch: whether the phone sends the song and its lyrics to the Spicy Lyrics
 * watch app, and how they arrive on the wrist.
 *
 * The watch looks after its own screen: its text size, alignment and background are set there
 * (the lyrics' button in the transport opens them). What the phone decides is what it sends.
 */
@Composable
internal fun WatchContent() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(WearBridge.enabled) }
    var romanization by remember { mutableStateOf(WearBridge.romanization) }
    var paired by remember { mutableStateOf(WearBridge.paired) }
    var open by remember { mutableStateOf(WearBridge.watchOpen) }
    // The connection has no callback of its own: it's read while the page is open.
    LaunchedEffect(Unit) {
        while (true) {
            paired = WearBridge.paired
            open = WearBridge.watchOpen
            delay(1_000)
        }
    }
    ToggleRow(
        label = "Send lyrics to the watch",
        checked = enabled,
        onCheckedChange = {
            enabled = it
            WearBridge.setEnabled(it)
            WearBridge.init(context)
        },
        description = "The Spicy Lyrics watch app shows what's playing here, word by word, with the transport on your wrist.",
        icon = Icons.Rounded.DevicesOther,
    )
    SettingRow(
        label = "Watch",
        description = when {
            !enabled -> "Off: nothing is sent."
            open -> "Open now: the watch has the lyrics."
            paired -> "Found on your watch, closed for now."
            else -> "Install the Spicy Lyrics watch app from the Play Store on your watch to see the lyrics there."
        },
        icon = Icons.Rounded.DevicesOther,
        enabled = enabled,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(if (open) SpicyColors.Accent else if (paired) SpicyColors.TextSecondary else SpicyColors.TextTertiary, CircleShape),
        )
    }
    ToggleRow(
        label = "Romanize for the watch",
        checked = romanization,
        onCheckedChange = {
            romanization = it
            WearBridge.setRomanization(it)
        },
        description = "Read the lyrics' pronunciation under Japanese, Chinese or Korean lines. The watch can also ask for this itself.",
        icon = Icons.Rounded.DevicesOther,
        enabled = enabled,
    )
    MoreOptions("What the watch is sent, and how it stays in time") {
        Text(
            text = "The phone finds the lyrics and sends them with the song's position; the watch keeps time from " +
                "its own clock, so it stays in step even with the phone in a pocket. A song's lyrics are only " +
                "looked up while the watch app is open, and nothing is sent while it's closed.",
            style = SpicyType.Caption,
            color = SpicyColors.TextSecondary,
        )
    }
}
