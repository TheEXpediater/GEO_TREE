# GEO Tree — CLAUDE.md

## Mission

Build and maintain **GEO Tree**, an offline-first Android field-management application for registering, geotagging, locating, navigating to, monitoring, and later assessing tamarind trees.

GEO Tree is an independent project. Treat this repository as the authoritative source for its architecture and implementation. Do not search for or depend on previous projects unless the user explicitly provides them for comparison.

The application is designed around this architecture:

```text
Compose UI
    ↓
ViewModel + StateFlow
    ↓
Repository
    ↓
Room
    ↓
WorkManager
    ↓
FastAPI
    ↓
MongoDB
```

Room is the Android source of truth.

FastAPI and MongoDB provide shared synchronization between devices but must never be required for core offline field operation.

Current development priorities are:

1. Dashboard and authenticated application shell
2. Tree geotagging and information management
3. Interactive map and field locator
4. GPS-based field navigation to registered trees
5. Reliable offline operation and synchronization
6. Physical-device testing and APK delivery
7. AI tamarind leaf assessment in a later milestone

---

## Engineering Rules

1. Inspect before editing.
2. Prefer the smallest working vertical slice.
3. Keep data flow explicit: UI → ViewModel → Repository → Room/API.
4. Offline first. The field workflow must not require the backend.
5. Save locally before attempting sync.
6. Android never connects directly to MongoDB.
7. Prefer proven technology and low dependency count.
8. Room is the on-device operational source of truth.
9. MongoDB is the shared server source of truth after synchronization.
10. Never fabricate success, GPS values, screenshots, test results, or API responses.
11. Run tests and builds after meaningful changes.
12. Fix root causes instead of hiding errors.
13. Do not rewrite unrelated working code.
14. Never commit secrets, `.env`, API keys, keystores, or personal machine paths.
15. Development credentials are debug-only.
16. Accessible, clear UI is more important than decoration.
17. Before destructive commands, explain the effect and require explicit approval.
18. Preserve working behavior while extending the system.
19. Do not replace a working subsystem merely because another implementation looks cleaner.
20. If a capability is incomplete or environment-dependent, state that clearly instead of pretending it is finished.

---

## Product Identity

Working name: **GEO Tree**

Visual direction: a modern agricultural field-research application with the precision and atmosphere of an archaeological survey tool.

Use:

- deep forest green
- tamarind/moss green
- warm clay accent
- sand/cream surfaces
- charcoal text
- restrained amber for GPS acquisition
- green for ready/accurate states
- red only for errors, destructive actions, or active route guidance
- subtle map/compass/cartographic cues

Avoid childish farming graphics, generic corporate blue, military radar styling, clutter, and fake parchment.

Create an original vector GEO Tree logo combining:

- location pin
- tamarind leaf/branch
- subtle compass/survey cue

Do not use a university seal unless supplied.

---

## Authenticated Application Structure

After successful authentication, open the authenticated application shell.

Primary bottom navigation:

```text
Dashboard | Map | Settings
```

Dashboard is the default authenticated destination.

Tag Tree must remain quickly accessible from both Dashboard and Map.

Do not create multiple competing navigation systems unless there is a clear architectural need.

---

## Required Screens

### Splash

- GEO Tree logo
- GEO Tree wordmark
- short subtitle such as `Field Mapping`
- real initialization/loading state
- no long fake delay

### Development Login

Debug builds only:

- Email prefilled: `admin@gmail.com`
- Password prefilled: `admin123`
- show/hide password
- validation
- loading state
- inline error state

Release builds must not silently ship or depend on these defaults.

### Dashboard

Dashboard is the first authenticated screen.

It should use Room data and remain functional when the backend is unavailable.

Show:

- total registered trees
- synced trees
- pending trees
- failed synchronization count
- GPS status
- backend connection status
- last synchronization time
- recent trees
- quick actions for Tag Tree, Open Map, and Sync Now

Do not fetch dashboard data directly from MongoDB or make the dashboard depend on a live API response.

### Field Locator / Map

The Map tab is the primary spatial interface.

It should provide:

- interactive map
- current-device location
- registered tree markers
- GPS/accuracy indicator
- sync status indicator
- current-location control
- street/field-level zoom behavior
- prominent **Tag Tree** action
- selected-tree bottom card/sheet
- field navigation to a selected tree

Markers must be driven from Room, not directly from network responses.

### Tag Tree

Top-to-bottom:

