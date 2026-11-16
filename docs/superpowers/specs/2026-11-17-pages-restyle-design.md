# All pages in the home screen's style - design

Date: 2026-11-17 · Status: approved (mockup "pages-v1": Music & driving, Greeting, Voice)

## Why
The new home screen works; the twelve pages behind its icons still look like the old app: a box per
setting, long grey paragraphs between them, a plain "‹ Title". They are long and don't match.

## Goals / non-goals / anti-goals
- Goal: every page follows four rules (below), shorter and easier to scan, same settings.
- Non-goal: no settings added or removed; Drive Mode itself untouched.
- Anti-goal: hiding something you need. Warnings, the setup checklist and fault codes are never folded.
  If a change makes a page harder to use, it is reverted for that page.

## The four rules
1. Header = the home icon grown up: icon, page name, status line (the same state line as on Home).
2. One card per section, rows inside with thin dividers - not a box per setting.
3. Rows say what they do in one line; explanations longer than ~140 characters fold to their first
   sentence + "More ›" (tap to expand, tap again to fold).
4. The page's main action sits at the top in red; secondary buttons become "label ›" rows in the card.

## Approaches
- **Shared building blocks** (chosen): the existing helpers (header, switch, stepper, value row, nav
  row, status row, step row, check row, text, button) group themselves into section cards; every page
  is assembled from them, so all twelve match and new settings match automatically.
- Restyling each page by hand: faster for one page, pages drift apart again (how they got here).

## Calls left to the builder
- Per-row icons from the mockup are left out (they would need ~80 hand-picked icons); the header icon
  carries the page identity. *(my call)*
- Fold threshold 140 characters, first sentence up to 120.
