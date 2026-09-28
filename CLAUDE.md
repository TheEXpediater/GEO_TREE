# GEO Tree — CLAUDE.md

## Mission

Build **GEO Tree**, a professional Android field-mapping application for registering and geotagging tamarind trees.

The older Museum App may be used only as a technical reference for proven patterns such as Kotlin/Jetpack Compose, FastAPI, MongoDB, Docker, Retrofit, authentication, and local-network development. Do not copy museum-specific names, assets, routes, data, or domain logic.

The first build must prove this vertical slice:

**Login → Field Locator → Capture Tree Image → Acquire GPS → Show Latitude/Longitude/Accuracy → Save Offline → Show Map Marker → Sync to FastAPI/MongoDB when reachable.**

Do not implement leaf-disease AI yet.

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
- red only for errors/destructive actions
- subtle map/compass/cartographic cues

Avoid childish farming graphics, generic corporate blue, military radar styling, clutter, and fake parchment.

Create an original vector GEO Tree logo combining:
- location pin
- tamarind leaf/branch
- subtle compass/survey cue

Do not use a university seal unless supplied.

## Required First-Build Screens

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

### Field Locator
Primary screen:
- interactive map
- current-device location
- registered tree markers
- GPS/accuracy indicator
- sync status indicator
- current-location control
- prominent **Tag Tree** action
- selected-tree bottom card/sheet

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
- future leaf-assessment section clearly marked as future work

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
    map/
    tagtree/
    treedetail/
  data/
    local/
    remote/
    repository/
    model/
```

Do not create abstractions with no current purpose.

## Offline Data Model

MongoDB is the server database, but **Room is required on the phone** so the application works offline.

Minimum `TreeEntity`:

```text
id: String                 // UUID
treeCode: String
imageLocalPath: String?
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

## Map Rules

The design must allow true offline maps later.

For this first build:
- markers come from Room
- current location is independent of backend state
- isolate map-provider-specific code
- never make tree records depend on map tiles

MapLibre is preferred if it can be integrated reliably without secrets. If another map SDK is used initially, keep it replaceable and do not commit API keys.

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

Root `compose.yaml` should support MongoDB and, when practical, the backend. Use named MongoDB volumes. Never delete volumes automatically.

Suggested database: `geo_tree`

Collections:
- `users`
- `trees`

Do not add Qdrant or AI services in this build.

## Development Authentication

Seed only in development and only if missing:

```text
admin@gmail.com
admin123
```

Store a secure hash on the backend.

Minimum routes:

```text
GET  /api/v1/health
POST /api/v1/auth/login
GET  /api/v1/auth/me
```

## Sync

UI always observes Room.

Flow:

```text
Create/Edit
→ save Room
→ PENDING
→ WorkManager waits for connectivity
→ SYNCING
→ FastAPI upsert by UUID
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
- keep timestamps/version metadata for future multi-device sync

Suggested routes:

```text
POST /api/v1/trees/sync
GET  /api/v1/trees/changes?since=<cursor-or-timestamp>
GET  /api/v1/trees/{id}
```

## Development Networking

Android emulator backend:
```text
http://10.0.2.2:8000
```

Physical phone on LAN:
```text
http://<laptop-lan-ip>:8000
```

Future hosted URL must be configurable without redesigning repositories. Never hardcode a personal LAN IP.

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

The locator should feel like a real field instrument: current-location cue, compact coordinates, accuracy chip, sync status, marker selection, and bottom-sheet details.

## First-Build Acceptance Flow

```text
Launch
→ Splash
→ Dev Login
→ Field Locator
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
```

## Tests

Backend:
- health
- login success/failure
- tree sync create
- idempotent repeat sync
- duplicate Tree Code
- change retrieval
- invalid coordinates

Android:
- GPS accuracy-state mapping
- form validation
- duplicate Tree Code handling
- repository local-first save
- sync transitions
- login loading/error/success state

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

## Definition of Done

The first build is done only when:
- project builds cleanly
- MongoDB starts through Docker
- FastAPI health works
- dev admin login works
- debug credentials are prefilled
- GEO Tree branding/logo renders
- Field Locator renders
- location permission flow works
- simulated emulator coordinates are acquired
- latitude, longitude, accuracy, timestamp are shown
- tree image can be captured
- Tree Code can be entered
- tree saves to Room without server
- marker appears from Room
- record survives app restart
- offline record becomes PENDING
- record later syncs to FastAPI/MongoDB
- repeated sync does not duplicate
- backend tests pass
- Android unit tests pass
- debug APK builds
- smoke test runs on the existing Medium Phone emulator
- README contains exact setup/run/test commands
- no fake or unverified success is reported

## Out of Scope

Do not implement yet:
- leaf disease recognition
- TensorFlow Lite model
- OpenCV
- Roboflow
- Qdrant
- analytics dashboard
- production hosting
- 3D
- complex role system
- push notifications

Prepare clean extension points only.

## Working Procedure

For each substantial change:
1. Inspect.
2. Plan briefly.
3. Implement one coherent slice.
4. Run focused tests.
5. Run broader tests/build.
6. Exercise the emulator.
7. Report files changed, commands run, results, blockers, limitations, and next safest step.

## Core Architectural Principle

```text
GPS / Camera
     ↓
  Android
     ↓
   Room  ← UI reads here
     ↓
WorkManager
     ↓
 FastAPI
     ↓
 MongoDB
```

If FastAPI/MongoDB disappears, GPS tagging and local records must still work.
