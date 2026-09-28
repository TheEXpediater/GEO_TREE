<<<<<<< HEAD
# GEO Tree

Android field-mapping app for registering and geotagging tamarind trees. It works offline first and syncs to a shared FastAPI/MongoDB backend.

## Give 1 scope

Give 1 proves geotagging end to end:

```text
Splash → Dev Login → Field Locator → Tag Tree → Capture Image → Tree Code → Fresh GPS fix
→ Save to Room (PENDING) → Marker appears → WorkManager sync (metadata + image) → FastAPI → MongoDB
→ SYNCED → other devices pull the record
```

Not in Give 1: leaf-disease AI, TensorFlow Lite, OpenCV, Roboflow, Qdrant, dashboards, production hosting.

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
| Backend | `backend/app/` (`routes`, `services`, `repositories`, `schemas`, `auth`, `database`) |
| Backend tests | `backend/tests/` (isolated in-memory DB; never touches dev data) |

## Prerequisites (Windows)

- Android Studio with SDK platform 35 and an emulator (tested on the **Medium_Phone** AVD, API 37)
- A **JDK 17 or 21**. Gradle 8.10 cannot run on JDK 25 (Android Studio's current bundled JBR). The build uses Gradle's daemon toolchain (`gradle/gradle-daemon-jvm.properties` → `toolchainVersion=21`), so Gradle finds a JDK 21 automatically (for example one installed by IntelliJ/Android Studio under `%USERPROFILE%\.jdks`). If it can't, point `JAVA_HOME` at a JDK 21.
- Docker Desktop
- Python 3.12+ (only needed to run the backend outside Docker or to run backend tests)

## 1. Start MongoDB + backend (Docker)

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

Android Studio: open the repository root (not `android/`). `local.properties` (`sdk.dir=...`) is created by Android Studio and is gitignored.

### Backend address

| Where the app runs | Base URL |
|---|---|
| Emulator (default) | `http://10.0.2.2:8000` |
| Physical phone on the same Wi-Fi | `http://<laptop-LAN-IP>:8000` |

Change it in the app (**Login → Server: Change**, or **Locator menu → Server settings**). The app calls `/api/v1/health`, confirms that the server is GEO Tree, and saves the URL only if that check passes. You can also build with a different default: `.\gradlew.bat :android:app:assembleDebug -Pgeotree.baseUrl=http://192.168.1.20:8000`. A later hosted HTTPS URL uses the same mechanism; repositories and API contracts don't change.

Physical phone: allow inbound TCP 8000 in Windows Firewall. Debug builds allow cleartext HTTP; release builds are HTTPS-only.

## 4. Tests

```powershell
cd backend; .\.venv\Scripts\python.exe -m pytest -q; cd ..
.\gradlew.bat :android:app:testDebugUnitTest
```

- **Backend (29 tests):** health, dev-admin seed idempotency, login success and failure, `/auth/me`, tree create, update, idempotent repeat sync, stale-update ignore, duplicate Tree Code → 409, invalid latitude/longitude/accuracy → 422, change-feed version filtering and paging, image upload/replace/validation/auth/path traversal.
- **Android (42 tests):** GPS accuracy classification, Tag Tree validation (blank/invalid code, invalid coordinates, negative accuracy, low accuracy), duplicate local Tree Code, local-first save, PENDING → SYNCING → SYNCED, FAILED retry, image-upload retry, conflict handling, remote-change upsert and cursor, login loading/success/failure/validation/duplicate submit, debug credential defaults, server URL normalization.

## 5. Simulating GPS on the emulator

```powershell
adb emu geo fix 120.660000 15.216700      # LONGITUDE first, then latitude
adb emu geo fix 120.660400 15.217100      # a second location ~60 m away
```

If `geo fix` is not supported, use **Emulator → Extended Controls → Location**. An emulator fix only proves the pipeline. The emulator reports its own accuracy value (5.0 m in testing), so it says nothing about real-world GPS accuracy.

GPS quality thresholds (`core/location/GpsAccuracyPolicy.kt`): ≤ 5 m **Good**, > 5–10 m **Acceptable**, > 10 m **Low**. A Low reading can still be saved after an explicit **Save Anyway** confirmation. Tagging always requests a fresh fix (`maxUpdateAge = 0`) and never saves a cached location. Users cannot type coordinates.

## 6. Offline behaviour

After one successful sign-in, the session is stored in DataStore and everything below works with the backend stopped: viewing trees, opening details, GPS capture, camera capture, saving, markers, and restarting the app. A fresh, never-signed-in install needs the backend once in order to sign in. There is no offline password system in Give 1.

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

- **Map tiles need internet.** The basemap is MapLibre with OpenStreetMap raster tiles, because no Google Maps key was available. Tiles you have already viewed are cached, but the basemap is not a true offline map. Tree records and markers do not depend on tiles. A later milestone can swap `STYLE_JSON` in `feature/locator/map/TreeMap.kt` for offline MBTiles/PMTiles. OSM's public tile server is for light development use only.
- **The emulator camera shows a virtual room**, not a tree. Capture, preview, file persistence and upload are verified, but real photo quality needs a physical device.
- Emulator GPS accuracy is synthetic. Field accuracy has to be measured on real devices.
- One user role and development authentication only. JWTs last 7 days. After expiry, sync pauses ("Sign in to sync") while local work continues.
- Editing after save is limited to changing the Tree Code (to resolve conflicts). There is no delete in Give 1.
- The debug APK is large (about 57 MB) because MapLibre ships native libraries for all ABIs. Use ABI splits or an App Bundle for release.

## Next milestone

AI leaf assessment: capture a leaf photo from Tree Detail and classify it on-device. This builds on the existing tree UUID, image pipeline and sync.
=======
# GEO_TREE
>>>>>>> origin/main