1. Large tree image capture/preview
2. Tree Code, e.g. `GEO-TAM-003`
3. Latitude
4. Longitude
5. GPS accuracy in meters
6. Capture timestamp
7. Acquire/Refresh Location
8. Save Tag

Use UUID internally. Never use coordinates as identity.

Prototype GPS status:

- <= 5 m: Good
- > 5 m and <= 10 m: Acceptable
- > 10 m: Low accuracy; recommend recapture

Keep thresholds centralized/configurable.

### Tree Detail

Show:

- image
- Tree Code
- latitude/longitude
- accuracy
- capture time
- sync status
- center-on-map action
- Navigate to Tree action
- future leaf-assessment section clearly marked as future work

---

## Android Stack

Required:

- Kotlin
- Jetpack Compose
- Material 3
- Navigation Compose
- ViewModel + StateFlow
- Coroutines
- Room
- DataStore
- Fused Location Provider / Android Location Services
- CameraX
- Retrofit + OkHttp
- WorkManager
- Coil
- Gradle Kotlin DSL
- MapLibre for the current map implementation

Prefer a small explicit `AppContainer` unless Hilt already exists or clearly reduces complexity.

Suggested structure:

```text
android/app/src/main/java/<package>/
  GeoTreeApplication.kt
  MainActivity.kt

  core/
    database/
    network/
    location/
    camera/
    sync/
    session/
    design/

  feature/
    splash/
    login/
    dashboard/
    locator/
    navigation/
    tagtree/
    treedetail/
    settings/

  data/
    local/
    remote/
    repository/
    model/
```

Do not create abstractions with no current purpose.

---

## Offline Data Model

MongoDB is the server database, but **Room is required on the phone** so the application works offline.

Minimum `TreeEntity`:

```text
id: String                 // UUID
treeCode: String
imageLocalPath: String?
remoteImagePath: String?
latitude: Double
longitude: Double
accuracyMeters: Float
altitudeMeters: Double?
locationCapturedAt: Long
createdAt: Long
updatedAt: Long
syncStatus: PENDING | SYNCING | SYNCED | FAILED
serverVersion: Long?
lastSyncError: String?
```

Rules:

- UUID is technical identity.
- Tree Code is human-readable and locally unique.
- Coordinates are data, not identity.
- Do not store image blobs in Room.
- Preserve records if networking fails.
- Room migrations must be explicit.
- UI-visible tree state should be derived from Room.
- Server responses should be reconciled back into Room instead of becoming a second source of truth.

---

## GPS Rules

Use Android location APIs, not browser geolocation.

Permissions:

- `ACCESS_COARSE_LOCATION`
- `ACCESS_FINE_LOCATION`
- camera permission

For a user-initiated tree tag:

- request a fresh current location
- prefer high accuracy
- surface Android's reported accuracy
- store capture time
- handle null location
- handle denied permission
- handle approximate-only permission
- handle disabled location services
- never invent coordinates

Do not perform location work directly in a Composable.

For active navigation, use a controlled continuous location stream rather than repeatedly calling one-shot tagging APIs.

Stop location updates when navigation ends or when lifecycle rules require it.

---

## Map Rules

The current application uses MapLibre.

Tree records, markers, GPS state, and field guidance must never depend on map-tile availability.

Current behavior:

- GPS works independently of internet access.
- Saved tree records work offline.
- Tree markers work offline because they come from Room.
- A real offline raster MBTiles map of the PSAU / Magalang deployment area is bundled in the APK (`assets/offline_map/`), installed once to app storage, and is the default map source.
- Offline coverage is only the box in `OfflineMapRegion.kt` (kept equal to `tools/offline_map/region.json`); never describe it as covering more.
- The online OpenStreetMap raster basemap is an opt-in alternative and is not offline; previously viewed tiles may be cached, but that is not offline-map support.
- If the offline map is unavailable, do not silently switch to online tiles.

Keep map-provider-specific code isolated.

Keep geographic constants for the deployment area in `OfflineMapRegion.kt` only.

The offline package is rendered locally from OpenStreetMap data (ODbL, attribution required) by `tools/offline_map/`. Do not scrape or bulk-download public OSM raster tiles.

### Camera behavior

Use practical field-level zoom behavior:

- current-location overview: approximately zoom 15–16
- My Location: approximately zoom 16.5–17.5
- selected tree: approximately zoom 17–18
- navigation: keep a useful street/field-level view

