# Drive Music – how the app works

A guide for a new user. Written for a Mitsubishi ASX (2011, 1.6 MIVEC) with a Senzo head unit,
Android Auto and patched YouTube Music, but most of it works in any car with Android Auto.

---

## 1. What it is

Drive Music runs quietly in the background on your phone. When Android Auto connects in the car it:

- **starts your music** (YouTube Music) by itself;
- **keeps it playing**: if something pauses it (a glitch, an app, a lost connection) it puts it back on
  a few seconds later, but never during a call or while the Google Assistant is talking;
- **greets you** like a butler or a cheeky friend: time of day, weather, road conditions, which song is up;
- **keeps you posted during the drive**: weather turning, closed roads or major news, and asks first;
- with an **OBD adapter** (Vgate iCar Pro 2S) it also **reads the car**: temperature, battery, fuel,
  warning lights and fault codes explained in plain words, trips and costs, and early warnings
  before something breaks.

When you park, it pauses the music, writes down how the drive went and (optionally) where you parked.

---

## 2. Installing and first setup

**Installing.** Install the APK from the app's GitHub Releases page. After that the app updates
itself: it checks daily and asks before installing a new version.

**The Setup checklist.** The home screen shows "Setup: N step(s) left" until everything is done.
Tap it. Each item has a button that opens the right Android setting:

| Step | Why |
|---|---|
| **Notification access** (required) | This is how the app sees and controls your music, and it keeps the app running. |
| **Drive Music: battery Unrestricted** | So Android doesn't put it to sleep. On Samsung also check *Settings → Battery → Background usage limits*: Drive Music must not be under *Sleeping apps*. |
| **YouTube Music: battery Unrestricted** | Same for the music app. |
| **Android Auto: battery Unrestricted** | Same for Android Auto. |
| **MicroG: battery Unrestricted** | Only with patched YouTube Music: keeps you signed in. |
| **Keep permissions when unused** | Stops Android from switching the app off if you don't open it for a while. |
| **Notifications** | For the Drive Mode pop-up and update questions. |
| **Nearby devices** (optional) | Shows your car's Bluetooth name in the log. Required for the OBD adapter. |

You do **not** need a routine or automation to start the app: it starts by itself when Android Auto connects.

**Android Auto car screen.** Android Auto hides apps that don't come from the Play Store. To see
Drive Music on the car screen: *Settings → Car screen* in the app explains the steps (developer
settings → *Unknown sources*, then *Customise launcher* → tick Drive Music).

**Personalising** (all optional), from the home screen:

- *Welcome greeting*: your name, birthday, Dutch or English, weather and roads, announcing the first song.
  **Set your town (Location)** here – weather is on by default but needs a place; until then the
  greeting has no weather and the home screen's Weather tile says *Set your town*.
- *Voice & style*: **Sassy** (cheeky one-liners) or **Butler** (refined, British); pick a voice and hear it.
- *Music & driving*: which music app, how long to wait before starting/resuming, volume on connect.
- *Drive Mode*: open it automatically when the car connects.

---

## 3. Getting in the car

What happens, in order:

1. **Android Auto connects.** The app notices within a second or two.
2. **The greeting** (on by default) is spoken first, about 2 s after connecting. While it speaks, the
   car screen shows it as a pop-up, which disappears when it's finished. A greeting takes about 15–20 s.
3. **Music starts** as soon as the greeting is done – so on a normal start, roughly 20 s after
   connecting (2 s if the greeting is off). Then the first song is announced ("Now playing: …").
4. **Drive Mode** opens on the phone if you switched that on.
5. **During the drive:**
   - a pause is undone after the resume delay (default 8 s) – shown in the log as an auto-resume;
   - a pause you make yourself (pause button in the car or in Drive Mode) is respected;
   - phone calls: music pauses for the call and comes back after it;
   - from about 12 minutes in, every 15 minutes: weather/road changes and important news (see 5).
6. **Stalling.** Restarting the engine can make the radio reboot and Android Auto drop out. The app
   waits for it to come back (up to 90 s, longer with the OBD adapter, which can tell whether the
   ignition is really on) and carries on with the same drive.
7. **Leaving the car.** Music pauses, the drive is written down ("Last drive" on the home screen),
   and – if switched on – the parking spot is saved.

