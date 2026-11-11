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

## Patched YouTube Music (Morphe / ReVanced)

Supported. Patched builds use a different package name than the official app, so Drive Music finds them automatically (patched builds are preferred over the official one if both are installed). You can check or change the app under **Settings → Change music app**. If MicroG (GmsCore) is installed, it gets its own battery item on the checklist: if Samsung puts MicroG to sleep, the patched app can lose sign-in and stop streaming.

## Phone security (Play Protect / Samsung)

The app is built to be as boring as possible to security scanners:

- **No internet permission**: it physically cannot send data anywhere.
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

### Stable signing key (optional, avoids uninstall-before-update)

By default each CI build is signed with a throwaway debug key, so you must uninstall before installing a new build. To fix that, create a keystore once and add these repository secrets (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64` (the `.jks` file, base64-encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Never commit the keystore to the repo.

```
keytool -genkeypair -v -keystore drive-music.jks -alias drivemusic -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 drive-music.jks   # paste the output into KEYSTORE_BASE64
```

## Install

1. On GitHub open **Actions → Build APK →** the latest successful run → download **DriveMusic-apk** (a zip containing `app-release.apk`).
2. Copy/open the APK on your phone. Allow "Install unknown apps" for your browser or file manager when asked.
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
