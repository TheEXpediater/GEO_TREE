# GEO Tree

Android field-management app for registering, geotagging, locating and navigating to tamarind trees. It works offline first and syncs to a shared FastAPI/MongoDB backend.

## Give 2 scope (current)

```text
Splash → Login → Dashboard ─┬─ Dashboard | Map | Settings   (bottom navigation)
                            └─ Tag Tree (Dashboard button, Map FAB)
Map: current GPS → select tree → Navigate to Tree → red direct guidance line
     → distance · bearing · speed · ETA (continuous GPS) → "Tree reached" → Stop Navigation
```

- **Dashboard** (first authenticated screen): total/synced/pending/failed counts, GPS status and last known accuracy, device network, backend status (Connected / Offline / Checking), last successful sync, quick actions (Tag New Tree, Open Map, Sync Now), "N records waiting to sync", recent trees. All numbers come from Room; nothing waits on the API.
- **Map**: MapLibre field locator with Room markers, blue current-location dot and accuracy halo, compass, GPS/accuracy/sync/navigation chips, My Location, explicit **Follow Location**, Tag Tree FAB, selected-tree sheet (thumbnail, coordinates, distance, Navigate to Tree).
- **Field guidance** (not road routing): a red geodesic line from your position to the tree, updated from a continuous GPS stream.
- **Settings**: backend status and URL, Test Connection, Change Server (health-checked before saving), last sync, Sync Now, basemap source, guidance settings, sign out.
- **Offline PSAU / Magalang field map** (follow-up): a 5.8 MB map of the deployment area is bundled in the APK and works with no internet (section 10).

Not in Give 2: leaf-disease AI, TensorFlow Lite, OpenCV, Roboflow, Qdrant, turn-by-turn or road routing, voice guidance, production hosting, push notifications.

## Give 1 scope

Give 1 proves geotagging end to end:

```text
Splash → Dev Login → Field Locator → Tag Tree → Capture Image → Tree Code → Fresh GPS fix
→ Save to Room (PENDING) → Marker appears → WorkManager sync (metadata + image) → FastAPI → MongoDB
→ SYNCED → other devices pull the record
```

All of Give 1 still works unchanged inside the Give 2 shell.

## Architecture

```text
GPS (Fused Location) / Camera (CameraX)
        ↓
   ViewModels (StateFlow)          ← Compose UI observes these
        ↓
   Repositories
     ↙        ↘
  Room         Retrofit/OkHttp
 (UI source     ↓
  of truth)   FastAPI  ──→  MongoDB
     ↑            ↑
     └── WorkManager (SyncEngine) ──┘
```

- **Room is the on-device source of truth.** Markers, lists and details read from Room only.
- **Local first.** A tree is validated and written to Room as `PENDING` before anything touches the network.
- **Android never talks to MongoDB.** FastAPI is the only gateway.
- **Identity is a device-generated UUID.** Coordinates are attributes. Tree Codes are human-readable and unique.
- **Images are files**: app-private storage on the phone, `uploads/tree_images/` on the server. Room and MongoDB store paths only.

| Layer | Location |
|---|---|
| Android app | `android/app/src/main/java/com/geotree/app/` (`core/`, `data/`, `feature/`) |
| Room schema export | `android/app/schemas/` |
| Offline field map | `android/app/src/main/assets/offline_map/` (package + metadata), built by `tools/offline_map/` |
| Backend | `backend/app/` (`routes`, `services`, `repositories`, `schemas`, `auth`, `database`) |
| Backend tests | `backend/tests/` (isolated in-memory DB; never touches dev data) |

## Prerequisites (Windows)