Do not lock the camera. Users must still be able to pan and zoom.

Use explicit Follow Location behavior rather than constantly forcing the camera back to the user.

---

## Field Navigation

Initial navigation is **field guidance**, not full Google Maps-style turn-by-turn road navigation.

Required flow:

```text
Current GPS
    ↓
Selected Tree
    ↓
Navigate to Tree
    ↓
Guidance Line
    ↓
Distance + Bearing + Speed + ETA
    ↓
Arrival
```

The initial guaranteed-offline route may be a direct/geodesic line from current position to the selected tree.

Do not call this turn-by-turn navigation or road routing.

Use a route abstraction such as `RouteProvider` with an initial implementation such as `DirectRouteProvider` so future road-routing implementations can be added without rewriting the Map or navigation UI.

During active navigation, provide:

- destination Tree Code
- remaining distance
- bearing/direction
- current GPS speed when reliable
- estimated arrival time
- GPS accuracy
- Stop Navigation action

Use a configurable arrival radius, initially around 10 meters.

### Speed

When Android reports a reliable speed:

```text
km/h = m/s × 3.6
```

Do not fabricate speed.

### ETA

When reliable speed exists:

```text
ETA = remaining distance / current speed
```

Do not divide by zero.

If stationary or speed is unavailable, either show ETA unavailable or use a clearly labeled fallback walking estimate based on a centralized configuration value.

Never present a fallback walking speed as the user's actual speed.

---

## Backend

Stack:

- Python
- FastAPI
- Pydantic
- PyMongo
- MongoDB in Docker
- JWT authentication
- bcrypt-compatible password hashing
- pytest

Architecture:

```text
Android → FastAPI → MongoDB
```

Never:

```text
Android → MongoDB
```

Root `compose.yaml` should support MongoDB and the backend. Use named MongoDB volumes. Never delete volumes automatically.

Database: `geo_tree`

Collections:

- `users`
- `trees`

Do not add Qdrant or AI services until the AI milestone.

---

## Development Authentication

Seed only in development and only if missing:

```text
admin@gmail.com
admin123
```

Store a secure password hash on the backend.

Minimum routes:

```text
GET  /api/v1/health
POST /api/v1/auth/login
GET  /api/v1/auth/me
```

---

## Sync

UI always observes Room.

Flow:

```text
Create/Edit
→ save Room
→ PENDING
→ WorkManager waits for connectivity
→ backend health check
→ SYNCING
→ FastAPI upsert by UUID
→ image upload when needed
→ SYNCED
→ pull remote changes
→ upsert Room
→ UI updates from Room
```

Requirements:

- idempotent upsert
- same UUID synced twice must not duplicate
- duplicate Tree Code must produce an explicit conflict
- failed sync must not delete local data
- use a documented simple conflict policy for the prototype
- use server-side version metadata for incremental multi-device synchronization
- do not rely on device clocks as the primary sync cursor

Prefer:

```text
GET /api/v1/trees/changes?after_version=<n>
```

when the existing backend already supports server versions.

---

## Development Networking

Android emulator backend:

```text
http://10.0.2.2:8000
```

Physical phone on LAN:

```text
http://<laptop-lan-ip>:8000
```

Future hosted URL must be configurable without redesigning repositories.

Never hardcode a personal LAN IP.

Use the existing backend connection manager as the single source of truth for backend URL configuration.

Before persisting a manually entered backend URL, verify the GEO Tree health endpoint.

---

## Local Backend

Development topology:

```text
Android Phone / Emulator
        ↓
Wi-Fi / LAN
        ↓
Windows Development Computer
        ↓
Docker Desktop
        ↓
FastAPI + MongoDB
```

The Android application cannot start Docker Desktop or containers on another computer.

The repository should provide a simple `run.bat` or equivalent launcher that:

1. checks Docker availability
2. starts GEO Tree services with Docker Compose
3. waits for the FastAPI health endpoint
4. prints localhost and likely LAN addresses

Preserve Docker Compose:

```text
restart: unless-stopped
```

If Docker Desktop starts with Windows, GEO Tree containers may automatically restart.

If the backend is unavailable:

- GPS must continue working
- Dashboard must still show local data
- existing trees must remain accessible
- Tag Tree must still save locally
- Map markers must still work
- direct field guidance must still work
- new/edited records remain pending until synchronization becomes possible

Never automatically delete MongoDB or upload volumes.

---

