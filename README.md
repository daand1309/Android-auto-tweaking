# Drive Music

A small Android app (built for a Samsung phone + Android Auto + YouTube Music) that:

1. **Starts YouTube Music automatically** when Android Auto connects. It opens the app *and* presses play, retrying for ~20 seconds until music is actually playing.
2. **Auto-resumes** if the music pauses while you drive. It waits a few seconds and won't resume during calls, while Google Assistant is speaking, or when another app is playing.
3. **Logs everything** that happens during a drive, with a best guess at *why* each pause happened (navigation prompt, Assistant, notification, call, lost internet, Bluetooth drop, app killed by battery saver...). You can share the log from the app.
4. **Setup checklist** showing which phone settings still need fixing (battery restrictions for YouTube Music / Android Auto / this app), with buttons that open the right settings page.

## Extras

- **Smart auto-resume**: if you pause again within a minute of an auto-resume (and nothing else explains the pause), it assumes you meant it and stays off until the next drive. It never resumes during a call, while the Assistant talks, or while another app plays.
- **Quick Settings tile** "Auto-resume": swipe down → edit (pencil) → drag the tile in. Tap it to switch auto-resume on/off.
- **Won't hijack**: if another app (podcast, audiobook) is already playing when you connect, it leaves it alone.
- **Pause when leaving the car** (on by default) so music doesn't carry on from the phone speaker.
- **Set media volume on connect** (optional) and **wait after connecting** delay.
- **Last drive summary** on the main screen (duration, pauses, auto-resumes, last pause reason).
- **Log viewer**: colour-coded (red = problems, green = success, blue = drive start/end) with a "Problems only" filter.

## Drive Mode (phone, big buttons)

A full-screen, high-contrast dashboard for the phone in its holder: large Previous / Play-Pause / Next, now-playing, a Welcome button and the auto-resume toggle. It keeps the screen awake and hides the system bars. Open it from the app's **🚗 Open Drive Mode** button.

> Note on Android Auto's split screen (media left, navigation right): that left pane is Android Auto's media card, owned by the current media app (YouTube Music), so a utility app like this can't dock into it. Our Android Auto screen opens full-screen instead; for side-by-side controls, use the phone's Drive Mode.

## On the car screen (Android Auto)

Drive Music adds a templated screen to the Android Auto display showing **now playing**, **status** and **last drive**, with **Play**, **Welcome** (replay the greeting) and **Auto-resume on/off** buttons.

- Android Auto only allows safety-approved templates (lists, rows, buttons), so this is an info-and-controls screen, not a custom dashboard, and it can't put text on the map screen.
- Enable it once (the app's **Car screen** page has these steps and a button that opens Android Auto's settings):
  1. Android Auto settings → tap **Version** about 10 times → OK.
  2. ⋮ → **Developer settings** → tick **Unknown sources**.
  3. Back → **Customise launcher** → tick **Drive Music**.
  4. Reconnect the car.
- This is personal/developer mode; publishing a car app to everyone would need Google's review.
- The screen refreshes every few seconds while it's open. It does **not** change the head unit's own boot/splash screen — that's the car kit's firmware, unrelated to this app.

## Welcome greeting

When Android Auto connects, a voice welcomes you by name (e.g. "Good morning, Mr. Smith. Welcome back."), adds two random lines from a list of 25 (plus day/time-specific ones), reads the weather and road conditions, says which song is up first, and then starts your music. English or Dutch.

- **Road conditions** come from live weather data: black ice / near-freezing, snow, fog and visibility, heavy rain (aquaplaning), strong gusts, and whether it's dark or the sun sets soon. (Traffic jams aren't included: there's no free Dutch traffic feed apps may use without a key.)
- **Song**: "Up first: <title> by <artist>" when YouTube Music already has a song queued; otherwise "Now playing: ..." is announced a few seconds after the music starts.

- Set your name, language and weather location under **Welcome greeting** in the app, and tap **Test greeting now** to hear it.
- Weather comes from [Open-Meteo](https://open-meteo.com) (free, no account). Only an approximate location (rounded to ~10 km) is sent. Location is set from the app ("Use my location" or "Type a city") and is never tracked in the background.
- Uses the phone's text-to-speech (Google's engine if installed, otherwise the phone default), picks the best installed offline voice for the language automatically, and plays through the car like a navigation prompt. If the voice isn't available, music still starts.
- **Voice & pronunciation** settings: choose a voice (with preview), speed and pitch, and "Pronounce name as" for a phonetic spelling. Titles are spoken properly ("Mr." → "Mister" / "meneer"), negative temperatures as "minus"/"min", times as "5:05 PM" / "17.05 uur", and YouTube song titles are cleaned ("(Official Video)", "[4K]", "- Topic", "VEVO", emojis, "feat." → "featuring"). Sentences are spoken one at a time with short pauses.
- Not repeated if Android Auto reconnects within 10 minutes.

## Backup, crashes and maintenance

- **Backup & restore** saves your settings (name, voice, location, toggles) to a JSON file and restores them after a reinstall or on a new phone. Run-time state such as the last drive isn't included.
- If the app ever crashes, the error is written to the drive log as a `CRASH` line (shown in red under **Problems only**), so it can be shared and fixed.

## Patched YouTube Music (Morphe / ReVanced)

Supported. Patched builds use a different package name than the official app, so Drive Music finds them automatically (patched builds are preferred over the official one if both are installed). You can check or change the app under **Settings → Change music app**. If MicroG (GmsCore) is installed, it gets its own battery item on the checklist: if Samsung puts MicroG to sleep, the patched app can lose sign-in and stop streaming.

