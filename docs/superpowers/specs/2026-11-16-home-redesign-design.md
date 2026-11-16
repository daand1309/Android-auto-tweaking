# Home tab redesign - design

Date: 2026-11-16 · Status: waiting for approval

## Why

The home tab works, but finding things is "a bit of a search": below the status tiles and buttons sits
one long list of settings rows. The owner also wants the app ready to grow - new features, new themes,
new animations - without each addition making the home screen longer. And the car photo should stay:
it makes the app feel like *this* car's app.

## Goals

- Everything reachable in one tap from Home, recognisable by icon.
- Your car photo is the centre of Home again, in a layout built for it.
- Adding a feature, theme or animation style later is a small, local change.
- Small quality-of-life fixes that remove taps and guesswork.

## Non-goals

- Drive Mode itself does not change (it was redone recently and works).
- The pages behind the icons keep their current content; only Car data is split (below).
- No new features in this change (no new data, no new settings).

## Anti-goals (a failure even if it "works")

- A tab bar or a second way of getting around next to the grid (rejected: option 2 was chosen).
- A home screen that jumps around: the layout must look the same with or without a photo, parked or driving.
- Anything that breaks at a big phone font (the existing large-text test must keep passing).

## Approaches considered

1. **Tabs at the bottom** (Home / Car / Music / Settings). Clear, but two taps to most things.
2. **Icon grid on Home** - everything one tap away, like a phone's home screen. **Chosen.**
3. **Search + pinned shortcuts.** Fastest if you know the name; most work. Its search box is kept,
   on top of the grid.

A combination with tabs *and* the grid was also drawn; the owner chose the grid alone, no tab bar
(option "2" in the conversation): one way of getting around is easier to learn, and Back always
returns to Home.

## The design (as decided)

Home, top to bottom:

1. **Car card** - the photo fills the top, the car's name and status ("Parked · 6° light rain" /
   green "Driving · 25 km so far") over it; the photo fades into three numbers at its bottom edge.
   - Parked: last drive, battery, fault code (or "no faults").
   - Driving: speed, engine temperature, battery - plus the current song with ⏮ ⏯ ⏭ on the card.
   - No photo: same size card, faint car outline, "Add a photo of your car (stays on your phone)".
   - Tap → Car data. Long-press the photo → change / remove.
2. **Open Drive Mode** - the big red button, as now.
3. **Needs you** - one card, only when something waits: setup steps, the adapter question, an update,
   a fault code, full-screen permission. Each line one tap to fix. Replaces today's separate warning rows.
4. **Icon grid**, three per row, under three small headings:
   - **Music:** Music & driving, Greeting, Voice, Drive Mode settings
   - **Car:** Car data, Trips & costs, Service, Car screen
   - **App:** Themes, Setup, Updates, Backup, Logs, Help & privacy
   Each icon shows a one-line state under its name ("On", "41 %", "Due in 2 400 km", "1 step left").
   An orange dot on an icon = it is in "Needs you".
5. **Search settings** - matches page titles, setting names and extra words in English and Dutch
   ("pitch", "accent" → Voice; "brandstof", "price" → fuel price on Car data). A result opens that page.

Behaviour:

- Back on any page → Home; Back on Home → closes the app.
- Quick buttons (Start music / Greeting, Stop greeting) move onto the car card next to the song line,
  so Home stays short. *(my call)*
- The current long settings list on Home is removed; every row it had is behind an icon.

Groundwork for growth:

- **Feature list:** each feature is one entry - name, icon, group, page, state line, "needs you" check,
  search words. Home, the grid, the dots and search are all built from that list. A new feature = one
  new entry.
- **Themes as sets:** a theme is one named set of colours, corner sizes and animation style (in
  `Style`). The two current themes become two sets; adding one is adding a set. The Themes icon opens
  a page with the theme list and the animation switches (moved from Drive Mode settings).

## Constraints

- Kotlin, UI built in code (no XML layouts), as the rest of the app.
- Existing animations via `Motion` (own frame clock); the grid enters with the same page animation.
- Large-text and small-touch-target checks (`LargeTextTest`, `cutOffTextsForTest`, `smallTargetsForTest`)
  must pass; icons at least 44 dp.
- `NewUserJourney` and `ScreenshotTour` tap things by visible text ("Setup", "Car data", "Drive Mode",
  "Last drive"...) - they are updated to the new labels in the same change.
- The car photo stays on the phone; nothing about it leaves the device.

## Left to the builder

- Exact icons (drawable vectors, consistent with the existing ic_* set).
- Car data split: whether Battery / Fuel price / Adapter become their own pages now or stay sections
  of Car data for this change. *(my call: keep them as sections, search jumps to the section - smaller change)*
- Exact state-line text per icon, and the Dutch strings.
