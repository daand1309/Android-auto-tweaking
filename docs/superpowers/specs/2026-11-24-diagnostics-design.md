# Diagnostics (design)

**Why:** one place to check the car's health on demand with the OBD adapter, instead of data spread over
Car data / Service / Possible issues. The owner wants to pick between a quick snapshot and a big test.

**Approved shape** (chat, 24 Nov): Quick check (~30 s, ignition on or engine running) and Full test (~5 min,
parked, engine running, guided steps). ✓ / ⚠ / ✗ per item with one line; results saved and shareable.

**Approaches:** through the existing poll loop's adapter link (chosen: an ELM327 clone takes one client);
a second connection (rejected: the adapter can't); only past drive data (rejected: not a live check).

**Build**
- `obd/Diagnostics.kt` (pure, tested): `Check(id, title, verdict OK/WARN/BAD/INFO, detail)`;
  `quick(...)` judges one snapshot; `FullTest` collects samples per step (IDLE 60 s, LOAD 30 s, REV 30 s,
  SNAP 15 s) and judges them; `encode/decode` for the history (last 10).
- `ObdManager`: `diagnosing` flag = every round reads all values at the live pace; `requestCodes()`; a job
  queue run on the poll thread (mode 06 reads) - the link is never used from two threads.
- `ObdSession.onBoardTests(mids)`: mode 06 reads (already allowed by ReadOnly: `06XX`).
- `DiagnosticsActivity` (UI in code, Style colours): start screen with both buttons and history; a step
  screen with a countdown and live numbers; results list with Share.
- Entry: home grid "Diagnostics" (Car group) and a button on the Car data page.

**Rules:** read-only (ReadOnly regex unchanged); Full test refuses / stops when speed > 3 km/h; needs the
adapter connected (says so otherwise); warns, but runs, with a cold engine.

**Thresholds (1.6 MIVEC petrol), my calls:** battery off ≥12.5 ok / ≥12.2 warn; charging 13.5–14.8 ok,
13.2–15.0 warn; fuel trim |ST+LT| ≤10 ok, ≤20 warn (warm, closed loop); idle 600–900 rpm, spread ≤25 ok,
≤50 warn; front O2 range ≥0.5 V switching, <0.3 V lazy; rear O2 range ≤0.3 V with a switching front =
catalyst storing oxygen; MAF idle 1.5–4.5 g/s; voltage under load ≥13.2 ok, ≥12.8 warn; lean at idle only
(idle trim > +10, 2500 trim < +5) = likely air leak; codes cleared < 200 km or < 10 warm-ups = warn;
misfire counts (mode 06 A2–A5) ≥ 10 warn, ≥ 50 bad.

**Non-goals:** clearing codes or any write; tests while driving; car-screen version.