- Android Studio with SDK platform 35 and an emulator (tested on the **Medium_Phone** AVD, API 37)
- A **JDK 17 or 21**. Gradle 8.10 cannot run on JDK 25 (Android Studio's current bundled JBR). The build uses Gradle's daemon toolchain (`gradle/gradle-daemon-jvm.properties` → `toolchainVersion=21`), so Gradle finds a JDK 21 automatically (for example one installed by IntelliJ/Android Studio under `%USERPROFILE%\.jdks`). If it can't, point `JAVA_HOME` at a JDK 21.
- Docker Desktop
- Python 3.12+ (only needed to run the backend outside Docker or to run backend tests)

## 1. Start MongoDB + backend (Docker)

### Easiest: `run.bat`

1. Start Docker Desktop and wait for "Engine running".
2. Double-click `run.bat` in the repository root (or run `.\run.bat`).

It checks the Docker CLI and engine, runs `docker compose up -d --build`, waits for `http://localhost:8000/api/v1/health`, then prints:

```text
GEO Tree Backend Ready
Local:            http://localhost:8000
Android emulator: http://10.0.2.2:8000
Physical phone:   http://192.168.x.x:8000   (candidate LAN addresses)
```

If Docker Desktop is installed but not running, it says so and stops. It never deletes volumes and never changes Windows Firewall. The logic lives in `scripts/start_geo_tree.ps1` (Windows PowerShell 5.1 compatible).

**Auto-restart.** Both services use `restart: unless-stopped`. If Docker Desktop is set to start when you sign in to Windows, the GEO Tree containers come back automatically. But the Android app cannot start Docker on your laptop: if Docker Desktop is closed, sync is unavailable. GPS, tagging, Dashboard, markers and field guidance keep working on the phone, and new trees stay `PENDING` until the backend is reachable again.

### Manually

```powershell
docker compose up -d --build
docker compose ps
Invoke-RestMethod http://localhost:8000/api/v1/health
```

Expected health response: `status=ok, service=geo-tree-api, database=ok`.

- MongoDB: `mongo:7.0`, database `geo_tree`, named volume `geo_tree_mongodb_data`
- Host port mapping: **27018 → 27017** by default. If 27018 is already taken, copy `.env.example` to `.env` and set `GEO_TREE_MONGO_HOST_PORT` (for example `27019`). The backend container always uses `mongodb://mongodb:27017` internally.
- Uploaded images live in the named volume `geo_tree_uploads`.

Stop without deleting data:

```powershell
docker compose stop
```

> Never use `docker compose down -v`: it deletes the MongoDB volume.

### Backend on Windows instead of Docker (optional)

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
Copy-Item .env.example .env        # MONGO_URI=mongodb://localhost:27018 (or your override port)
docker compose stop backend       # free port 8000 first
.\.venv\Scripts\python.exe -m uvicorn main:app --host 0.0.0.0 --port 8000 --env-file .env
```

## 2. Development credentials

In `ENVIRONMENT=development`, and only if the account is missing, the backend creates:

```text
admin@gmail.com / admin123
```

The password is stored as a bcrypt hash. Debug APKs prefill these values. Release builds contain empty defaults, and the backend never seeds outside development.

## 3. Android build and install

```powershell
.\gradlew.bat :android:app:testDebugUnitTest
.\gradlew.bat :android:app:assembleDebug
adb devices
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.geotree.app/.MainActivity
```

APK location: `android\app\build\outputs\apk\debug\app-debug.apk`

A convenience copy for physical-phone testing is written to the repository root as `GEO_Tree_Test.apk` with `GEO_Tree_Test.apk.sha256` (both gitignored). Verify it with:

```powershell
Get-FileHash .\GEO_Tree_Test.apk -Algorithm SHA256
```

Android Studio: open the repository root (not `android/`). `local.properties` (`sdk.dir=...`) is created by Android Studio and is gitignored.

### Backend address

| Where the app runs | Base URL |
|---|---|
| Emulator (default) | `http://10.0.2.2:8000` |
| Physical phone on the same Wi-Fi | `http://<laptop-LAN-IP>:8000` |

Change it in the app (**Login → Server: Change**, or **Settings → Change Server**). The app calls `/api/v1/health`, confirms that the server is GEO Tree, and saves the URL only if that check passes. You can also build with a different default: `.\gradlew.bat :android:app:assembleDebug -Pgeotree.baseUrl=http://192.168.1.20:8000`. A later hosted HTTPS URL uses the same mechanism; repositories and API contracts don't change.

Physical phone: allow inbound TCP 8000 in Windows Firewall. Debug builds allow cleartext HTTP; release builds are HTTPS-only. A physical phone must never use `10.0.2.2` (that address only exists inside the emulator).

### Physical Field Test checklist (short)

Do this on a real Android phone. The emulator cannot test real walking speed, real GPS accuracy or a full docked keyboard (see Known limitations).

- [ ] Install `GEO_Tree_Test.apk`.
- [ ] Sign in once while the laptop backend is reachable.
- [ ] **Settings → Map**: the offline map shows **Ready**.
- [ ] Enable **Airplane mode**, keeping **Location** ON.
- [ ] Walk outdoors at PSAU.
- [ ] **Tag Tree**: acquire GPS.
- [ ] Check the accuracy (≤ 5 m Good, ≤ 10 m Acceptable).
- [ ] Tag a real tree. With the keyboard open, check that **Save Tag** stays visible above it.
- [ ] Navigate to a previously tagged tree.
- [ ] While walking, check that **Current speed [GPS]** and **ETA at current speed** appear when Android reports a speed.
- [ ] Stand still and check that the panel switches to the **Walking estimate** box.
- [ ] Walk into 10 m of the tree and check that "Tree reached" appears once.
- [ ] Force-close and reopen the app; the new tree is still there.
- [ ] Turn networking back on with the backend running (`run.bat`).
- [ ] Check that the pending record becomes **Synced**.

### Physical phone heading test (required; not done on the emulator)

1. Open **Map**.
2. Select a tree.
3. Start **Field Guidance** (Navigate to Tree).
4. Hold the phone flat or naturally in front of you.
5. Rotate the phone clockwise.
6. Check that the green live arrow on the panel compass rotates with it, and that the blue chevron on the map turns too.
7. Rotate toward the grey target line.
8. Check that the live arrow aligns with it and turns success green.
9. Check that the text approaches "Facing the tree" (relative angle near 0°).
10. Walk toward the tree.
11. Check that the distance decreases.
12. Check that the current-location chevron keeps updating.
13. Tap ▼ to **minimize** guidance.
14. Check that the bar keeps updating distance and ETA.
15. Tap the bar to **expand**.
16. Check that the same tree, distance and state are shown.
17. Tap the orientation control for **Heading Up**: the map should turn with you.
18. Drag the map: it should return to free mode.
19. Tap **My Location** to follow again.

If the arrow drifts or the panel says "compass needs calibration", move the phone in a figure-8 and keep it away from vehicles, metal and other phones.

### Physical phone test (online, with sync)

1. Start Docker Desktop.
2. Double-click `run.bat`.
3. Note the printed LAN address, e.g. `http://192.168.1.20:8000`.
4. Connect the phone and the laptop to the same Wi-Fi or hotspot.
5. Install `GEO_Tree_Test.apk` (copy it to the phone, or `adb install -r GEO_Tree_Test.apk`).
6. Open GEO Tree.
7. Open server settings: **Login → Server: Change** (or **Settings → Change Server** once signed in).
8. Enter `http://<laptop-ip>:8000`.
9. Tap **Test & Save**. The address is saved only if the GEO Tree health check passes.
10. Sign in (`admin@gmail.com` / `admin123`, prefilled in debug builds).
11. Walk outside, away from tall buildings.
12. Open the **Map** tab.
13. Tap **My Location**.
14. Tag a tree (**Tag Tree**): photo, Tree Code, fresh GPS fix.
15. Check the GPS accuracy (≤ 5 m Good, ≤ 10 m Acceptable, > 10 m Low).
16. Save.
17. Check that the marker appears immediately.
18. Walk away, then select the marker.
19. Tap **Navigate to Tree**.
20. Walk and watch distance, direction, speed and ETA update. "Tree reached" appears once within 10 m.
21. With the backend reachable, check that the record becomes **Synced** (Dashboard or Tree Detail).

If the backend is unavailable, tagging still saves locally and the tree stays **PENDING**. WorkManager syncs it automatically once the laptop is reachable again, or tap **Sync Now**.

### Physical phone test (fully offline)

1. Install `GEO_Tree_Test.apk`.
2. Sign in once while the backend is reachable (steps above). There is no offline first sign-in.
3. Open **Settings → Map** and confirm **Offline PSAU Map** is selected and the offline map shows **Ready** (5.8 MB).
4. Turn Wi-Fi OFF.
5. Turn Mobile Data OFF.
6. Optionally enable Airplane Mode, keeping **Location** ON (GPS does not need internet).
7. Open GEO Tree.
8. Check the **Dashboard** (counts, "Offline map ready", Backend Offline).
9. Open **Map**.
10. Confirm the PSAU map is visible inside the coverage area (section 10).
11. Tap **My Location**: the map centres on you at street/field zoom.
12. Confirm the existing tree markers are shown.
13. Tag a new tree: photo, Tree Code, fresh GPS, Save.
14. Confirm the new marker appears at once (status Pending).
15. Select a tree.
16. Tap **Navigate to Tree**.
17. Confirm the red guidance line.
18. Walk.
19. Watch the distance change.
20. Watch the direction change.
21. Check speed and ETA: they use your measured speed when the phone reports one; otherwise "Stationary" with a labelled walking estimate.
22. Force-close and reopen the app.
23. Confirm the new tree is still there.

Then turn networking back on with the laptop backend running (`run.bat`). The tree goes **PENDING → SYNCING → SYNCED**, either automatically or after **Sync Now**.

## 4. Tests

```powershell
cd backend; .\.venv\Scripts\python.exe -m pytest -q; cd ..
.\gradlew.bat :android:app:testDebugUnitTest
```

- **Backend (29 tests, unchanged in Give 2):** health, dev-admin seed idempotency, login success and failure, `/auth/me`, tree create, update, idempotent repeat sync, stale-update ignore, duplicate Tree Code → 409, invalid latitude/longitude/accuracy → 422, change-feed version filtering and paging, image upload/replace/validation/auth/path traversal.
- **Android (163 tests):** 0.3.2 adds session routing (none → Login, valid → Dashboard with no backend call, expired → local Dashboard with sync not scheduled, restart persistence, explicit logout), expired-session sync block with no server request, re-login keeping Room data and resuming sync, sign-in banner and chip states, angle normalisation and the 359°/0° wrap, circular smoothing, heading from the rotation matrix (flat and upright), declination (true vs magnetic), relative bearing and the 8 relative directions, alignment tolerance, compass registration only while needed (no sensor leak), FREE / FOLLOW_LOCATION / HEADING_UP transitions, missing-compass handling, panel collapse with guidance continuing, and arrival while collapsed. 0.3.1 adds stale-speed expiry, repeated-spike rejection, gap reset, moving/stationary hysteresis, NaN/negative/infinite speed, measured-vs-walking-estimate display, "no fix yet" timing, and the Tag Tree "which field needs attention" order. The offline-map follow-up adds region validation and inside/outside checks, a cross-check that the Kotlin region, `tools/offline_map/region.json` and the bundled package metadata (size and SHA-256) agree, package extraction (first launch copies once, later launches do not, new version or damaged file re-copies, checksum mismatch fails cleanly, missing package), map-source setting default and legacy value, offline/online/background basemap resolution (never silently online), coverage state, default PSAU camera and GPS camera, first-fix re-centring and tree zoom limits. Give 2 adds field-navigation math (haversine distance, bearing, 8-point cardinal mapping, m/s → km/h, ETA, zero/negative/NaN speed, distance/duration formatting, geodesic path), `DirectRouteProvider` geometry, speed filtering (unavailable, stationary, smoothing, spike rejection, low-confidence readings), `FieldNavigationController` (start without a fix, distance/bearing updates, measured-speed ETA, labelled walking estimate, arrival once per session, stop, destination change, stale-session and stale-fix protection), Map ViewModel (marker selection and zoom, open/My Location zoom, follow mode cancelled by gestures, navigation GPS profile switch, single stream, no location-update leak, selected-tree distance, permission handling), Dashboard (Room counts, recent ordering, backend/GPS status mapping, Sync Now, re-check after a sync), basemap selection and backend status mapping, and last-successful-sync recording. Give 1 coverage: GPS accuracy classification, Tag Tree validation (blank/invalid code, invalid coordinates, negative accuracy, low accuracy), duplicate local Tree Code, local-first save, PENDING → SYNCING → SYNCED, FAILED retry, image-upload retry, conflict handling, remote-change upsert and cursor, login loading/success/failure/validation/duplicate submit, debug credential defaults, server URL normalization.

## 5. Simulating GPS on the emulator

```powershell
adb emu geo fix 120.694200 15.217800      # LONGITUDE first, then latitude: PSAU (point A)
adb emu geo fix 120.697000 15.219500      # ~340 m north-east, near PSAU (point B)
adb emu geo fix 120.698500 15.221500      # ~250 m further north-east (point C)
adb emu geo fix 120.660000 15.216700      # Magalang poblacion, still inside the offline map
adb emu geo fix 120.588700 15.144900      # outside the offline map (shows the coverage notice)
```

Turn networking off on the emulator for an offline test (restore with `disable` / `enable`):

```powershell
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
```

If `geo fix` is not supported, use **Emulator → Extended Controls → Location**. An emulator fix only proves the pipeline. The emulator reports its own accuracy value (5.0 m in testing), so it says nothing about real-world GPS accuracy.

GPS quality thresholds (`core/location/GpsAccuracyPolicy.kt`): ≤ 5 m **Good**, > 5–10 m **Acceptable**, > 10 m **Low**. A Low reading can still be saved after an explicit **Save Anyway** confirmation. Tagging always requests a fresh fix (`maxUpdateAge = 0`) and never saves a cached location. Users cannot type coordinates.

## 6. Offline behaviour

After one successful sign-in, the session is stored in DataStore and the app is a complete offline field tool.

**Session (0.3.2).** You sign in once per 7-day session.
- **Storage:** `SessionStore` (DataStore) keeps the access token, email and server-issued expiry. The backend's lifetime is `jwt_expire_minutes = 7 days` in `backend/app/config.py`.
- **Startup:** routing reads only the stored session; the server is never contacted.
  - No session → Login.
  - Valid session → Dashboard. This holds after closing, force-stopping or rebooting, and with Wi-Fi, mobile data and the backend all off.
  - Expired session → still the Dashboard. Trees, photos, offline map, GPS, Tag Tree and Field Guidance keep working.
- **Expired session:** sync pauses locally, with no request sent using the expired token. The Dashboard shows a non-blocking "Sign in to resume synchronization" banner, and the sync chips read "Sign in to sync".
  - **Sign In to Sync** (Dashboard or Settings) opens Login in re-sign-in mode, with **Continue without syncing** available.
  - Signing in replaces only the token, keeps all Room data, returns you to where you were, and resumes WorkManager sync.
  - A token the server rejects (401) triggers the same banner.
- **Sign Out** in Settings is the only way back to the Login screen. It keeps trees on the device.
- There are no refresh tokens in this prototype.

| Works offline (no internet, no backend) | Requires the backend (FastAPI + MongoDB) |
|---|---|
| ✓ Dashboard | Sync between devices |
| ✓ Room tree data | Shared MongoDB data |
| ✓ GPS | Remote image synchronization |
| ✓ Tag Tree | First sign-in on a new install |
| ✓ Camera | |
| ✓ Offline PSAU basemap (inside its coverage) | |
| ✓ Tree markers | |
| ✓ Direct Field Guidance | |
| ✓ Distance | |
| ✓ Bearing | |
| ✓ Speed/ETA calculation | |

New and edited trees stay `PENDING` until the backend is reachable. The Android app cannot start Docker on the laptop. There is no offline password system.

**Verified on the emulator, fully offline.** Setup: airplane mode on, Wi-Fi and mobile data off, `docker compose stop backend`, MapLibre's tile cache (`files/mbgl-offline.db`) deleted, emulator rebooted. Results:
- Dashboard: PASS
- Offline basemap: PASS
- GPS location: PASS
- Room tree markers: PASS
- Tag Tree (two trees): PASS
- Image persistence: PASS
- Direct navigation: PASS
- Distance and bearing: PASS
- Speed/ETA: local; walking estimate only, see limitations
- Arrival: PASS
- Outside-coverage notice: PASS
- Restart persistence: PASS

After reconnecting, WorkManager synced both trees, with images, without duplicates.

## 7. Sync behaviour

- `TreeSyncWorker` (WorkManager, `NetworkType.CONNECTED`) runs after each save, on app start, every 15 minutes, and on **Sync now**.
- Each run checks `/api/v1/health` first, because the dev server may be on a LAN. If the check fails, nothing is touched and records stay `PENDING`.
- Push: each `PENDING`/`FAILED` tree → `SYNCING` → `POST /api/v1/trees/sync` (upsert by UUID). If the server has no image yet, the app uploads it with `POST /api/v1/trees/{id}/image`, then marks the tree `SYNCED` with the server's `server_version`.
- Pull: `GET /api/v1/trees/changes?after_version=<n>` → upsert into Room. `lastPulledServerVersion` (DataStore) advances only after items are applied.
- Retries use exponential backoff (15 s base) and stop after 6 attempts. The periodic job, app start, or **Sync now** resumes later. Failures never delete local records.

**Prototype conflict policy**
- The same UUID sent again is a no-op (`unchanged`) unless its `updated_at` is newer. That makes sync idempotent and ignores stale writes.
- A different UUID with an existing Tree Code gets **409 `TREE_CODE_CONFLICT`**. The local tree becomes `FAILED` with an explanation, and Tree Detail offers **Change code**.
- Every accepted change gets a new server-side monotonic `server_version`. Device clocks are never used as the sync cursor.
- A pending local edit that is newer than the server copy is not overwritten by a pull.

## 8. Image storage

- Phone: `filesDir/tree_images/<uuid>.jpg` (about 2 MP JPEG from CameraX). Room stores `localImagePath` and `remoteImagePath`.
- Server: content-sniffed JPEG/PNG/WebP (max 10 MB) saved as `uploads/tree_images/<treeId>-<random>.<ext>`. MongoDB stores `image_path`.
- `GET /uploads/tree_images/{file}` requires the bearer token. Coil uses the app's authenticated OkHttp client.

## 9. Field guidance (navigation)

This is **direct field guidance**: a straight great-circle line from your GPS position to the tree. It does not follow roads or paths and it is not turn-by-turn navigation.

| Value | How it is computed |
|---|---|
| Distance | Haversine great-circle distance (mean Earth radius 6 371 008.8 m), never Cartesian lat/lon. Shown as `428 m` below 1 km and `1.4 km` from 1 km. |
| Direction | Initial great-circle bearing from you to the tree, as an 8-point label (N, NE, …) with degrees, relative to north. There is no magnetometer compass. |
| Current speed | Android's reported `Location.speed` × 3.6 = km/h, used only when `Location.hasSpeed()` is true. A reading is shown only when it passes these checks:<br>• it is finite and not negative<br>• fix accuracy is ≤ 50 m<br>• speed accuracy is ≤ 3 m/s, when Android reports it<br>• it is ≤ 30 m/s (one faster reading reuses the previous value; two in a row mean "unavailable")<br>A speed with no new fix for 8 s is dropped, so a stale value is never shown. Movement starts at 0.5 m/s and ends below 0.3 m/s (hysteresis). Light smoothing (moving average, weight 0.5). |
| ETA | **Measured:** "Current speed 4.7 km/h [GPS]" and "ETA at current speed 5 min" (remaining distance ÷ measured speed). **Not measured** (stationary, no speed from GPS, or stale): a separate muted box, "Walking estimate · ≈ 4 min", with the reason and the 5.0 km/h pace (1.4 m/s). It never uses the "Current speed" label. No division by zero. Format: `Less than 1 min`, `6 min`, `1 hr 12 min`. |
| Arrival | Remaining direct distance ≤ 10 m shows "Tree reached" once per session. |

All thresholds live in `NavigationConfig` (`feature/navigation/NavigationMath.kt`).

**GPS use.** Tagging still requests one fresh fix. The Map runs one continuous `requestLocationUpdates` stream at a time: about every 4 s for the locator, and every 2.5 s at high accuracy during guidance. The stream stops when you leave the Map tab, when the app goes to the background, and when the screen's ViewModel is cleared. Guidance pauses in the background (there is no foreground service). A fix older than 15 s is never used to start a session. On the emulator, `dumpsys location` showed a 2.5 s request while navigating, 4 s after Stop, and no request after leaving the Map tab.

**Map follow modes** (0.3.2)

| Mode | How you get there | Behaviour |
|---|---|---|
| FREE | Any pan or rotate gesture, or selecting a tree | You control the map. |
| FOLLOW_LOCATION | **My Location** button | Centres on you at zoom 17, north up, and follows each GPS fix at your zoom. |
| HEADING_UP | Tap the top-right **orientation control** or the compass in the guidance panel | Follows you and rotates the map with the phone compass. Tap again for North Up. |

Pinch-zoom keeps following. Panning or rotating returns to FREE. Without a compass sensor, Heading Up is refused with a message.

**Map controls.** There is exactly one of each, placed from measured sizes rather than fixed offsets:
- **Top right, under the top bar:** the GEO Tree orientation control. MapLibre's own compass is disabled so there is no duplicate.
- **Right side, above Tag Tree:** My Location (the only centring action; highlighted while following).
- **Bottom:** the guidance panel, expanded or collapsed.
- **Bottom left, above whatever occupies it:** MapLibre's logo and ⓘ attribution.

On short screens the expanded panel is capped to the space left and scrolls inside, so it never pushes the buttons into the top controls. Checked on the Medium Phone (1080×2400) and on a 720×1280 / 320 dpi display override.

**Camera.** With GPS, the map opens on you at zoom 15.5. Without GPS it opens on the PSAU deployment area at zoom 15 (never a country or world view). My Location goes to 17. Selecting a tree goes to 17.5, or keeps your zoom up to 18. Starting guidance frames you and the tree (max zoom 17.5). In offline mode you cannot zoom out past 12. Values live in `MapZoom` (`feature/locator/LocatorViewModel.kt`). The camera is never locked.

**Guidance panel (minimize / expand).** The **expanded** panel shows:
- Tree Code, distance, destination direction ("NE · 58°") and the relative turn ("Turn 34° right" / "Facing the tree")
- the navigation compass
- speed and ETA, or the walking estimate
- GPS accuracy
- **Stop Guidance**

The ▼ button or a swipe down on the handle **collapses** it to one bar: mini compass · Tree Code · distance · ETA · ▲. Tap the bar or swipe up to expand. Both views come from the same navigation session, so GPS, distance, ETA and arrival keep updating while collapsed.

**Phone heading and the navigation compass.**
- **Sensor:** `core/orientation/DeviceHeadingProvider.kt` reads `Sensor.TYPE_ROTATION_VECTOR` via `SensorManager`, falling back to accelerometer + magnetometer. It works offline.
- **Lifecycle:** sensors are registered only while guidance or Heading Up needs them *and* the Map is on screen. Leaving the Map, backgrounding the app, or stopping guidance unregisters them (unit-tested with a fake sensor).
- **Hold position:** heading is taken from the phone's top edge when it is held flat, and from the back camera's direction when it is held upright.
- **True north:** the rotation vector reports magnetic north. Android's `GeomagneticField` (World Magnetic Model, offline) gives the declination at your GPS position, and true = magnetic + declination. Before the first GPS fix the heading is shown as magnetic, never silently treated as true.
- **Smoothing:** light circular smoothing (weight 0.3 per reading). 359° and 1° average to 0°, not 180°.
- **Calibration:** low or unreliable sensor accuracy shows "compass needs calibration: move the phone in a figure-8".

The compass in the panel is north-up:
- **Grey:** the target. A centre line marks the exact bearing to the tree, and two faint side lines mark ±12° (`NavigationConfig.alignmentToleranceDegrees`). These are orientation aids, not roads.
- **Forest green:** the live arrow, where the phone is facing. It turns success green when inside the ±12° corridor.
- **Relative direction:** normalize(target − heading) as a signed turn, labelled ahead, ahead-right, right, behind-right, behind, behind-left, left or ahead-left.
- **Location marker:** while a heading is available it is a blue chevron pointing where the phone faces; otherwise it is the usual dot. GPS course is not used as a substitute, so a stationary user is never given a fake orientation.

**Routing extension point.** The UI depends only on `RouteProvider` → `RouteResult(geometry, distanceMeters, estimatedDurationSeconds, routeType)`. `DirectRouteProvider` is the only implementation. A future `OfflineRoadRouteProvider` or `ServerRoadRouteProvider` plugs in through `AppContainer.routeProvider` without touching the Dashboard, Map or navigation panel.

## 10. Offline PSAU / Magalang field map

The app ships a **real offline map** of the deployment area inside the APK. With Wi-Fi and mobile data off, the Map still shows streets, buildings, fields and place names inside the coverage area below. This was verified on the emulator in airplane mode after deleting MapLibre's tile cache and rebooting.

**Coverage (source of truth: `OfflineMapRegion.kt` = `tools/offline_map/region.json`)**

| | |
|---|---|
| Region | PSAU / Magalang |
| Latitude | 15.1980° N to 15.2400° N |
| Longitude | 120.6480° E to 120.7150° E |
| Size | about 7.2 km (E–W) × 4.6 km (N–S) |
| Includes | Pampanga State Agricultural University and its mapped areas (e.g. Livestock Village, Rice Village), Magalang poblacion (town centre), and the San Pablo, San Vicente and Ayala areas and roads between them |
| Default centre | 15.21851, 120.69557, the "Pampanga State Agricultural University" node in OpenStreetMap |
| Zoom levels | 13–17. Tiles are 512 px (2× density), so z17 has z18-level detail; MapLibre over-zooms beyond 17 |
| Package | `psau_magalang.mbtiles`: 593 WebP raster tiles, 5,754,880 bytes (5.8 MB), version `2026.10.01-r1` |
| Data | OpenStreetMap, snapshot 2026-07-15 |
| Licence | Map data © OpenStreetMap contributors, ODbL 1.0. Attribution is shown in the map's ⓘ button and in Settings |

It does **not** cover all of Magalang, Mount Arayat, or the rest of Pampanga. Outside the box the map shows a plain shaded background with a dashed edge, and a small notice says "Outside offline field map". GPS, saved trees and field guidance keep working there.

**How it works**
- Format: raster **MBTiles** (SQLite), read by MapLibre 11.8.8's built-in `mbtiles://` source. No new dependency.
- Why raster: vector tiles would also need offline font glyphs and extra tooling. Raster tiles with labels drawn in keep the app simple, and the size is small for this area.
- First launch: the Splash step "Preparing offline field map…" copies the package once from `assets/offline_map/` to `filesDir/offline_map/`. MapLibre needs a real file, not an APK asset. The copy is size- and SHA-256-verified.
- Later launches compare the installed marker (`installed.json`) with the bundled metadata and file size, and copy only when the version changes or the file is damaged.
- The style is muted sand and moss, so the red route, red destination and blue GPS dot stay dominant.

**Map source (Settings → Map)**
- ● **Offline PSAU Map** (default)
- ○ **Online Map**: OpenStreetMap tiles over the internet. Uses data; not an offline map.

If the offline package is unavailable, the app does **not** silently switch to online tiles. It shows a plain background and explains why. Settings also shows offline map status (Ready / Preparing / Not installed / Unavailable), region, coverage, zoom levels, package size and version. The Dashboard has a small "Offline map ready" indicator.

**How the package was made (no tile scraping)**

The map was **not** made by downloading tiles from `tile.openstreetmap.org` or any other tile server. The steps were:
1. `tools/offline_map/fetch_osm.py` sends **one** Overpass API query for the bounding box and saves the OSM *data* (`osm_raw.json`, gitignored).
2. `tools/offline_map/build_offline_map.py` renders every tile locally with Pillow and writes the MBTiles file plus `metadata.json` into the app's assets.

To rebuild (for example after changing `region.json`, then update `OfflineMapRegion.kt` to match):

```powershell
cd tools\offline_map
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install pillow
.\.venv\Scripts\python.exe fetch_osm.py           # only to refresh the OSM data; one request
.\.venv\Scripts\python.exe build_offline_map.py   # about 40 s; writes android\app\src\main\assets\offline_map\
cd ..\..
.\gradlew.bat :android:app:testDebugUnitTest      # checks region.json, OfflineMapRegion.kt and metadata agree
```

Raising `max_zoom` to 18 roughly quadruples the tile count. Reduce the bounds first if size matters.

**Developer override.** A raster MBTiles or PMTiles file side-loaded with `adb push <file> /sdcard/Android/data/com.geotree.app/files/maps/` replaces the bundled map; use **Settings → Rescan for side-loaded map packages**. PMTiles has not been tested with a real file. Vector (`pbf`) packages are rejected because they need a matching style. Map-provider code is isolated in `feature/locator/map/`.

## API

```text
GET  /api/v1/health
POST /api/v1/auth/login
GET  /api/v1/auth/me
POST /api/v1/trees/sync
GET  /api/v1/trees/changes?after_version=<n>&limit=<1..500>
GET  /api/v1/trees/{id}
POST /api/v1/trees/{id}/image           (multipart field: file)
GET  /uploads/tree_images/{filename}
```

Errors are structured as `{"error": {"code": "...", "message": "...", "details": {...}}}`.

## Known limitations

- **Offline map coverage is limited to the box in section 10.** Elsewhere the map shows a plain background; GPS, records and guidance still work. OSM data in the area is uneven: many fields are not mapped as farmland, and the tamarind plots themselves are not in OSM. The map shows OSM as of 2026-07-15.
- The offline map is raster, so labels cannot rotate with the map and text gets softer above zoom 18.
- **Guidance is a direct line**, not a road or path route. It can point across buildings, fences and water.
- **Emulator speed:** `adb emu geo fix` reports a speed of 0, and this emulator image ignored `geo nmea` sentences. The measured-speed and ETA paths are covered by unit tests only and need a walk test on a real phone. Emulator accuracy (5.0 m) is synthetic.
- **Keyboard on the emulator:** the Medium_Phone AVD has a hardware keyboard, so Gboard shows only its compact toolbar or a *floating* keyboard; floating keyboards never resize apps. The Save bar was verified to rise by the keyboard inset the app receives and to stay tappable with the keyboard open, but a full docked keyboard needs a check on a real phone.
- Guidance pauses when the Map tab is not visible or the app is in the background (no foreground location service). The compass is off then too.
- **Compass not validated on a real phone yet.** On the emulator the heading pipeline was driven with virtual accelerometer/magnetometer values (relative turn, alignment, chevron and Heading Up all responded). That proves the code path, not real compass behaviour, magnetic interference or calibration. Run the physical heading test below.
- In Heading Up the raster basemap rotates, so its drawn labels rotate too.
- Session expiry uses the phone's clock; a badly wrong clock can pause sync early or late (the server still enforces the real expiry).
- **The emulator camera shows a virtual room**, not a tree. Capture, preview, file persistence and upload are verified, but real photo quality needs a physical device.
- Emulator GPS accuracy is synthetic. Field accuracy has to be measured on real devices.
- One user role and development authentication only. JWTs last 7 days. After expiry, sync pauses ("Sign in to sync") while local work continues.
- Editing after save is limited to changing the Tree Code (to resolve conflicts). There is no delete in Give 1.
- The debug APK is large (about 63 MB, of which the offline map is about 5.4 MB compressed) because MapLibre ships native libraries for all ABIs. Use ABI splits or an App Bundle for release.

## Next milestone

Field-test the offline map, GPS accuracy and guidance on physical phones at PSAU, and improve local map data where it is missing (OpenStreetMap edits, or an approved PSAU campus map). After that: AI leaf assessment: capture a leaf photo from Tree Detail and classify it on-device. This builds on the existing tree UUID, image pipeline and sync.
