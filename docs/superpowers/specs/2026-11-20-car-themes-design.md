# Car themes (short design)

**Why:** the owner likes changing the look and wants it to reflect the car: a grey ASX with a grey/black interior.

**Approved (preview: design/themes-preview.png):** seven new themes, existing Red & silver and Classic blue stay:
Titanium (main pick), Titanium red, Night drive, Steel blue, Copper, Dial green, Midnight black. Colours as in design/themes-preview.html.

**Approaches:** (1) add entries to Style.ALL_THEMES plus an on-accent text colour - chosen; (2) Android XML themes per colour - rejected, the UI is built in code from Style; (3) free colour picker - rejected, easy to make unreadable combos.

**Build:**
- Theme gets `onPrimary` (text/icon colour on accent fills); dark for Titanium, Copper, Dial green, Midnight black.
- Every accent-filled control uses Style.ON_PRIMARY for its text.
- Launch animation circle follows the accent (white mark becomes ON_PRIMARY).
- Danger red, amber, green status colours stay fixed in every theme.
- Stored theme ids unchanged; unknown id still falls back to Red & silver.

**Non-goals:** car screen (Android Auto uses Google's colours); app icon.