## UI Quality

- edge-to-edge
- responsive phone layout
- portrait first
- no fixed pixel layouts
- touch-friendly controls
- keyboard-safe forms
- loading/empty/offline/error/success states
- no blocking work on main thread
- no giant composables
- meaningful content descriptions
- restrained motion

The application should feel like a real field instrument: current-location cue, compact coordinates, accuracy chip, sync status, marker selection, route guidance, and clear bottom-sheet information.

---

## Current Acceptance Flow

```text
Launch
→ Splash
→ Dev Login
→ Dashboard
→ Map
→ Tag Tree
→ Capture Image
→ Enter Tree Code
→ Acquire GPS
→ Review coordinates + accuracy
→ Save locally
→ Marker appears immediately
→ PENDING
→ Backend reachable
→ WorkManager sync
→ SYNCED
→ Select Tree
→ Navigate to Tree
→ Guidance line + distance + bearing + speed/ETA
→ Arrival
```

---

## Tests

Backend:

- health
- login success/failure
- tree sync create/update
- idempotent repeat sync
- duplicate Tree Code
- change retrieval/version filtering
- invalid coordinates
- image upload behavior

Android:

- GPS accuracy-state mapping
- form validation
- duplicate Tree Code handling
- repository local-first save
- sync transitions
- login loading/error/success state
- dashboard counts/state mapping
- distance calculation
- bearing calculation
- cardinal direction mapping
- speed conversion
- ETA calculation
- zero-speed handling
- arrival radius
- navigation start/stop

Commands:

```powershell
python -m pytest -q
.\gradlew.bat :android:app:testDebugUnitTest
.\gradlew.bat :android:app:assembleDebug
adb devices
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

For emulator GPS, after confirming an emulator is connected:

```powershell
adb emu geo fix <longitude> <latitude>
```

The emulator command uses longitude first. If unsupported, use Android Studio Emulator → Extended Controls → Location.

Test at least two locations and verify persistence after force-stop/relaunch.

---

## Definition of Done

The current milestone is done only when:

- project builds cleanly
- MongoDB and FastAPI start through Docker
- FastAPI health works
- dev admin login works
- debug credentials are prefilled
- GEO Tree branding/logo renders
- Dashboard is the authenticated landing page
- bottom navigation works
- Field Locator/Map renders
- location permission flow works
- simulated emulator coordinates are acquired
- latitude, longitude, accuracy, timestamp are shown
- tree image can be captured
- Tree Code can be entered
- tree saves to Room without server
- marker appears from Room
- record survives app restart
- offline record becomes PENDING/FAILED without data loss
- record later syncs to FastAPI/MongoDB
- repeated sync does not duplicate
- selected tree can start field navigation
- direct guidance line updates from current GPS
- distance and bearing update correctly
- speed/ETA handles stationary and moving states safely
- navigation can stop without leaking location updates
- dashboard/map remain useful while backend is offline
- backend tests pass
- Android unit tests pass
- debug APK builds
- smoke test runs on the existing Medium Phone emulator
- physical-test APK can be produced after successful tests
- README contains exact setup/run/test instructions
- no fake or unverified success is reported

---

## Out of Scope Unless Explicitly Requested

Do not implement yet:

- leaf disease recognition
- TensorFlow Lite model
- OpenCV preprocessing
- Roboflow
- Teachable Machine
- Qdrant
- full road-routing engine
- turn-by-turn voice navigation
- production cloud deployment
- push notifications
- complex user-role system
- university SSO
- 3D features

Prepare clean extension points only.

---

## Working Procedure

For each substantial change:

1. Inspect.
2. Plan briefly.
3. Identify risks and existing behavior that must be preserved.
4. Implement one coherent vertical slice.
5. Run focused tests.
6. Run broader tests/build.
7. Exercise the emulator.
8. Verify offline behavior when relevant.
9. Report files changed, commands run, results, blockers, limitations, and next safest step.

Do not ask the user to manually perform work that can be completed through the available shell, Gradle, ADB, Docker, or Python environment.

---

## Core Architectural Principle

```text
GPS / Camera
     ↓
Compose UI
     ↓
ViewModel
     ↓
Repository
     ↓
Room  ← Android source of truth
     ↓
WorkManager
     ↓
FastAPI
     ↓
MongoDB
```

If FastAPI/MongoDB disappears, GPS tagging, local records, Dashboard data, existing markers, and direct field navigation must still work.