---

## 4. The screens

**Home.** Your car photo, then *Drive Mode*, *Start music* and *Greeting* buttons and four tiles:
*Now playing*, *Weather*, *Last drive*, *Your car* (where you parked). With the OBD adapter two more:
*Engine* temperature and *Battery* (or the fault code if there is one). Below that, the menu.

**Drive Mode** (full-screen, keeps the screen on, two tabs you can swipe between):

- **Music tab:** the song, huge previous / play-pause / next buttons, and *Welcome* (hear the greeting
  again), *Resume on/off* (auto-resume for this drive) and *Close*.
- **Car tab** (with the OBD adapter): speed, RPM, engine temperature (blue cold / green good / red hot),
  fuel use now, this trip (km and L/100), battery, range left, outside and intake temperature.
  Warnings appear on top: amber = get it checked, red = stop or be careful.
- **The question:** when there's an update (see 5) a big green *Go ahead* / red *No thanks* appears.

Landscape works too (side by side). Controls dip slightly and give a short vibration when pressed.

**Android Auto car screen** (*Drive Music* in the car's app list): play, *Welcome briefing*,
*Auto-resume* on/off, *Weather*, *Any news?*, and *Car status* (with the OBD adapter). Update
questions appear there as a pop-up with *Go ahead* / *Not interested*.

---

## 5. The butler

**Styles.** *Sassy* is playful; *Butler* is a refined British gentleman ("Good morning, sir…") who
uses "sir" two or three times per greeting, not every sentence. English or Dutch.

**The greeting** includes some of: time of day, your name, birthday, the weather for the next hours
(once your town is set), road conditions (ice, fog, gusts), a service reminder or early warning about
the car if there is one, and a few playful lines. The very first one says "Welcome", after that
"Welcome back". The first song is announced right after the music starts.

**Updates during the drive** (*Updates* menu):

- *Weather or roads change*: rain or snow starting, ice, fog, strong gusts.
- *Breaking news*: only what you need to know in the Netherlands – alerts, disasters, big outages,
  closed motorways, major politics. Sport, celebrities, animals and other soft news are filtered out.
  At most 3 per drive, never during a call. Only trusted Dutch newsrooms (NU.nl, NOS, NL Times, regional).
- *Follow a story*: give keywords (e.g. "kabinet, coalitie") and new headlines about it are told
  during your drives for 3 days.
- *Ask first*: the butler politely asks ("I do apologise, sir, but there is an update…") and you
  answer with the green/red buttons on the phone or car screen. No answer within 10 s = go ahead.

---

## 6. Car data with the OBD adapter

**Setting it up.** Plug the Vgate iCar Pro 2S into the OBD port (under the dashboard), ignition on,
pair "Android-Vlink" in the phone's Bluetooth settings (PIN 1234). In the app: *Car data (OBD)* →
switch it on → *Choose* the adapter → *Test connection*. Set the engine size (1.6 L), and under
*Your car* the tank size (60 L) and fuel price for costs.

**Read-only.** The app only ever *asks* the car questions. It never clears fault codes or changes
anything – that's enforced at the lowest level, before anything is sent.

**What it tells you** (spoken, at a safe moment):

- **Ready to drive**: after a cold start, when the engine has idled long enough (30 s, longer in the cold).
- **Engine warm**, **low fuel** (with the range left), **icy roads** (from the car's outside temperature).
- **Overheating**, **check-engine light** with the fault code explained in plain words and what to do
  (drive gently / get it checked soon / stop when safe). About 120 codes are explained, including the
  ASX/4A92-specific ones; unknown codes are explained by their area.
- **Weak battery or charging**, **thermostat stuck open**.

A faulty reading can't trigger a warning: values must make physical sense and hold for a while, and
fault codes must agree with the car's own count or keep coming back over several reads.

**Trips & costs.** Every drive with the adapter is saved on the phone: distance, fuel, L/100,
costs, duration and a **driving score** (0–100, just for fun: only hard acceleration/braking, revving
hard and long idling cost points – not the motorway, traffic or the warm-up). After parking a quiet
notification sums it up. The *Trips & costs* page shows this week, month and all time.

**Early warnings.** The app learns what's normal for your car and warns when something slowly changes,
before a warning light: fuel mixture drifting (air leak, sensors), charging voltage sagging
(alternator), idle getting shaky (plugs, coils), slower warm-up (thermostat), running hotter on the
road (cooling). Also *pending* fault codes – ones the car noticed but hasn't confirmed yet. It needs
about 8 drives to judge. Told once after parking and in the next greeting; then at most every 2 weeks.

**Service reminder.** Type in what the car's dashboard says (km and time left) and the app counts down
with your drives, so both remind you together. Or set the last service date and interval.

---

## 7. Other features

- **Where's my car**: switch on *Remember where I park* – the spot is saved when you leave the car
  and deleted when you drive again. Opens in Maps or your tracker app (e.g. SmartThings Find).
- **Backup & restore**: save your settings to a file and restore them on a new phone.
- **Updates**: daily check on GitHub, asks before installing.
- **Logs**: what happened on each drive (pauses, resumes, greetings, car data). Share it when
  something goes wrong – your VIN and exact location are never in it.

---

## 8. Privacy and safety

- Internet is used only for: update checks (GitHub), weather (Open-Meteo, location rounded to ~10 km),
  and – if on – news (Dutch newsrooms; Google News is searched for your town name only).
- Logs, trips, photos and your parking spot stay on the phone unless you share them.
- No ads, no tracking, no accessibility or overlay permissions, no access to contacts or SMS.
- Towards the car: read-only, always.

---

## 9. Theory vs practice

The guide above was written first (theory). Then a new user was played on an Android 14 emulator:
a clean install, following only what the screens say, then a first drive with Android Auto
simulated and a stand-in for YouTube Music. Every step was photographed and logged
(`NewUserJourney` in the app's tests; it runs in CI on every change).

**What matched**

| Step | Theory | Practice |
|---|---|---|
| First open | Setup checklist with buttons to Android settings | ✓ "Setup: 5 step(s) left"; 7 items, each with an *Open* button |
| Granting access | App starts working once notification access is on | ✓ monitor running within seconds; checklist ticks update |
| Defaults | Auto-start, auto-resume (8 s), greeting on, sassy, Drive Mode not automatic, OBD off | ✓ exactly these |
| Car connects | Greeting, shown on the car screen, then music | ✓ greeting spoken, car-screen pop-up shown and removed, music playing |
| Pause mid-drive | Back on by itself after ~8 s | ✓ playing again 11 s after the pause (8 s wait + detection) |
| Home while driving | Status "Driving", Now playing tile | ✓ "Driving · Android Auto connected", "Mr. Brightside – The Killers" |
| Drive Mode | Song, big buttons, Welcome / Resume / Close; Car tab | ✓ all there; Car tab without adapter shows dashes and "Set up Car data (OBD)…" |
| Leaving the car | Music pauses, drive written down | ✓ paused immediately; Last drive tile "Under a minute" |
| Car data page (new) | Switch it on, then choose the adapter | ✓ explanation and the switch |

**What didn't match – and what was done**

| Found | Fix |
|---|---|
| The guide said music starts ~2 s after connecting; in practice ~20 s, because the greeting comes first | Guide corrected (section 3). Behaviour is as designed. |
| Weather is on by default but needs a town; without it the greeting silently had no weather, and the Weather tile kept promising "After your next drive" | App: tile now says *Set your town*, the greeting menu says "set your town for weather". Guide: says so in sections 2 and 5. |
| A brand-new user's very first greeting said "Welcome back" | App: first greeting says "Welcome" / "Welcome aboard, sir". |
| The setup reminder sat below the buttons and the update card – easy to miss on first open | App: setup (and "music app not found") now come first on the home screen. |
| Last-drive pop-up said "0 min" while the tile said "Under a minute" | App: says "under a minute" everywhere. |
| Help page listed fewer news sources than the app uses | App: now names NU.nl, NOS, NL Times and regional newsrooms. |

**Not tested in practice yet** (needs the real car): the real Senzo head unit and Android Auto, the
car-screen app list, the OBD adapter with the ASX, stalling and the radio rebooting, phone calls,
breaking news during a drive. These are covered by automated tests with simulated parts, but a real
drive is the final check – the first drive log with the iCar will tell.
