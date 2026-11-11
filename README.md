# Drive Music

A small Android app (built for a Samsung phone + Android Auto + YouTube Music) that:

1. **Starts YouTube Music automatically** when Android Auto connects. It opens the app *and* presses play, retrying for ~20 seconds until music is actually playing.
2. **Auto-resumes** if the music pauses while you drive. It waits a few seconds and won't resume during calls, while Google Assistant is speaking, or when another app is playing.
3. **Logs everything** that happens during a drive, with a best guess at *why* each pause happened (navigation prompt, Assistant, notification, call, lost internet, Bluetooth drop, app killed by battery saver...). You can share the log from the app.
4. **Setup checklist** showing which phone settings still need fixing (battery restrictions for YouTube Music / Android Auto / this app), with buttons that open the right settings page.

## Patched YouTube Music (Morphe / ReVanced)

Supported. Patched builds use a different package name than the official app, so Drive Music finds them automatically (patched builds are preferred over the official one if both are installed). You can check or change the app under **Settings → Change music app**. If MicroG (GmsCore) is installed, it gets its own battery item on the checklist: if Samsung puts MicroG to sleep, the patched app can lose sign-in and stop streaming.

## Install

1. On GitHub open **Actions → Build APK →** the latest successful run → download **DriveMusic-apk** (a zip containing `app-release.apk`).
2. Copy/open the APK on your phone. Allow "Install unknown apps" for your browser or file manager when asked.
3. Open **Drive Music** and work through the checklist until everything shows ✅. **Notification access** is the only required one: it is what lets the app control YouTube Music and keep running in the background.
4. Tap **▶ Test: start YouTube Music now** to check it works.

## Your Samsung routine

You can keep your Modes & Routines "open YouTube Music" routine; this app presses play after it opens. Remove any "play/pause media" step from the routine so the two don't fight. If you grant **Display over other apps**, this app can open YouTube Music itself and the routine isn't needed.

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
