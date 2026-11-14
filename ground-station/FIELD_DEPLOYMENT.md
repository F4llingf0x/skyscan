# SkyScan field deployment — offline scanning on a site LAN

The full stack is designed to run with **zero internet**: a travel router, a
laptop running the compose stack, and the drone are the entire network. Fly
the whole two-pass survey, process it on site, verify the deliverable in the
viewer, then carry the result home as a single file and import it into the
office/central instance.

```
        site LAN (travel router, e.g. 192.168.8.0/24, NO WAN)
   ┌───────────────┬──────────────────────┬────────────────────┐
   │ laptop        │ Jetson (on drone)    │ optional: tablet   │
   │ 192.168.8.10  │ 192.168.8.20 (WiFi   │ /phone for the UI  │
   │ docker stack: │ or LTE-router LAN)   │ http://192.168.8.10│
   │ UI :4200      │ agent → backend:8080 │        :4200       │
   │ API :8080     │                      │                    │
   │ MinIO, PG,    │                      │                    │
   │ engine        │                      │                    │
   └───────────────┴──────────────────────┴────────────────────┘
```

## 1. Prepare AT THE OFFICE (the only steps that need internet)

1. **Build/pull all docker images** while online: `docker compose build`
   followed by `docker compose up -d` once, then down. Images, npm/Maven/CMake
   artifacts and base layers are now cached locally — the field boot needs no
   registry. Belt-and-braces for a different field laptop:
   `docker save $(docker compose config --images) -o skyscan-images.tar`
   → `docker load -i skyscan-images.tar` on the target.
2. Charge everything; copy this repo (with `sample-data/`) onto the laptop.
3. Optional map tiles (see §4): pre-download a tile cache for the site area.

## 2. Router + addressing

- Any travel router works; **no WAN needed**. Enable DHCP, reserve fixed
  leases for the laptop (e.g. `192.168.8.10`) and the Jetson (`192.168.8.20`)
  so addresses survive reboots.
- Laptop: `docker compose up -d`. Compose publishes on `0.0.0.0`, so the UI
  is reachable from any device on the LAN at `http://192.168.8.10:4200`.
- Jetson `config.yaml`: `backend.base_url: http://192.168.8.10:8080`. The
  agent only dials OUT (poll + POST), so no inbound ports on the drone.
- Backend env: `DRONE_CONNECTOR=jetson`.
- If the browser runs on a device other than the laptop, set
  `MINIO_EXTERNAL_ENDPOINT=http://192.168.8.10:9000` in the compose
  environment so presigned viewer/download URLs point at a reachable address.

## 3. Things that silently assume internet — and their field answers

| Dependency | Field answer |
|---|---|
| RTK corrections (NTRIP caster) | **Local base station**: second GNSS module in a surveyed/averaged position, corrections over the LAN or a radio link. Without it you fly M8N-grade (fine for crude passes; see ../aerial-building-scanner/legacy_drone_agent/README GNSS ladder). |
| Time sync (NTP) | GNSS disciplines the FC; enable PPS→Jetson for scan/pose stamping. The laptop's clock only affects log names — set it roughly right. |
| Map tiles (mission planner) | See §4 — works tile-free, better with a local tile server. |
| Dashboard world map | Decorative; degrades to site markers offline (built-in fallback). |
| Potree viewer CDN | Auto-falls back to the built-in three.js LAS viewer. |
| Google Fonts | Cosmetic; browser falls back to system fonts. |
| Docker registry | Everything pre-built in §1; never pulled in the field. |

## 4. Map tiles offline

The mission planner works with **no tiles at all**: after a few failed tile
loads it switches to a neutral grid, and polygon drawing, planned routes, the
live vehicle marker and coordinates all keep functioning (the polygon is
drawn relative to the site coordinates, not the imagery).

Nicer option — bring the map with you: run any XYZ tile source on the LAN
(e.g. a tile cache/proxy you filled at the office for the site's bounding
box, zoom ~12–19) and point the UI at it once per browser:

```js
// browser devtools console, on the SkyScan UI:
localStorage.setItem('skyscan.tileUrl', 'http://192.168.8.10:8081/tile/{z}/{x}/{y}.png')
```

Remove the key to return to the OpenStreetMap default. Respect the usage
policy of whatever tile source you cache.

## 5. Fly and verify ON SITE

Normal operation (see `../aerial-building-scanner/legacy_drone_agent/SETUP_MANUAL.md` Stage 9): plan → crude →
preview → refine → detail → `PROCESSING` → `COMPLETE`. Everything — raw
scans, preview, engine processing, LAS/LAZ/COPC deliverables, photos, flight
logs — lands in the laptop's local Postgres + MinIO volumes. **Open the
deliverable in the viewer before leaving the site**: the point you tore down
the rig is the wrong moment to discover a gap in the facade.

## 6. Bring the data home — export / import

**Export (field instance)** — either from the UI: mission page → **Export
bundle** (staff login), or scripted:

```bash
TOKEN=$(curl -s http://192.168.8.10:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"operator","password":"password"}' | jq -r .accessToken)
curl -H "Authorization: Bearer $TOKEN" -o mission.zip \
  http://192.168.8.10:8080/api/missions/<missionId>/export
```

The bundle is one ZIP: `manifest.json` (site, mission, scan areas with routes
and tilt, acquisitions, deliverable metadata) + `objects/<key>` for every
stored object (raw scans, preview, LAS/LAZ/COPC, photos, flight logs). It is
self-contained — the field laptop can be wiped afterwards.

**Import (office/central instance)** — Dashboard → **Import field bundle**
(staff login), or:

```bash
curl -H "Authorization: Bearer $TOKEN" -F file=@mission.zip \
  http://central:8080/api/missions/import
```

Import re-creates the site (matched by name + organization, created if
missing) and the mission with **new ids**; the old mission UUID is rewritten
inside every object key so photo/log listings work on the target. The source
mission id is kept in the response and manifest for traceability. Importing
the same bundle twice yields two independent copies — dedupe by name if
needed. Nothing is deleted on the field instance; keep it as the raw backup
until the import is verified in the central viewer.

## 7. Field kit checklist

- [ ] Travel router (+ its power), Ethernet cable for the laptop (WiFi for
      the Jetson is fine; wire the laptop)
- [ ] Laptop with pre-built images (§1), ≥ 50 GB free for scans
- [ ] Drone + charged packs + charger, RC transmitter
- [ ] RTK base (if surveying grade needed) + tripod
- [ ] Test the WHOLE loop at the office on the travel router — including one
      export + import — before the first field day