## Phone security (Play Protect / Samsung)

The app is built to be as boring as possible to security scanners:

- **Internet is only used by the built-in updater** to check this app's own GitHub Releases (api.github.com / github.com). No data about you, your music or your logs is sent. You can turn automatic checks off.
- **Updates are verified before installing**: HTTPS from GitHub only, SHA-256 checksum must match, same package, newer version, and signed with the same key as the installed app. Android always shows its own "Update" confirmation.
- **No** overlay, accessibility, location, contacts, SMS, storage or "ignore battery optimisation" permissions.
- Notification access is only used to control media; notification contents are never read or logged.
- The only exported components are protected by system-only permissions (notification listener, Quick Settings tile) and the launcher screen.

What you may see when installing, and what to do:

| Prompt | What to do |
|---|---|
| **Auto Blocker** blocks the install (Samsung) | Settings → Security and privacy → Auto Blocker → turn off, install, then turn it back on. (Needed again for each update.) |
| **Play Protect**: "Unsafe app blocked" / "Send app for scanning?" | Tap *Scan app* - it has nothing to flag. If it still asks: *More details → Install anyway*. |
| Notification access switch is **greyed out / "Restricted setting"** | Settings → Apps → Drive Music → ⋮ (top right) → *Allow restricted settings*, then grant Notification access. The app has an "App info" button for this. |
| "**Unverified developer**" (Android developer verification, rolling out from late 2026) | Use Android's *advanced flow* for installing unverified apps (one-time, includes a 24 h wait), or install via a computer with `adb install`. |

## Updates

The app updates itself from this repo's **GitHub Releases**:

- Every push to the default branch that passes **all** tests is published as a release (`v1.<build number>`) with the APK and its SHA-256 checksum. Releases are only made when the permanent signing key secrets are set.
- The app checks once a day (never while driving) and when opened, shows a notification, and installs with one tap (Android asks you to confirm).
- First time: tap **Install** → allow **Install unknown apps** for Drive Music → tap **Install** again.

Requirements: the repository must be **public** (so the app can read releases without a token) and the signing key secrets below must be set.

### Signing key (required for updates)

Android only installs an update if it's signed with the same key as the installed app. Create a keystore once and add these repository secrets (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64` (the `.jks` file, base64-encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Never commit the keystore to the repo.

```
keytool -genkeypair -v -keystore drive-music.jks -alias drivemusic -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 drive-music.jks   # paste the output into KEYSTORE_BASE64
```

## Install

1. On your phone open this repo's **Releases** page and download **DriveMusic.apk** from the latest release. (Builds without the permanent key are only available as a zip under **Actions → Build APK → latest run → DriveMusic-apk**.)
2. Open the APK on your phone. Allow "Install unknown apps" for your browser or file manager when asked.
3. Open **Drive Music** and work through the checklist until everything shows ✅. **Notification access** is the only required one: it is what lets the app control YouTube Music and keep running in the background.
4. Tap **▶ Test: start YouTube Music now** to check it works.

## Your Samsung routine

You can keep your Modes & Routines "open YouTube Music" routine; this app presses play after it opens. Remove any "play/pause media" step from the routine so the two don't fight. Keeping the routine is recommended: Android doesn't let background apps open other apps, so the routine opening YouTube Music + this app pressing play is the most reliable combo.

## When something goes wrong

Open the app → **View / share logs** → **Share**, and send the file. Lines to look for:

| Log line | Meaning / fix |
|---|---|
| `PAUSE ... Clues: other audio playing: NAVIGATION voice` | Maps/Waze voice prompt paused the music. Change Maps voice settings. |
| `... GOOGLE ASSISTANT` | "Hey Google" was triggered (often road noise). Turn off or lower its sensitivity. |
| `... NOTIFICATION sound` | A notification interrupted. Mute notifications in Android Auto. |
| `... internet dropped` / `NO INTERNET` | Signal lost. Download playlists / turn on Smart downloads. |
| `YouTube Music session destroyed` | Samsung killed the app. Set battery to Unrestricted (also for MicroG) and add it to "Never sleeping apps". |
| `CAR: disconnected` mid-drive | Android Auto connection dropped (try another USB cable, or check wireless AA). |
| `Clues: none found` | Probably a real pause press (steering wheel, screen) or YouTube Music itself. |

## Build it yourself

`./gradlew assembleRelease` (needs JDK 17+ and the Android SDK). The APK is signed with the debug key so it can be sideloaded.

## Tests

Every push runs three GitHub Actions jobs:

- **build**: builds the APK, runs Android lint and writes the SHA-256 checksum.
- **unit-tests** (Robolectric): music app detection (official vs Morphe/patched), MicroG detection, settings, log rotation.
- **emulator-tests**: runs the real background service on an Android 14 emulator against a fake YouTube Music player: test button, start on car connect, auto-start off, pause logging + auto-resume, auto-resume off, "paused on purpose" rule, pause when leaving the car, and the screens opening. Android Auto itself can't run on an emulator, so the car connection is simulated through the same code path.

- **release**: publishes the GitHub Release used by the updater (only if all the above pass).

Unit tests also cover the updater's safety checks (version parsing, GitHub-only hosts, release/checksum parsing).

Run locally with `./gradlew testDebugUnitTest` and (with an emulator/phone attached) `./gradlew connectedDebugAndroidTest`.
