# Pages Restyle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** All twelve pages use section cards, a home-style header, folded long texts and a top main action.
**Architecture:** Pure `TextFold` + `PageIcons` (unit tested) decide folding and header identity; `MainActivity`'s row helpers add themselves to the current section card (`addRow`), closed by `header()`, accent buttons and any non-row view.
**Tech Stack:** Kotlin, views in code, JUnit/Robolectric, emulator tests in CI.
**Spec:** `docs/superpowers/specs/2026-11-17-pages-restyle-design.md`

## Global Constraints
- Same settings, same labels (tests and the user's habits rely on them); ≥ 44 dp targets; nothing ellipsized at 1.5× font.
- Never fold: warnCard, check rows (Setup), live data cards, fault codes.

## Review Focus
1. A custom view (Voice chips, live cards) between two rows must start a new card, not let later rows jump above it → `addRow` checks the card is root's last child.
2. A folded text that is the only explanation of a risky switch (e.g. premium voice sends text to Microsoft) → first sentence must still say it; checked on screenshots.
3. Big font: rows with long labels wrap, switches stay reachable → LargeTextTest.
4. Disabled buttons inside a card still look disabled → alpha kept.
5. Home must not change (it does not use the row helpers except accent button / text at the bottom).

### Task 1: TextFold and PageIcons
**Files:** Create `TextFold.kt`, test `TextFoldTest.kt`; add `HomeFeatures.forPage(name: String): Feature?` + test.
- `TextFold.short(s: String): String?` → null when `s.length <= 140`; else the first sentence (ends at ". ", "! ", "? " or "\n", within 120 chars) or the first 120 chars cut at a space, + "…" only when cut mid-sentence.
- Tests: `shortTextIsNotFolded` (100 chars → null), `longTextFoldsToFirstSentence` ("Checks every 10 minutes and stays quiet. " + 200 x → "Checks every 10 minutes and stays quiet."), `noSentenceBreakCutsAtASpace` (200 chars of words → length ≤ 121 and ends with "…"), `forPageMapsEveryPage` (MUSIC→music, OBD→car_data, TRIPS→trips, CAR→car_screen, THEMES→themes, SETUP→setup, HELP→help, unknown→null).

### Task 2: Section cards in the shared helpers
**Files:** `MainActivity.kt` helpers: `pageHeader`, `header`, `text`, `button`, `switch`, `stepper`, `stepperLike`, `navRow`, `statRow`, `stepRow`, `check`.
- `private fun addRow(v: View)`: current card = `group` if it is root's last child, else a new card (SURFACE, radius R_CARD, topMargin 6 dp, clipToOutline); divider (1 dp, BG colour, inset 14 dp) between rows; row background → transparent ripple, topMargin 0.
- `header()` closes the card. `button(accent|danger)` stays a full button and closes the card; other buttons become a row "label  ›" (min 52 dp). `text()` inside an open card → footnote in the card (13 sp, TEXT2); folded via `TextFold`; outside a card → as today, folded.
- `pageHeader()` → back chevron (48 dp target), icon tile 46 dp, title 22 sp bold, status line = `featureState(feature.id)`.
- Verify: unit tests + assembleDebug; commit.

### Task 3: Per-page main action + checks on screenshots
- Main action at top: Greeting (test greeting), Voice (hear a full greeting) right under style, Backup (save), Updates (check now as accent when no update waiting) - only where the page has one.
- Push; read CI screenshots for all pages (normal, small, 1.5× font); answer per page: shorter? anything hidden that shouldn't be? Fix or revert per page.
- Update `docs/HOW_IT_WORKS.md` (one paragraph on page layout).
