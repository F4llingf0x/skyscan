# SkyScan demo guide — agriculture & wildlife

Two demo layers for each function: **seeded** (a finished mission you open and
inspect — zero waiting) and **live** (you fly the simulated drone end-to-end —
the whole state machine, live map, and reports, ~2–3 minutes each).

Start the stack and log in:

```bash
docker compose up --build          # backend :8080, frontend :4200
# operator / password  (staff — can plan and launch)
# customer / password  (read-only customer view)
```

---

## Demo A1 — Crop health, seeded (30 seconds)

Open **Dashboard → Alma Orchard — Demo Farm → "DEMO — Orchard crop health"**.

What to show:
1. **Plant map** — every detected crop plant (amber) and tree (green) as a
   marker, plus **crop field ZONES** (teal translucent circles, F-001…): dense
   row-crop patches inspected via sample points with zone-level health.
   Hover for labels. This is the crude-pass output.
2. **Detected plants table** — vigor column (color-coded 0..1); with the
   mission COMPLETE, the findings column shows disease chips (severity-colored)
   and "healthy" for the rest.
3. **Open crop health report** — the deliverable: summary cards, findings by
   disease, and the affected-plants table with location, severity, confidence,
   **possible cures and what to check** per finding, then the full plant
   inventory. Point out the advisory disclaimer at the bottom.

## Demo A2 — Crop health, live flight (~3 minutes)

1. **Alma Orchard — Demo Farm → Plan mission**. Draw a polygon (or reuse the
   visible one), Mission type = **Crop health**, note the **camera module**
   dropdown (IMX219 77° default — it drives where plants land on the map).
   Launch.
2. Watch the **crude pass** fly (~18 s simulated): live telemetry, breadcrumb
   on the map, battery draining.
3. On landing the mission goes to **PREVIEW_READY** with "N plants detected".
   The crop panel now shows the plant map + table (all status DETECTED).
4. Set inspection altitude (6 m) and speed, **Launch disease inspection** —
   the drone flies the nearest-neighbor per-plant route (watch the vehicle
   visit markers). In a real flight, battery swaps happen between plants and
   the pass resumes automatically.
5. On landing: analysis runs, mission → **COMPLETE**, report link appears.
   Plants are now SCANNED, findings populated.

## Demo B1 — Wildlife survey, seeded (30 seconds)

Open **Riverside Meadow — Demo Reserve → "DEMO — Riverside wildlife survey"**.

What to show:
1. **Sighting map** — every tagged animal, colored by source (green =
   thermal-confirmed FUSED, orange = THERMAL-only warm body, blue = RGB).
   Hover a marker: label + species + time of finding.
2. **Sighting tags table** — time-ordered: species, **time of finding**,
   source, body temperature (thermal subset), confidence.
3. **Wildlife survey report** — species tally, thermal-confirmed count, and
   the full time-ordered tag table with locations.

## Demo B2 — Wildlife survey, live flight with LIVE tagging (~1 minute)

This is the money demo — animals appear on the console **while the drone is
still flying**.

1. **Riverside Meadow — Demo Reserve → Plan mission**, Mission type =
   **Wildlife survey** (single pass — the panel explains why: animals move),
   camera module visible here too. Launch.
2. During the ~18 s pass, watch the wildlife panel: at ~5 s **A-001 roe deer
   (FUSED, 37.8 °C)** pops onto the map and table the moment it is "seen",
   then A-002 wild boar, then A-003 hare (RGB-only) — each stamped with its
   time of finding. This is the same live SSE path the real drone uses.
3. On landing the mission goes straight to **COMPLETE** (no detail pass) and
   the report link appears — built from exactly the tags you watched arrive.

## Bonus — Drone check page (staff)

Top-nav **Drone check**: run the remote **self-test** (component table with a
deliberate WARN on GPS to show all states), then the **hover & dodge test** —
the stub simulates arming, climbing to your set height, a scripted person
approaching from the right at ~7 s (watch the DODGE event and the red state
chip), the person retreating (CLEAR), and landing. On real hardware this is
the GPS-free 1 m props-on test.

---

### Talking points per demo

| Function | The claim | Where it's proven |
|---|---|---|
| Crop | two-stage: map every plant, then inspect each | A2 steps 3–5 |
| Crop | battery swaps between plant visits | leg/resume machinery (real flights); mention in A2 step 4 |
| Crop | actionable output: cures + checks per disease | the report, A1 step 3 |
| Wildlife | every animal tagged with time + location | B1 step 2, B2 step 2 |
| Wildlife | thermal catches what RGB misses | THERMAL-only rows in B1 |
| Wildlife | live tagging mid-flight | B2 step 2 |
| Both | camera module selection drives map accuracy | planner dropdown, A2/B2 step 1 |

### Notes

- Demo data comes from the same stub code paths the simulated connector uses,
  so seeded and live demos look identical in structure.
- On a fresh database the demo missions seed at boot; on an existing database
  they back-fill on next start (idempotent by name). If MinIO is slow to
  start, report generation retries 4× then skips with a warning — the live
  demos are unaffected.
- Real-hardware differences: detection/classification run on the Jetson
  (TensorRT engines required — see PROJECT_CONTEXT §3b), and the wildlife
  live stream rides `/internal/drone/missions/{id}/sightings/live`.
