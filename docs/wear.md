# The Wear OS app

`/wear` is the watch half of Spicy Lyrics: word-synced lyrics for whatever is playing on the
phone, with the transport on the wrist. The phone does the looking up; the watch draws.

- **Phone** (`:app`): `com.tx24.spicyplayer.wear.WearBridge` writes the song, its lyrics and the
  clock to the Wear OS Data Layer, and `WearCommandService` takes the watch's commands.
- **Shared** (`:wearproto`): the wire format (`WearCodec`), its models, the watch's clock
  (`WatchClock`, `ClockSync`) and the rows the watch scrolls (`WearTimeline`). Plain Kotlin, so
  its tests run without an Android SDK.
- **Watch** (`:wear`): `WatchRepository` reads the Data Layer, `LyricsCanvas` draws, and
  `WearSettings` keeps the watch's own display settings.

## What goes over the connection

| Path | Kind | What | When |
|---|---|---|---|
| `/spicy/track` | DataItem | the song, its cover and a still of the background | a new song, or a new cover |
| `/spicy/lyrics` | DataItem | the lines, word times included, and the credit | the lyrics arrive or change |
| `/spicy/clock` | DataItem + Message | position, speed and playing | play, pause, seek, and while the watch is open |
| `/spicy/cmd` | Message | play/pause, next, previous, seek, hello, bye, ping | from the watch |

DataItems wait for the watch when it isn't connected, so the tile and the next launch open on
the last song. Messages aren't queued: a stale clock is worse than none.

**Time.** The phone stamps every clock payload with its wall clock. The watch reads the song's
position from that stamp and its own clock, so Bluetooth delays don't shift the lyrics. A new
position that disagrees by less than 400 ms is eased in over 350 ms rather than jumped to.

**Nothing runs for no one.** The bridge is silent until the watch app says hello (or a watch
with it is found); the phone's holder follows the player only while a lyric widget is placed or
the watch app is open, and stops a minute after the watch's last heartbeat.

## Building and installing

```sh
./gradlew :app:assembleDebug :wear:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb connect <watch ip>:<port>
adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

The two APKs must share a package name (`com.tx24.spicyplayer.next`) and a signature, which the
debug builds do through the shared debug keystore. Enable Wi-Fi debugging on the watch
(Settings → Developer options → Wireless debugging) to install over the network.

The watch app is not standalone: it needs the phone app with notification access granted.

## The screen

- The line being sung sits a little above the middle at the watch's own text size (14–19.5 sp
  by screen width); the rest scale back to 84% and fade with distance.
- Words fill as they're sung, with a soft leading edge; the word being sung lifts 3 dp and
  settles. `Settings → Motion` turns this down (Lite) or off.
- Breaks in the singing show three dots that fill in turn, as the phone draws them.
- The crown or a drag scrolls the lyrics; after four seconds they return to the song. While
  browsing, a tap on a line seeks there; any other tap opens the transport.
- Page two is the song: cover, names, transport and the timeline.
- Ambient (wrist down) shows the current line plain white on black.

Settings are **on the watch** (the gear in the transport): text size, alignment, motion,
background (the song's colours, the blurred cover, or black), romanization, translation,
background vocals, keep screen on, tap to seek, a lyric delay, and the moving background.

## Debugging

`adb shell am start -a com.tx24.spicyplayer.wear.DEMO -n com.tx24.spicyplayer.next.debug/com.tx24.spicyplayer.wear.WearActivity`
opens a made-up song (`Demo.kt`) in debug builds, for looking at the screen with no phone.

Debug builds send nothing to analytics, and the bridge logs under `SpicyWear`.

## Not done yet

- The live tile and the watch-face complication read what the phone last sent and refresh on
  their own schedule (a tile cannot animate per word).
- Ongoing-activity integration, so the app returns with a tap on the watch face.
- Publishing to the Play Store needs the release keystore and a store listing.
