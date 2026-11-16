# Home Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the home tab's tiles + long settings list with a photo car card, a "Needs you" card, a grouped icon grid and settings search, built from one feature list; make themes data.

**Architecture:** Pure, unit-tested Kotlin objects decide *what* is shown (`HomeFeatures` = the feature list + search, `NeedsYou` = what waits for the user, `Style.Theme` = theme sets). `MainActivity.renderHome()` only draws them. The car card is a `FrameLayout` around the existing `CarPhotoView`.

**Tech Stack:** Kotlin, Android views built in code (no XML layouts), JUnit + Robolectric unit tests, instrumented tests on an Android 35 emulator.

**Spec:** `docs/superpowers/specs/2026-11-16-home-redesign-design.md`

## Global Constraints

- UI in code, no XML layouts; animations only through `Motion`.
- No tab bar. Back on any page → HOME; Back on HOME → finish.
- Grid groups and order exactly: Music: Music & driving, Greeting, Voice, Drive Mode · Car: Car data, Trips & costs, Service, Car screen · App: Themes, Setup, Updates, Backup, Logs, Help.
- Tappable things ≥ 44 dp; no ellipsized text at 1.5× font (`LargeTextTest`).
- Car photo never leaves the phone. Read-only OBD rule untouched.
- Icons: emoji glyphs in this change (builder's call per spec; vectors can replace them later via the feature list).
- Before push: `./gradlew testDebugUnitTest`, and the emulator suite where available (DeepSeek runs `connectedDebugAndroidTest`).

## Review Focus

1. **Search with junk or nothing** (`""`, spaces, "zzz", mixed case "PiTcH") → empty query shows the grid, no match shows "Nothing found", case ignored. Pinned in Task 1.
2. **Trips & costs when OBD is off** → icon still shown (page explains), not hidden; grid never has holes. Pinned in Task 1 (`all` is fixed).
3. **Needs-you with nothing pending** → card absent, not an empty box. Pinned in Task 2.
4. **No photo / no OBD / never driven** → car card keeps its size and shows "–" values. Checked in Task 4 screenshots (NewUserJourney `first_open`).
5. **Big font** → icon labels and state lines wrap to 2 lines, never cut. Covered by `LargeTextTest` in Task 6.

---

### Task 1: Feature list and search

**Files:**
- Create: `app/src/main/java/com/daand/drivemusic/HomeFeatures.kt`
- Test: `app/src/test/java/com/daand/drivemusic/HomeFeaturesTest.kt`

**Interfaces:**
- Produces: `enum class FeatureGroup(val en: String) { MUSIC("Music"), CAR("Car"), APP("App") }`;
  `data class Feature(val id: String, val group: FeatureGroup, val title: String, val icon: String, val words: List<String>)`;
  `object HomeFeatures { val all: List<Feature>; fun byGroup(): Map<FeatureGroup, List<Feature>>; fun search(query: String): List<Feature> }`.
  Ids: `music, greeting, voice, drive_mode, car_data, trips, service, car_screen, themes, setup, updates, backup, logs, help`.

- [ ] **Step 1: Write failing tests** `HomeFeaturesTest`:
  - `gridOrderIsTheDesigned` → `byGroup()[MUSIC]!!.map{it.id} == listOf("music","greeting","voice","drive_mode")`, CAR = `car_data, trips, service, car_screen`, APP = `themes, setup, updates, backup, logs, help`.
  - `idsAreUnique` → `all.map{it.id}.toSet().size == all.size`.
  - `searchFindsByOtherWords` → `search("pitch").first().id == "voice"`, `search("PiTcH")` same, `search("brandstof").first().id == "car_data"`, `search("fuel price").first().id == "car_data"`, `search("thema").first().id == "themes"`.
  - `searchFindsByTitle` → `search("trips").first().id == "trips"`.
  - `emptyOrJunkQuery` → `search("")` and `search("   ")` empty; `search("zzz")` empty.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*HomeFeaturesTest*'` → FAIL (unresolved reference).
- [ ] **Step 3: Implement** `HomeFeatures.kt`. Search: lowercase, trim, return features where the title or any word contains the query (title matches ranked before word matches). Each feature's `words` holds English + Dutch terms for the settings on its page (e.g. voice: pitch, speed, accent, butler, stem, toonhoogte; car_data: obd, adapter, battery, accu, fuel price, brandstof, tank, engine size, early warnings; service: onderhoud, beurt).
- [ ] **Step 4: Run** the same command → PASS.
- [ ] **Step 5: Commit** `Home: one feature list (grid order, icons, search words)`.

### Task 2: Needs you

**Files:**
- Create: `app/src/main/java/com/daand/drivemusic/NeedsYou.kt`
- Test: `app/src/test/java/com/daand/drivemusic/NeedsYouTest.kt`

**Interfaces:**
- Produces: `data class NeedsYou.State(val musicAppMissing: Boolean, val setupStepsLeft: Int, val adapterQuestion: Boolean, val updateVersion: String?, val faultCode: String?, val fullScreenMissing: Boolean)`;
  `data class NeedsYou.Item(val kind: Kind, val text: String, val featureId: String?)`; `enum Kind { MUSIC_APP, SETUP, ADAPTER, UPDATE, FAULT, FULL_SCREEN }`;
  `fun NeedsYou.items(s: State): List<Item>` (order: MUSIC_APP, ADAPTER, FAULT, SETUP, FULL_SCREEN, UPDATE);
  `fun NeedsYou.dotted(items: List<Item>): Set<String>` = featureIds with a dot.
  featureId mapping: MUSIC_APP→"music", SETUP→"setup", ADAPTER→"car_data", FAULT→"car_data", FULL_SCREEN→"drive_mode", UPDATE→"updates".

- [ ] **Step 1: Failing tests:** `nothingPendingMeansNoCard` (all false/0/null → empty list); `orderIsMostUrgentFirst` (all set → kinds in the order above); `textSaysWhatToDo` (setupStepsLeft=1 → text "Setup: 1 step left", 2 → "Setup: 2 steps left"; faultCode "P0420" → text starts "Fault code P0420"; update "1.220" → "Update 1.220 ready to install"); `dotsFollowTheItems` (ADAPTER+UPDATE → setOf("car_data","updates")).
- [ ] **Step 2: Run** `--tests '*NeedsYouTest*'` → FAIL.
- [ ] **Step 3: Implement** `NeedsYou.kt` (pure, English text; adapter: "OBD adapter still in the car?", full screen: "Let Drive Mode open by itself", music app: "Music app not found - pick one").
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `Home: one 'Needs you' list instead of scattered warnings`.

### Task 3: Themes as sets + Themes page

**Files:**
- Modify: `app/src/main/java/com/daand/drivemusic/Style.kt` (theme block, lines ~10-50)
- Modify: `app/src/main/java/com/daand/drivemusic/MainActivity.kt` (Page enum, `render()`, new `renderThemes()`, remove animation switches from `renderDriveMode()` ~line 1083)
- Test: `app/src/test/java/com/daand/drivemusic/ThemeTest.kt`

**Interfaces:**
- Produces: `data class Style.Theme(val id: String, val name: String, val bg: Int, val surface: Int, val surface2: Int, val primary: Int, val highlight: Int, val text: Int, val text2: Int)`; `val Style.ALL_THEMES: List<Theme>`; `fun Style.theme(id: String): Theme` (unknown id → red). `Style.THEMES` (id→name map) stays, derived from `ALL_THEMES`. `Page.THEMES("Themes")`.

- [ ] **Step 1: Failing tests:** `unknownThemeFallsBackToRed` (`theme("nope").id == THEME_RED`); `everyThemeHasReadableText` (for each theme, text ≠ bg and text2 ≠ surface); `applySetsThePalette` (`apply(THEME_BLUE)` → `PRIMARY == theme(THEME_BLUE).primary`).
- [ ] **Step 2: Run** `--tests '*ThemeTest*'` → FAIL.
- [ ] **Step 3: Implement** the sets (same colour values as today) and `apply()` copying a set. Add `Page.THEMES`; `renderThemes()` = header "Theme" with one selectable row per theme (✓ on current; tap → `prefs.theme = id; Style.apply(id); recreate()`), header "Animations" with the two switches moved from Drive Mode settings (same prefs, same labels).
- [ ] **Step 4: Run** unit tests → PASS; `./gradlew assembleDebug` succeeds.
- [ ] **Step 5: Commit** `Themes: each theme is one set; Themes page with the animation switches`.

### Task 4: The new home screen

**Files:**
- Modify: `app/src/main/java/com/daand/drivemusic/MainActivity.kt` (`renderHome`, `heroCard`, `homeSignature`, `handleBack`; delete `statusTile`/`tileLines` once unused)
- Modify: `app/src/androidTest/java/com/daand/drivemusic/NewUserJourney.kt` (tap labels)

**Interfaces:**
- Consumes: `HomeFeatures.byGroup()`, `HomeFeatures.search()`, `NeedsYou.items()/dotted()`, `Page.THEMES`.
- Produces: `private fun openFeature(id: String)` (id → page / LogActivity / dialog), `private fun featureState(id: String): String` (state line), `private fun carCard()`, `private fun featureGrid(dots: Set<String>)`, `private fun searchBox()`.

- [ ] **Step 1: Car card** `carCard()`: a `FrameLayout` (rounded `R_BIG`, `Style.SURFACE`) holding the `CarPhotoView` (or, with no photo, a 190 dp box with a faint 🚗 and "📷 Add a photo of your car · stays on your phone" → `pickCarPhoto()`), the title overlay (brand "DRIVE MUSIC", `prefs.carName.ifBlank{"My car"}`, the existing status line logic), and a row of three stat boxes at the bottom. Parked: last drive (tap → `lastDriveDetails()`), battery ("–" without OBD), fault code or "none". Driving (`prefs.lastCarState == "Android Auto connected"`): speed, engine, battery from `ObdManager.live()`, plus a song row with ⏮ ⏯ ⏭ (media transport via `DriveMonitorService.instance`). A quick-button row on the card: parked "▶ Start music" + "🎙 Greeting"; while the greeting speaks "⏹ Stop" (existing actions). Card tap → `go(Page.OBD)`; long-press → existing change/remove photo dialog. Stat labels keep the words "Last drive" so `NewUserJourney`'s `tap("Last drive")` still works.
- [ ] **Step 2: Rest of home** `renderHome()` = `carCard()`, "Open Drive Mode" button, Needs-you card (only if `items` non-empty; each line a 48 dp row → its fix: setup → `go(SETUP)`, adapter → existing `adapterPromptCard()` buttons inline, update → `go(UPDATES)`, fault → `go(OBD)`, full screen → `openFullScreenSetting()`, music app → `pickMusicApp()`), `featureGrid(dots)` (header per group, rows of 3 equal tiles ≥ 84 dp: icon 24sp, title 13sp, state 11sp `TEXT2`, max 2 lines each, orange dot if in `dots`), then `searchBox()`: an `EditText` whose text change swaps a results container under it (grid hidden while a query is typed; "Nothing found" for no match; result row → `openFeature`). Keep `carOffLine()` at the bottom. Remove the old tiles, nav rows and `fullScreenCard()` call.
- [ ] **Step 3: State lines** `featureState(id)`: music → app label; greeting → "On"/"Off"; voice → "Butler"/"Sassy"; drive_mode → "Opens by itself"/"Manual"; car_data → "On · <adapter>" / "Off"; trips → "<n> trips" / "Needs the adapter"; service → "≈ <km> km left" / "Not set"; car_screen → "Android Auto"; themes → theme name; setup → "All done ✓" / "<n> left"; updates → "Version <name>"; backup → "Save / restore"; logs → "Your drives"; help → "Tips & privacy".
- [ ] **Step 4: Refresh + back:** `homeSignature()` drops tile-only inputs that no longer show and adds the needs-you kinds; `handleBack()` THEMES → HOME.
- [ ] **Step 5: Update** `NewUserJourney`: `tap("Car data")` hits the grid tile; `tap("Welcome greeting") || tap("Greeting")` still works; nothing else changes.
- [ ] **Step 6: Verify** `./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 7: Commit** `Home: car photo card, Needs you, icon grid and search`.

### Task 5: Search jumps to the right section

**Files:**
- Modify: `app/src/main/java/com/daand/drivemusic/HomeFeatures.kt` (add `section`), `MainActivity.kt` (`header()` remembers views, `go()` scrolls)
- Test: `HomeFeaturesTest.kt`

**Interfaces:**
- Produces: `fun HomeFeatures.sectionFor(query: String, feature: Feature): String?` (header text to scroll to, e.g. "fuel price" on car_data → "Your car", "battery" → "Battery", "service" → null on its own page).

- [ ] **Step 1: Failing test** `searchOpensTheSection`: `sectionFor("fuel price", car_data) == "Your car"`, `sectionFor("trips", trips) == null`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** a small word→header map per feature; `MainActivity.go(page, section)` scrolls to the header whose text equals `section.uppercase()` after layout. **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `Search: open the page at the matching section`.

### Task 6: Verify on screens and docs

**Files:**
- Modify: `docs/HOW_IT_WORKS.md` (home section), `app/src/androidTest/.../ScreenshotTour.kt` only if a label it taps changed.

- [ ] **Step 1:** Push; on the emulator run (local `connectedDebugAndroidTest` or CI) all instrumented tests pass, incl. `LargeTextTest` (now also covering `THEMES`) and `NewUserJourney`.
- [ ] **Step 2:** Read the CI screenshots `a_main_home_*`, `j01_first_open`, `c_small_home`, `e_large_main_home*`: card keeps its shape without a photo; nothing cut off; grid 3 wide. Fix anything off.
- [ ] **Step 3:** Update `HOW_IT_WORKS.md` home section; commit `Docs: the new home screen`.
