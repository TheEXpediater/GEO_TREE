# GEO Tree

Android field-management app for registering, geotagging, locating and navigating to tamarind trees, with DINOv2-based tamarind leaf condition assessment. It works offline first and syncs to a shared FastAPI/MongoDB backend.

## Render + Atlas production and web Mobile Settings (2026-10-24)

- **Hosting:** production stays on the Render API `https://geo-tree.onrender.com` with MongoDB Atlas (section 12). The Hostinger VPS expired (unreachable on 2026-10-24) and is not used. Release builds default to Render; Gradle refuses local, non-HTTPS and retired Hostinger hosts for a release.
- **Web admin → Mobile Settings** (`/mobile`): the APK `/download` serves (read from the file itself: package, version, size, SHA-256, signing certificate, bundled model and its checksums), release checks, a release history that starts at the first registration (a baseline's original date is stored as unknown), the download link and its QR code, the AI model profile (server model and APK model shown separately; every metric labelled with its evaluation type and sample count) and the dataset profile with private archive upload and validation. API: `/api/v1/admin/mobile/*` (Admin read, Super Admin registers releases). See "Mobile Settings operations" in section 12.
- **Not yet available on Render:** the deployed Render service predates Mobile Settings (not pushed), does not build the web admin, and has no durable place for the APK or dataset archives, so `/download` answers 404 there. The existing printed QR still encodes `https://geotree.museuma7k9x3.tech/download` (the expired VPS).

## Dataset v3, Powdery Mildew and the Render API (0.8.0)

- **New classifier dataset (2026-10-20).** The active training set was replaced with the labeled photos in `data-sets.zip` (local only, never committed; SHA-256 `82775506…`). Classes are now **Healthy, Spotting, Powdery Mildew**: the old class `Mold` was renamed in place (same class id, so earlier assessments and models stay consistent) and source folders `Mold`/`Molds` map to Powdery Mildew. Of 184 photos, 181 are usable (2 conflicting copies of `IMG_7259` and 1 exact duplicate excluded); 144 are the active training set and 37 are a **locked test set** that never entered training. See section 11, "Replacing the classifier dataset".
- **Model v3** (frozen DINOv2 ViT-S/14 + logistic-regression head, group-aware validation): locked test set 34/37 correct (accuracy 0.919, balanced accuracy 0.890, macro-F1 0.878). This is an **internal split estimate**: test photos come from the same capture sessions as training photos. A leave-one-source-out check (train on three source folders, test on the fourth) scored 0.49–0.71, i.e. accuracy on a new photographer, day or background is likely much lower. Not field-validated.
- **APK 0.8.0** bundles model v3 (head `4ddbdc65…`, backbone unchanged) and still classifies fully offline; it talks to the hosted API at `https://geo-tree.onrender.com`.
- **Render + MongoDB Atlas.** The hosted API is the lightweight FastAPI service only (`render.yaml`, section 12): no AI, no model files, and **no photo storage** (`IMAGE_STORAGE=disabled`, automatic on Render): records sync to Atlas, photos stay on the phones. Local Docker keeps everything AI (import, features, training, export, tests).

## On-device leaf assessment (0.7.0)

Field testers can assess a tamarind leaf **with no internet connection**. Since 0.7.0 the phone runs the same model the server trained, and the server is not involved in producing a result:

```text
photo → decode + EXIF orientation → Pillow-exact resize 384 / center-crop 336 / ImageNet normalization
      → DINOv2 ViT-S/14 (ONNX Runtime on the phone) → 384-d feature → trained classifier head
      → class + confidence + model version → Room (leaf_assessments) → later WorkManager sync
```

- **The APK contains the complete model** in `assets/leaf_model/`: the frozen DINOv2 backbone exported to ONNX (87 MB), the trained head (`classifier_head.json`: class order, weights, model version) and `manifest.json` (preprocessing, compatibility key, SHA-256 checksums). The first assessment after a fresh install needs no download. The files are generated, not hand-written, and are gitignored (trained weights); the build refuses to package an APK without them (`verifyLeafModel`). See section 11, "On-device model".
- **Sign-in is still required once.** The existing authentication rules are unchanged: the first launch needs a connection to sign in. After that, leaf assessment, history, tree records, the map and navigation work offline; a session that expires offline pauses sync only.
- **Sync is unchanged.** On-device results are synced with the existing idempotent `POST /api/v1/leaf-assessments/sync` (same fields; the server stores them as recorded).
- **Model updates.** After a completed sync the phone compares the server's active model (`GET /api/v1/ai/status`) with its own and, if different, downloads only the head (`GET /api/v1/ai/model/head`, new in 0.7.0). A head is activated only if it is intact and was trained for the bundled backbone; otherwise the current head stays. Until the backend with that endpoint is deployed, phones keep the bundled model.

## Web administration milestone (0.6.0)

```text
Web Admin (React + TypeScript, served by geo-tree-api at /) ─┐
                                                             ├─ FastAPI /api/v1 ── MongoDB
Android field app (Kotlin/Compose, Room offline-first) ──────┘        └─ DINOv2 ViT-S/14 (in geo-tree-api)
```

- **Two clients, one backend.** The web administration lives in `web/` and is built into `backend/web_dist`; the API serves it from the same origin (no extra container, no CORS, strict CSP, self-hosted fonts). Android keeps every field workflow; since 0.8.1 classifier and user administration (Leaf Classifiers, training images, model status, Train / Rebuild) is web-only, and the app has no admin screens or Administration card.
- **Roles enforced by FastAPI:** `SUPER_ADMIN`, `ADMIN`, `FIELD_TESTER` (section 13). Accounts can be suspended; a password change or reset revokes older tokens.
- **Web modules:** dashboard (real data only), tree records (search/filter/sort/paginate, metadata edit, tags, registration photo replace/delete, archive/restore), leaf assessments (read-only review), images (registration / training / assessment evidence kept separate), tags, classifiers and training images, models & training (metrics, confusion matrix, version history, jobs), users, activity log.
- **Archive is a soft delete.** Phones hide archived trees after their next sync (Room v4); scans and photos are kept; restore brings the tree back.

## AI milestone scope (0.4.0)

```text
Tree Detail → Assess Leaf → photo (camera or picker) → local FastAPI → DINOv2 ViT-S/14 (frozen)
            → 384-d feature → trained classifier head → Healthy | Spotting | Mold | … + confidence + model version
Settings → Administration → Leaf Classifiers (ADMIN role) → classes, training images, model status, Train / Rebuild
```

> Since Android 0.8.1 the Administration entry is gone from the app; classifier classes, training images and model status are managed in the web admin.

- **One classifier, flexible labels.** Healthy, Spotting and Mold are classes of one tamarind-leaf classifier (seeded in development), not separate models. Admins add, rename and delete classes in the app.
- **Frozen backbone, small trained head.** DINOv2 is never fine-tuned. Each uploaded image gets its DINOv2 feature computed once and cached; dataset changes queue one coalesced rebuild of the small head from cached features.
- **Local testing only.** Leaf assessment runs on the local Docker backend. The hosted deployment is unchanged and has AI disabled.
- **Offline field work is unchanged.** Geotagging, Room, the offline map and field guidance never depend on the AI server. Leaf photos are saved on the phone first, so an offline attempt can be retried later.

Details, measured results and limitations: [section 11](#11-leaf-assessment-ai).

## Give 2 scope

```text
Splash → Login → Dashboard ─┬─ Dashboard | Map | Settings   (bottom navigation)
                            └─ Tag Tree (Dashboard button, Map FAB)
Map: current GPS → select tree → Navigate to Tree → red direct guidance line
     → distance · bearing · speed · ETA (continuous GPS) → "Tree reached" → Stop Navigation
```

- **Dashboard** (first authenticated screen): total/synced/pending/failed counts, GPS status and last known accuracy, device network, backend status (Connected / Offline / Checking), last successful sync, quick actions (Tag New Tree, Open Map, Sync Now), "N records waiting to sync", recent trees. All numbers come from Room; nothing waits on the API.
- **Map**: MapLibre field locator with Room markers, blue current-location dot and accuracy halo, compass, GPS/accuracy/sync/navigation chips, My Location, explicit **Follow Location**, Tag Tree FAB, selected-tree sheet (thumbnail, coordinates, distance, Navigate to Tree).
- **Field guidance** (not road routing): a red geodesic line from your position to the tree, updated from a continuous GPS stream.
- **Settings** (since 0.8.1, exactly three sections): **Synchronization** (sync status, server reachability, last successful sync, trees and leaf scans waiting, offline/failure explanation, Sync Now), **Map** (Open Map, map source, offline PSAU map package/coverage, field-guidance settings, rescan side-loaded maps) and **Account** (email, role, session validity, Sign In to Sync when expired, Sign Out, app version). The server address is not editable in Settings: release builds default to `https://geo-tree.onrender.com`; the login screen keeps its health-checked **Server: Change** for development.
- **Offline PSAU / Magalang field map** (follow-up): a 5.8 MB map of the deployment area is bundled in the APK and works with no internet (section 10).

Not in scope (any milestone so far): TensorFlow Lite / on-device AI, OpenCV, Roboflow, Qdrant or any vector database, DINOv2 fine-tuning, turn-by-turn or road routing, voice guidance, production hosting, push notifications.

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
| Leaf assessment AI (backend) | `backend/app/ai/` (DINOv2 extractor, classifier head, split, metrics, jobs), `routes/admin_classifiers.py`, `routes/ai.py` |
| Leaf assessment / admin (Android) | `feature/leafassessment/`, `feature/classifiers/`, `feature/admin/`, `data/remote/GeoTreeAiApi.kt` |
| AI developer scripts | `ai_batch_test.bat`, `backend/scripts/ai_batch_test.py`, `backend/scripts/ai_import_dataset.py` |
| Backend tests | `backend/tests/` (isolated in-memory DB and a fake DINOv2 extractor; never touches dev data) |

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
- AI data (classifier dataset images, cached DINOv2 features, model versions, DINOv2 weight cache) lives in the named volume `geo_tree_ai_data` at `/app/ai_data`.
- The first `docker compose up --build` also installs CPU PyTorch (the image is about 1.8 GB); the first start downloads the DINOv2 ViT-S/14 code and weights (~85 MB) into `geo_tree_ai_data`. Later starts load them from the volume in a few seconds.
- The health response of the local Docker backend also contains `ai=enabled`.

Stop without deleting data:

```powershell
docker compose stop
```

> Never use `docker compose down -v`: it deletes the MongoDB, upload and AI data volumes.

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

The password is stored as a bcrypt hash. The development admin has `role: ADMIN` (a dev admin seeded before roles existed gets the role once at startup; an existing role is never changed). Admin-only routes return `403 ADMIN_REQUIRED` for other users. Debug APKs built for the local backend prefill both values; a build pointed at a hosted server (`-Pgeotree.baseUrl=https://…`) prefills only the email. Release builds contain empty defaults, and the backend never seeds outside development.

## 3. Android build and install

```powershell
.\gradlew.bat :android:app:testDebugUnitTest
.\gradlew.bat :android:app:assembleDebug
adb devices
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.geotree.app/.MainActivity
```

APK location (the only place it is written): `android\app\build\outputs\apk\debug\app-debug.apk`. No APK is copied to the repository root. For a phone, copy that file to a folder of your choice outside the repository, or install it directly with `adb install -r`.

Android Studio: open the repository root (not `android/`). `local.properties` (`sdk.dir=...`) is created by Android Studio and is gitignored.

### Backend address

| Where the app runs | Base URL |
|---|---|
| Emulator (build default) | `http://10.0.2.2:8000` |
| Physical phone on the same Wi-Fi | `http://<laptop-LAN-IP>:8000` |

Change it in the app on the login screen (**Login → Server: Change**; Settings has no server editor since 0.8.1). The app calls `/api/v1/health`, confirms that the server is GEO Tree, and saves the URL only if that check passes. You can also build with a different default: `.\gradlew.bat :android:app:assembleDebug -Pgeotree.baseUrl=http://192.168.1.20:8000`. Since 0.8.1 the build default depends on the build type: **release** builds use `https://geo-tree.onrender.com` (a release with a local or non-HTTPS address fails the `verifyReleaseBaseUrl` check), **debug** builds use `http://10.0.2.2:8000`; `-Pgeotree.baseUrl` overrides both. A saved server on the retired VPS host is dropped at startup so the build default applies.

Physical phone: allow inbound TCP 8000 in Windows Firewall. Debug builds allow cleartext HTTP; release builds are HTTPS-only. A physical phone must never use `10.0.2.2` (that address only exists inside the emulator).

### Physical Field Test checklist (short)

Do this on a real Android phone. The emulator cannot test real walking speed, real GPS accuracy or a full docked keyboard (see Known limitations).

- [ ] Install `app-debug.apk` (from `android\app\build\outputs\apk\debug\`).
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
5. Install the APK: `adb install -r android\app\build\outputs\apk\debug\app-debug.apk` (or copy that file to the phone).
6. Open GEO Tree.
7. Open server settings: **Login → Server: Change** (sign out first if already signed in; Settings has no server editor since 0.8.1).
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

1. Install `app-debug.apk` (from `android\app\build\outputs\apk\debug\`).
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
cd backend; .\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt; .\.venv\Scripts\python.exe -m pytest -q; cd ..
.\gradlew.bat :android:app:testDebugUnitTest
cd web; npm ci; npm run typecheck; npm test; cd ..
```

- **Web administration milestone (0.6.0).** Backend (134 tests in total): every admin route returns 403 for Field Testers and legacy role-less users and 401 without a token; suspension (login 403, existing token 401); tokens revoked by a password change, including tokens issued before `iat` existed; per-email sign-in throttling; audit entries without secrets; Admin limited to Field Tester accounts; Super Admin role changes; no self-demotion/suspension; password reset revoking sessions; user filters without hashes; the Super Admin promotion script (identity and hash unchanged, refuses a second); tree list search/filter/sort/paging; metadata edits that never touch GPS and appear in the phone change feed; Tree Code conflicts; archive/restore (history kept, `updated_at` kept, phone sync keeps it archived); registration photo replace/delete; tag CRUD, web-only assignment, detach on delete; assessment review filters without changing history; dashboard figures; model version history and jobs; web serving without shadowing the API. Android (232): `SUPER_ADMIN` sees admin tools, the Dashboard admin card is role-aware, archived trees are hidden from counts/lists and restored trees reappear, and the Room 3→4 migration checked against the exported v4 schema. Web (7): API client error parsing, session expiry, query building, formatting.

- **AI milestone additions.** Backend (100 tests in total): admin authorization (401/403), class CRUD and name conflicts, upload validation (non-image, too small, oversize, HEIC → JPEG), exact-duplicate rejection, thumbnails without exposed paths, the embedding cache (each new image embedded once; training reuses cached features; a backbone/preprocessing change recomputes them), image delete removing its cached feature and queuing a rebuild, the coalesced debounced job queue, manual retrain, version activation, failed-training rollback (previous model stays active), backbone failure, interrupted-job recovery, class deletion invalidating the model, a new class appearing in the mapping, the prediction contract, `AI_MODEL_NOT_READY`, the head on synthetic features, the stratified duplicate-safe split, metrics against hand-computed values, and the batch-test/import scripts (read-only evaluation, report verification, safe cleanup, refusal of hosted servers). DINOv2 is never downloaded in tests: a fake extractor stands in. Leaf assessment history (14 backend tests): one and two scans per tree, unique ids, the first scan unchanged after the second, newest-first history, detail by id, idempotent re-sync, 409 instead of overwriting a recorded result, FAILED/UNCERTAIN kept, required result fields, invalid ids/values, photo stored once under `leaf_assessments/` and never in the training dataset, the change feed, auth. Android (228 tests in total): admin role visibility and refresh, classifier list/add/rename/delete and name checks, upload progress and partial failure, single and multi-image delete, model-status polling (only while the screen is visible), leaf assessment history (a scan is saved as PROCESSING before the AI request; a second scan creates a second record and leaves the first byte-for-byte unchanged; newest-first ordering; the current status derived from the newest result, not from a failed attempt; "assess again" creates a new record; interrupted scans become FAILED; sync updates touch only sync fields), the result/detail page states, sync through the real `SyncEngine` (several scans per tree, photos uploaded once, repeated sync without duplicates, network failure keeping history, a scan waiting for its tree, a rejected photo format, other devices' scans merged without touching local ones), the Room 2→3 migration executed on a real SQLite (test-only `sqlite-jdbc`) and compared with the exported v3 schema, and the local default server.

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
- `IMAGE_STORAGE=disabled` (automatic on Render): photo uploads are acknowledged without storing bytes and photos stay on the phones; see section 12.

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

## 11. Leaf assessment (AI)

### Model

| | |
|---|---|
| Backbone | **DINOv2 ViT-S/14**, official `torch.hub` entry `facebookresearch/dinov2` (Apache 2.0), **frozen** (never trained) |
| Feature | normalized CLS token, 384 values |
| Input | EXIF-upright RGB → bicubic resize of the short side to 384 → centre crop **336 × 336** → ImageNet normalization (DINOv2's evaluation transform scaled to 336) |
| Head | multinomial logistic regression (scikit-learn) on L2-normalized, centred features scaled by √384; `class_weight=balanced`; C chosen from {0.01, 0.1, 1, 10} by stratified cross-validation **inside the training split only** |
| Artifact | `models/model_v<N>.npz` (arrays and class mapping only; loaded with `allow_pickle=False`) + `model_v<N>.json`; MongoDB `ai_models` is the registry |
| Device | `DINOV2_DEVICE=auto`: CUDA if this PyTorch build sees a usable GPU, otherwise CPU. The Docker image ships **CPU-only PyTorch 2.14.1**, so the local backend runs on CPU (verified: "ready on cpu"). A CUDA error at load time falls back to CPU automatically. |

DINOv2 is a general visual feature model, not a disease classifier. All accuracy below comes from the GEO Tree tamarind photos and this head.

### Pipeline

```text
Admin uploads image(s) → validate by content (JPEG/PNG/WebP/HEIC; HEIC → JPEG) → reject exact duplicates (SHA-256, whole dataset)
→ store image + thumbnail → DINOv2 feature computed once → embeddings/<image-id>.npy → dataset version + 1
→ training job queued (or the queued one is reused: changes within AI_RETRAIN_DEBOUNCE_SECONDS are coalesced)
→ job: compute only missing/incompatible features → deterministic stratified train/validation split (identical images never split)
→ fit head → validate → write model_v<N> → activate (the previous version stays active until then)
```

- A dataset that is not ready (fewer than two classes, or a class below `AI_MIN_IMAGES_PER_CLASS`) queues nothing; the admin screen lists exactly what is missing.
- A failed job is recorded as `ERROR` with its reason; the previous model keeps answering. A job interrupted by a server restart is marked `ERROR` (not retried in a loop); use **Train / Rebuild Model**.
- Deleting an image deletes its cached feature. Deleting a class deletes its images and features and deactivates any model that used it. Renaming never invalidates a model (models map class ids).
- Changing the backbone or preprocessing (e.g. `DINOV2_IMAGE_SIZE`) changes the feature cache key: old features and models are treated as incompatible and features are recomputed on the next rebuild.
- The queue is an in-process worker thread with jobs persisted in MongoDB: fine for one local backend process, not a durable production queue.

### MongoDB and files

Collections: `classifier_classes` (id, name, slug, active, timestamps), `classifier_images` (id, class_id, original_filename, stored_path, mime_type, file_size, width, height, sha256, embedding_path, embedding_model, embedding_dimension, embedding_status, active, created_at), `ai_models` (version, backbone, embedding key, class mapping, train/validation counts, metrics incl. confusion matrix, head and split settings, artifact path, active), `ai_jobs` (QUEUED → TRAINING → COMPLETE / ERROR, trigger, coalesced changes, features computed/reused). Counters `ai_dataset_version` and `ai_model_version` live in `counters`. Images, features and models are files in the `geo_tree_ai_data` volume, never blobs in MongoDB.

### Configuration (`compose.yaml`, overridable in `.env`)

| Variable | Default | Meaning |
|---|---|---|
| `AI_ENABLED` | `true` in compose, `false` otherwise | The hosted deployment and plain `uvicorn` runs have no AI unless set |
| `DINOV2_MODEL` | `dinov2_vits14` | `dinov2_vitb14` only as an explicit experiment; ViT-L/g are refused |
| `DINOV2_DEVICE` | `auto` | `auto`, `cpu` or `cuda` (CPU fallback) |
| `DINOV2_IMAGE_SIZE` | `336` | multiple of 14 |
| `DINOV2_BATCH_SIZE` / `DINOV2_NUM_WORKERS` | `4` / `0` | feature extraction batching (DataLoader workers) |
| `AI_MIN_IMAGES_PER_CLASS` | `10` | below this a class blocks training |
| `AI_VALIDATION_FRACTION` | `0.2` | per class, at least one validation image |
| `AI_RETRAIN_DEBOUNCE_SECONDS` | `15` | coalescing window for automatic rebuilds |
| `AI_CONFIDENCE_THRESHOLD` | `0.6` | below this the app shows "Uncertain · closest: …" instead of a plain answer |

### Android

- *(Removed from Android in 0.8.1; the web admin's Classifiers and Models pages replace it.)* **Settings → Administration → Leaf Classifiers** (visible only to the ADMIN role): model status card (Untrained / Queued / Training / Ready / Error, active version, dataset images, last trained, validation accuracy, macro F1 and per-class precision/recall/F1), **Train / Rebuild Model**, readiness problems, and one card per class (name, image count, status). Each card has a single ⋮ menu: **Rename Classifier**, **Manage Images**, **Delete Classifier** (with confirmation). The screen polls the server only while it is visible and something is running.
- **Manage Images**: thumbnail grid with file name and upload time, **Add Images** (system photo picker, up to 50 at once, uploaded one by one with progress and per-file errors such as duplicates), tap to preview or delete one image, long-press to select several and delete them (with confirmation).
- **Tree Detail → Current Leaf Status**: AI status of the on-device model (ready / model not ready, e.g. damaged install), the newest scan that produced a class (condition, confidence, "Latest scan · date", model and version), **Assess Leaf**, and the number of assessments. This card is derived from history; nothing is stored on the tree.
- **Tree Detail → Assessment History**: every scan of this tree, newest first (condition and confidence or "Not assessed", date, AI status, sync status). Tapping a row opens its read-only detail. Empty state: "No assessments yet. Run a leaf scan to begin monitoring this tree's condition over time."
- **Assess Leaf → Run Assessment → result page**: Take Photo (CameraX) or Choose Photo → review → **Run Assessment**. The scan is written to Room (status PROCESSING, its own assessment UUID) *before* on-device inference starts, then the result page for that scan opens and follows it to Completed / Uncertain ("Confidence below threshold") / Failed ("Assessment unavailable" with the reason, e.g. an unreadable photo). It shows the photo, class, confidence, model and version, scan and completion times, AI status and sync status, with **Back to Tree** and **View History**. The capture screen is replaced, so Back returns to the tree. Inference runs in an application-lifetime scope, so leaving the page does not cancel it.
- **Retry / assess again** is offered on a finished scan whose photo is still on the phone. It creates a **new** scan from the same photo; the earlier record (including a failed offline attempt) stays in history unchanged.

### Leaf assessment history

- **One scan = one record.** Room table `leaf_assessments` (schema v3; explicit 1→2 and 2→3 migrations; v2 rows are kept, `COMPLETE`→`COMPLETED`/`UNCERTAIN`, an unfinished `PENDING`→`FAILED`/`INTERRUPTED`). `treeId` links to the tree and is never a key, so a tree has any number of scans. Photos stay in `filesDir/leaf_images/`; Room stores paths only.
- **AI status and sync status are separate**: `status` PENDING / PROCESSING / COMPLETED / UNCERTAIN / FAILED; `syncStatus` PENDING / SYNCING / SYNCED / FAILED (the trees' enum). "Completed · Sync pending" is a normal state.
- **Immutable history.** A finished scan's result is never changed again; only sync fields (`syncStatus`, `serverVersion`, `remoteImagePath`, `lastSyncError`) are updated. A scan whose request died with the app process is recorded as FAILED / INTERRUPTED at the next start, photo kept. A scan that never reached the model has no model name/version: none is invented.
- **Sync** runs inside the existing WorkManager `SyncEngine`, after trees (an assessment needs its tree on the server): push finished scans by assessment UUID (`POST /api/v1/leaf-assessments/sync`, then the photo once with `POST /api/v1/leaf-assessments/{id}/image`), then pull other devices' scans from `GET /api/v1/leaf-assessments/changes?after_version=<n>` with their own server-side cursor. Re-sending a recorded scan is a no-op ("unchanged"); a different result for the same id is rejected with 409 `ASSESSMENT_CONFLICT` and nothing is overwritten. A pull adds unknown scans and never changes a local scan's result or deletes anything. A finished scan requests a sync automatically.
- **MongoDB** collection `leaf_assessments`: one document per scan (`assessment_id`, `tree_id`, `status`, `class_id`, `class_name`, `confidence`, `model_name`, `model_display_name`, `model_version`, `error_code`, `error_message`, `image_path`, `created_at`, `completed_at`, `updated_at`, `server_version`, `created_by`). Indexes: unique `assessment_id`, `(tree_id, created_at)`, `server_version`. `tree_id` is not unique.
- **Assessment photos are not training data.** They are stored as `uploads/leaf_assessments/<assessment-id>.<ext>` (the `geo_tree_uploads` volume), never in `ai_data/classifier_images/`. Only Admin → Classifiers → Manage Images adds training images.
- These are data routes: they work on any backend (including one with `AI_ENABLED=false`). Since 0.7.0 new scans are classified on the phone, so they need no AI runtime on the server either.

### Loading a labeled training folder and testing

Training images normally come from the Android admin screens. To load a labeled folder from the laptop through the same admin API (same validation, duplicate check and feature cache):

```powershell
backend\.venv\Scripts\python.exe backend\scripts\ai_import_dataset.py <folder-with-class-subfolders> --holdout-fraction 0.25
```

`--holdout-fraction` first copies a deterministic share of each class into `ai_test_input\<class>\` as held-out test images; only the rest is uploaded. The import refuses a source inside `ai_test_input`.

Batch test (local backend only; refuses non-local servers; never changes the dataset):

```powershell
.\ai_batch_test.bat                  # ai_test_input\healthy|spotting|mold\* → report in ai_test_results\<timestamp>\
.\ai_batch_test.bat --cleanup-input  # also deletes ai_test_input\ after a fully successful, verified, saved run
```

The report (`report.md`, `report.json`, `predictions.csv`) has per-file predictions and confidence, accuracy, macro precision/recall/F1, per-class metrics and the confusion matrix. Folder names are matched to class names case-insensitively. Cleanup only ever deletes a folder named `ai_test_input`, never `ai_data`, Docker volumes, MongoDB data or model artifacts.

### Measured results (local Docker, CPU, 2026-10-08)

Dataset: 137 labeled photos supplied for this milestone (Healthy 34, Mold 63, Spotting 40; 67 HEIC, 70 JPEG). A deterministic 25 % per class was held out as test images (34) and never uploaded; 103 were uploaded as training data (Healthy 26, Mold 47, Spotting 30).

| | Images | Accuracy | Macro precision | Macro recall | Macro F1 |
|---|---|---|---|---|---|
| Internal validation (model v1 and v2) | 20 | 95.0 % | 96.7 % | 93.3 % | 94.5 % |
| Held-out batch test (`ai_batch_test`) | 34 | 91.2 % | 91.2 % | 91.2 % | 90.5 % |

Held-out confusion matrix (rows expected, columns predicted; Healthy/Mold/Spotting): Healthy 8/0/0, Mold 1/15/0, Spotting 1/1/8. Four images were below the 0.6 confidence threshold.

Read these numbers with care:
- They come from very small sets (20 and 34 images), so one image moves accuracy by 3–5 points.
- Many photos come from the same sessions (consecutive `IMG_72xx` files), so near-identical shots of the same leaf can sit in both training and test sets. Only exact duplicates are prevented, so these results are probably optimistic for new trees and lighting.
- **Dataset conflict:** `IMG_7259.HEIC` is the same file in both `Mold` and `Spotting`. Both copies landed in the test set (one counted wrong). Decide its correct label before adding it to training; the backend would reject the second copy anyway.

**Assessment history, verified on the Medium Phone emulator against local Docker (2026-10-08):** the installed 0.1.0 build (Room v1, 5 trees) was upgraded in place and Room migrated 1→2→3 with all trees kept. On GEO-TAM-012 (Map → tree → View Details): empty state shown; scan 1 → result page "Healthy, 90 %, DINOv2 ViT-S/14 · v2", Back to Tree → exactly 1 history item; scan 2 → 2 items, newest first, the first unchanged (same id and times). Two scans accidentally used a GEO Tree logo picture from the gallery instead of a leaf; the model returned Uncertain (Healthy 49 %) rather than a confident class. Scan 4 (spotting leaf) → "Spotting 98 %" as the current status, 4 items. With the backend stopped and the app force-stopped and relaunched, all history and the oldest scan's photo and details were shown from Room; a new scan failed clearly ("Assessment unavailable", photo kept, no model version). After restarting the backend, "Retry with this photo" created a new scan (Mold 61 %) and kept the failed one: 6 records, 6 unique ids in Room and MongoDB, every photo uploaded; two further syncs left 6 records. The classifier dataset stayed at 103 images.

Earlier in this milestone, Android was exercised on the Medium Phone emulator against this backend: classifier list and model status, add/rename/delete class, multi-image upload, single and multi-select delete, automatic rebuild to model v2, and leaf assessment of a held-out photo ("Spotting, 98 %, DINOv2 ViT-S/14 · v2"). With the backend stopped, Tree Detail showed "AI server offline" with the last result from Room, an assessment attempt failed clearly with the photo kept, a new tree was tagged and saved as Pending, and after restarting the backend the saved photo was assessed again and the tree synced.

### On-device model (Android 0.7.0+)

The phone runs the server's model; nothing is retrained or approximated.

| File (`android/app/src/main/assets/leaf_model/`, gitignored) | Content |
|---|---|
| `dinov2_vits14_336.onnx` | the frozen DINOv2 ViT-S/14 module the server loads from its torch.hub cache, exported to ONNX (opset 17) for a fixed 336 × 336 input; the interpolated position embedding is computed by PyTorch and stored as a constant |
| `classifier_head.json` | the trained head copied from the server artifact `models/model_v<N>.npz` (class order, weights, bias, mean, scale), the model version recorded on assessments and the artifact's SHA-256 |
| `manifest.json` | preprocessing (resize 384 Pillow bicubic, center crop 336, ImageNet mean/std), the compatibility key `dinov2_vits14\|336px\|resize384-bicubic-centercrop\|imagenet-norm\|cls\|v1`, the confidence threshold (0.6), file checksums and the export's own parity check |

Generate (needs the local AI backend image and its `geo_tree_ai_data` volume, mounted read-only; PowerShell):

```powershell
docker run --rm -e AI_DATA_DIR=/app/ai_data -e PYTHONPATH=/src -e HOME=/tmp/home `
  -v geo_tree_ai_data:/app/ai_data:ro -v "${PWD}\backend:/src:ro" -v "${PWD}\android\app\src\main\assets\leaf_model:/out" `
  geo_tree-backend:latest sh -c "pip install --user -q -r /src/scripts/requirements-export.txt && python /src/scripts/export_mobile_model.py --model-json /app/ai_data/models/model_v2.json --model-version 1 --out /out"
```

`--model-version` (default: the JSON's) is the version recorded on assessments, so bundle **the active artifact of the server the APK talks to**. The 0.7.0 APK bundles hosted model **v2** (active since 2026-10-11): `--model-json` and `--artifact` point to read-only copies of the production `models/model_v2.json` and `model_v2.npz` (SHA-256 `bfe5dca45a951544178a7c30346491f2820238f233e6105a94379b421fcd9f34`, recorded as `serverArtifactSha256` in the bundled head), with the backbone and verification images from the local volume (DINOv2 weights SHA-256 `b938bf1b…`, identical on both servers). The local Docker v2 artifact (`8c8e813d…`) trained from the same images differs from it only by about 1e-16 (floating-point rounding). The export refuses to write anything unless ONNX Runtime reproduces the PyTorch features on every dataset image (cosine ≥ 0.9999) with identical predicted classes, and running it twice produces byte-identical files.

On the phone (`core/ai`): `OnDeviceLeafClassifier` decodes the photo with `BitmapFactory` (stored pixel values, no colour-space conversion, like Pillow), applies the EXIF orientation like Pillow's `exif_transpose`, resizes with a bit-exact port of Pillow's bicubic resampler (`PillowResample`), normalizes in float32 like NumPy, runs the ONNX backbone with ONNX Runtime 1.22.0 and applies the head in float64. `LeafModelStore` copies the backbone once to `noBackupFilesDir/leaf_model` and verifies its SHA-256 (a damaged copy is replaced from the APK; a damaged APK asset is an explicit "Model not ready" error, never a guess). Downloaded heads are validated, written atomically and activated by a pointer swap; the previous head is kept as the last known good one, and the bundled head is the final fallback.

Checks:
- JVM unit tests (`core/ai`): preprocessing tensors equal the server's `preprocess` bit for bit on 19 generated cases including all 8 EXIF orientations (fixtures from `backend/scripts/make_android_ai_fixtures.py`, no user photos); head probabilities equal `LinearHead.predict_proba` within 1e-12; corrupt/incompatible model files are refused; a failed update keeps the active head.
- Device parity harness: `backend/scripts/mobile_parity_reference.py reference` runs photos through the server's upload validation, feature extractor and head; `android/app/src/androidTest/.../LeafModelParityTest` runs the phone pipeline on the same files (instructions in its header); `mobile_parity_reference.py compare` reports class agreement, identical-pixel and identical-tensor counts, feature cosine, probability differences and timings.

### On-device measurements (emulator, 2026-10-12)

`Medium_Phone` AVD (`sdk_gphone16k_x86_64`, Android 17, 16 KB pages), APK 0.7.0. First measured with the local head (`8c8e813d…`, labelled v1), then repeated with the final build bundling hosted v2 (`bfe5dca4…`, compared with server features scored by that exact artifact): same results. Inputs: all 137 photos of the labeled dataset after the server's own upload validation (HEIC converted to JPEG as the server stores them).

| Check | Result |
|---|---|
| Export: ONNX Runtime vs PyTorch on the 103 training images | min cosine 0.99999976, max probability difference 3.2e-6, 103/103 same class |
| Phone vs server: predicted class | **137/137** |
| Phone vs server: decoded pixels / input tensors | 137/137 identical / 137/137 bit-identical |
| Phone vs server: features | min cosine 0.999999999996; max probability difference 3.2e-6 |
| Held-out photos (34, never trained on) | phone 31/34 = server 31/34 against folder labels; includes both copies of the conflicting `IMG_7259` and photos with near-duplicates in training, so not a field-accuracy estimate |
| Time per photo (12 MP; median) | decode 320 ms, preprocess 314 ms, inference 244 ms; whole `classify()` 818 ms |
| ONNX session creation | 0.36–10 s (first use after app start) |
| Memory while loaded | native heap about 250–270 MB, Java heap about 115 MB |
| APK | 220 MB (debug, 4 ABIs) |

Field flow on the emulator (airplane mode, server unreachable): 3 scans of one tree → 3 COMPLETED records with distinct ids, results equal to the server's for the same photos; kept after force-stop and offline restart; synced automatically about 5 s after reconnecting (photos uploaded once); repeated syncs added no duplicates. A corrupted installed model file was detected and re-installed from the APK at the next start.

### Replacing the classifier dataset (done 2026-10-20 with `data-sets.zip`)

Only the classifier **training** set and what depends on it (stored images, thumbnails, cached DINOv2 features, model versions) is replaced; trees, accounts, leaf assessments and their photos are untouched. Procedure (PowerShell, local Docker running):

1. **Back up** outside the repository: `mongodump --db=geo_tree`, a tar of the `geo_tree_ai_data` volume and a copy of `android/app/src/main/assets/leaf_model/`; verify the dump by restoring it into a scratch database.
2. **Audit and stage** the ZIP outside the repository (hash, member list, path-traversal check, extraction), validate every image with the backend's own `validate_image`, compute DINOv2 features with the server extractor, and write a provenance manifest: source entry, part, original/canonical label, size, SHA-256, exclusion reason, same-leaf group, split.
3. **Group and split.** Same-label photos are grouped as one leaf when DINOv2 cosine ≥ 0.97, or when they are consecutive iPhone shots ≤ 10 s apart with cosine ≥ 0.93. About 20 % of the groups per class and source folder form the locked test set (seed 20261020); a group never straddles train and test.
4. **Replace** (rename keeps the class id; old images are removed through the service, new ones added through it; `split_group` and provenance are stored on each record; one manual training job is queued):
   ```powershell
   docker run --rm --network geo_tree_default -e MONGO_URI=mongodb://mongodb:27017 -e AI_DATA_DIR=/app/ai_data `
     -e AI_RETRAIN_DEBOUNCE_SECONDS=86400 -v geo_tree_ai_data:/app/ai_data -v "<staging>:/data:ro" -v "<manifests>:/manifests" `
     geo_tree-backend:latest python scripts/replace_classifier_dataset.py --manifest /manifests/dataset_manifest.csv `
     --root /data --rename "Mold=Powdery Mildew" --confirm --retrain
   ```
   Without `--confirm` it is a dry run. It refuses to run with unexpected classes, duplicate manifest rows or a training job in progress, and verifies afterwards that every record matches its manifest file, label, checksum and cached feature and that no old image or feature remains.
5. **Train.** The backend trains as usual; training now keeps each `split_group` on one side of its validation split and selects the regularization with group-aware cross-validation (images without a group fall back to their SHA-256, the previous behaviour).
6. **Evaluate** the exact artifact on the locked test features: `backend/scripts/evaluate_classifier_holdout.py --manifest … --features … --model-json model_v<N>.json --artifact model_v<N>.npz --baseline <old>.npz --loso --out report.json`.
7. **Export** for Android with `export_mobile_model.py --model-json /app/ai_data/models/model_v<N>.json` (section above) and run the device parity harness.

**Results for model v3** (frozen DINOv2 ViT-S/14, logistic-regression head, C = 1, seed 42; 144 training images: Healthy 27, Spotting 31, Powdery Mildew 86; service validation split 115/29, accuracy 0.931, macro-F1 0.922):

| Locked test set (37 images, 31 leaf groups) | Healthy | Spotting | Powdery Mildew |
|---|---|---|---|
| Expected Healthy (7) | 5 | 2 | 0 |
| Expected Spotting (8) | 0 | 8 | 0 |
| Expected Powdery Mildew (22) | 1 | 0 | 21 |
| Precision / recall / F1 | 0.833 / 0.714 / 0.769 | 0.800 / 1.000 / 0.889 | 1.000 / 0.955 / 0.977 |

Accuracy 0.919 (34/37), balanced accuracy 0.890, macro precision 0.878, macro-F1 0.878; 2 predictions fell below the 0.6 "uncertain" threshold (accuracy of the other 35: 0.971). By capture setup: hand-held over soil 18/20, leaf on paper 16/17.

- **Old model (v2) on the same 37 images:** 31/37, but it was trained on 25 of them (the old dataset came from the same photos). On the 12 test images it never saw: old 7/12, new 11/12.
- **Leave-one-source-out** (fit on three folders, test on the fourth; not used to choose the model): Annah 0.708 (balanced 0.562), Kyla 0.613 (0.679), part 1 0.487 (0.504), part 2 0.565 (Powdery Mildew recall 26/46). With `part 1` held out, the only paper-background training photos were Powdery Mildew and most paper-background Healthy/Spotting leaves were called Powdery Mildew: the model partly relies on background/session cues. The deployed model is trained on all sources (both backgrounds in every class), but its accuracy on a new photographer, day or background is likely far below 0.92.
- **Label quality.** `Mold` photos (hand-held, green leaves, pale undersides with small dark specks) were mapped to Powdery Mildew as instructed; the white powdery patches are clearly visible only in some `powdery mildew` (paper) photos. This mapping is not botanically verified. `IMG_7259` (identical file in Mold and Spotting) stays excluded until its true label is known; `Molds` and `Spot` were treated as spelling variants of Mold and Spotting.
- **Parity.** Evaluation features are bit-identical to the server's cached training features (144/144). ONNX vs PyTorch on all 144 training images: min cosine 0.99999976, same class 144/144. Phone vs server on all 184 ZIP photos (emulator, release model v3): same class 184/184, identical decoded pixels and input tensors 184/184, max probability difference 3.4e-6; median 0.69 s per 12 MP photo.

### AI on GPU (optional, not configured)

The default image uses CPU PyTorch on purpose: it works on every machine, and recent CUDA builds of PyTorch may not support the GTX 1050 Ti (Pascal) architecture. To experiment, install a CUDA build of PyTorch that supports your GPU in `requirements-ai-torch.txt`, add `gpus: all` to the backend service, and keep `DINOV2_DEVICE=auto`. GPU execution has not been tested in this repository.

## 12. Hosted API on Render + MongoDB Atlas (current)

```text
Android (offline-first, on-device AI) ──HTTPS──> Render web service: FastAPI (backend/requirements.txt only)
Web admin (optional, from local Docker)                │
                                                       └── MongoDB Atlas (structured records only)
Local Docker: classifier import, DINOv2 features, training, ONNX export, full tests
```

- **Service:** `https://geo-tree.onrender.com` (existing Render web service, created in the dashboard; on 2026-10-20 its health returned `database: ok`, `ai: disabled` after a 33 s cold start, and its OpenAPI already listed every route the app uses). `render.yaml` documents the intended settings: root directory `backend`, build `pip install -r requirements.txt`, start `uvicorn main:app --host 0.0.0.0 --port $PORT`, health check `/api/v1/health`. Keep the dashboard in line with it.
- **Environment (Render dashboard, never in Git):** `MONGO_URI` (Atlas SRV string) and `JWT_SECRET` as secrets; `ENVIRONMENT=production`, `MONGO_DATABASE=geo_tree`, `AI_ENABLED=false`, `IMAGE_STORAGE=disabled`. Startup only creates indexes idempotently; nothing is wiped or reseeded (the development admin is never seeded outside development).
- **No AI on Render.** PyTorch/DINOv2 are not installed; `/api/v1/ai/*` answer `503 AI_DISABLED`. Phones classify on-device; the model-update check simply finds no server model and keeps the bundled one.
- **No photos on Render.** The free filesystem is ephemeral, so `IMAGE_STORAGE` defaults to `disabled` whenever Render's `RENDER=true` is present. Tree and leaf-assessment photo uploads are then acknowledged without storing bytes (`200`, record unchanged, header `X-GeoTree-Image-Storage: disabled`): records sync to Atlas and each photo stays on the phone that took it (phones never delete photos after upload). Admin photo replacement answers `503 IMAGE_STORAGE_DISABLED`. Durable photo storage (e.g. an object store or a paid persistent disk) is a separate, not yet approved decision; set `IMAGE_STORAGE=local` only on a host with a persistent disk. Photos uploaded to Render before 0.8.0 were written to its ephemeral disk and are not recoverable there.
- **Deploys:** pushing to `main` redeploys the service if auto-deploy is enabled in the dashboard. Data lives in Atlas and survives deploys and restarts.
- **Free-tier limits** (re-check before relying on them: <https://render.com/docs/free>, <https://www.mongodb.com/docs/atlas/reference/free-shared-limitations/>): the service sleeps after idle time and the first request then takes tens of seconds (the app keeps working offline and retries sync); local files do not survive restarts; Atlas free clusters have limited storage and throughput.
- **Smoke test (2026-10-20, local, Render mode):** the API ran from a virtualenv with only `requirements.txt` (no NumPy/Pillow/PyTorch), `RENDER=true`, `ENVIRONMENT=production`, against a throwaway database: health (`ai: disabled`, `imageStorage: disabled`), sign-in, wrong-password and missing-token refusals, tree sync, photo uploads acknowledged without bytes, assessment sync plus idempotent resend, change feeds, AI routes `503`; after a restart all records were still there exactly once. The throwaway database was then dropped.
- **Android:** client APKs are built with `-Pgeotree.baseUrl=https://geo-tree.onrender.com` (release builds are HTTPS-only). Development builds keep `http://10.0.2.2:8000` (emulator), `http://localhost:8000` (host) and the in-app server setting for a phone on the LAN.

### Mobile Settings operations

| | Local Docker (`compose.yaml`) | Render (`render.yaml`) |
|---|---|---|
| Release checks | `RELEASE_SIGNER_SHA256` = established certificate `071b9904…3321` | not set (no APK served) |
| Served APK (`DISTRIBUTION_DIR`) | not mounted by default | not set: `/download` answers 404 |
| Download URL / QR (`DOWNLOAD_PUBLIC_URL`) | not set | not set until a verified download route exists |
| Dataset archives (`DATASET_ARCHIVE_DIR`) | named volume `geo_tree_dataset_archives` | disabled: ignored while `RENDER=true` (ephemeral disk) unless `DATASET_ARCHIVE_PERSISTENT=true` marks a real persistent disk |

- **Register a release** (once a download location serves the APK): Web admin → Mobile Settings → App release → *Register served APK* (Super Admin). Every value is read from the served file; registration is refused if the package, release (non-debuggable) build, signing certificate or bundled-model checksums are wrong, or if versionCode does not increase. The first record may be a **baseline** (an APK already served before tracking began; its original date stays unknown). Restoring an earlier recorded APK and registering it again is recorded as a rollback.
- **Record the source archive of the active dataset** (local Docker): `docker compose exec backend python scripts/register_dataset_archive.py --archive <path inside the container> --expect-sha256 82775506a025599e74ed5717ee09786f06446d90158d4c6e77ff36c3365239b7 --manifest <provenance manifest> --active --activated-at <when it was activated> [--confirm]` (dry run without `--confirm`; it validates exactly like a web upload, copies the archive into the archive store and verifies the copy's hash).
- **Evaluations shown with the model:** `python scripts/record_model_evaluation.py --report evaluation_v3.json --head-sha256 <bundled head sha> --limitations "<honest limits>" [--confirm]` records the locked-test and cross-source results from the report file (its SHA-256 is stored).
- **Web archive uploads** only validate and stage (path-traversal, symlink, encrypted-entry, size and compression-ratio guards; content signatures, and full decoding where Pillow is installed; label aliases with `Mold`/`Molds` → Powdery Mildew; duplicates and cross-label conflicts excluded for review; CSV manifest). They never change the training dataset or start training; activation stays the controlled `replace_classifier_dataset.py` procedure in section 11.

## 12b. Historical: Hostinger VPS deployment (expired; not used)

The notes below describe the earlier deployment next to the museum app. The VPS term ended on 2026-10-19 and it was unreachable on 2026-10-24; it is not a target. GEO Tree data from it was recovered to `C:\GEO_TREE_RECOVERY\2026-10-20`. The printed QR code encodes `https://geotree.museuma7k9x3.tech/download` (decoded 2026-10-24); it works again only if that hostname is pointed at a verified download route.

GEO Tree runs on the same Hostinger VPS as the museum app, as a **separate Compose project**. The museum stack is protected production: GEO Tree never uses its directories, volumes, networks, databases or ports.

```text
Internet ─ museum Caddy (ports 80/443, museum stack) ─┬─ api.museuma7k9x3.tech      → museum backend (unchanged)
                                                      └─ geotree.museuma7k9x3.tech  → geo-tree-api:8000 (via geo-tree-network)
geo-tree-api ─ geo-tree-internal (internal: true) ─ geo-tree-mongodb
```

| | |
|---|---|
| Directory | `/opt/geotree` (`compose.yaml`, `.env` with `GEO_TREE_JWT_SECRET`, `admin-credentials`, `source/`, `distribution/`, `backups/`); never under `/opt/museum-app` |
| Compose project | `geo_tree_prod` (always `docker compose -p geo_tree_prod …` from `/opt/geotree`) |
| Containers | `geo-tree-api` (FastAPI + DINOv2 in one process, 1.5 GB / 1 CPU cap, OOM score 500), `geo-tree-mongodb` (640 MB cap, WiredTiger cache 0.25 GB, OOM score 300) |
| Networks | `geo-tree-network` (API + the museum Caddy), `geo-tree-internal` (API + MongoDB, no internet) |
| Volumes | `geo_tree_uploads` (`tree_images/`, `leaf_assessments/`), `geo_tree_ai_data` (`classifier_images/`, `embeddings/`, `models/`, `model_cache/`), `geo_tree_mongodb_data` |
| Host ports | none (only the museum Caddy listens on 80/443) |
| Source files in the repo | `deploy/hostinger/compose.yaml`, `deploy/hostinger/Caddyfile.geotree` |

**Shared-server safety.** The museum backend uses about 3.3 GB at rest and its 3D generation has reached about 7.7 GB of the 8 GB. GEO Tree is capped, is the kernel's preferred OOM victim, keeps PyTorch to one thread, loads DINOv2 only on first use (`AI_PRELOAD_BACKBONE=false`), and runs a memory guard (`backend/app/ai/resources.py`) with two independent checks: host free memory (protects the museum) and headroom below GEO Tree's own container limit (`AI_CONTAINER_MIN_HEADROOM_MB=700`, protects GEO Tree; it must stay below the 1536 MB limit or the work could never run). Below 1500 MB free on the host, feature extraction and training wait (jobs stay QUEUED, the previous model stays active, the admin status shows why); below 500 MB free, a leaf assessment answers `503 AI_BUSY` (the phone keeps a failed attempt it can retry). Training is one job at a time (in-process queue) and only refits the small head from cached features.

**Production accounts (physical-device testing).** `ENVIRONMENT=production`: `admin123` is never seeded and is rejected. The hosted server has these accounts (one user per email). **Passwords are never written in this repository**: they live only on the server in `/opt/geotree/admin-credentials` (mode 600; read it with `ssh root@<vps> cat /opt/geotree/admin-credentials`). They were rotated on 2026-10-11 after an earlier README revision exposed them.

| Account | Email | Role | Can use |
|---|---|---|---|
| Admin | `admin@gmail.com` | `ADMIN` | everything; classifier management, model status and Train / Rebuild in the web admin (Android 0.8.1 has no admin screens) |
| User | `user@gmail.com` | `FIELD_TESTER` | field work only: sign in, trees, photos, sync, leaf assessment, assessment history (admin screens are hidden; admin API answers `403 ADMIN_REQUIRED`) |

To change a password, use the backend's own hashing from the server (`docker exec -i geo-tree-api python …` with `app.auth.security.hash_password`, also setting `password_changed_at`), never by writing a plain value into MongoDB, and never paste the value into the README, source code or the APK. A temporary account `geotree-test@gmail.com` (`FIELD_TESTER`) also exists until cleanup is requested. Classifier classes are not auto-seeded in production; create them in Admin → Classifiers.

**Physical-device test on the hosted server.**
1. On the phone, open `https://geotree.museuma7k9x3.tech/download` (or scan `tools/distribution/release/geotree_download_qr.png`) and install `GEO_Tree.apk`; compare its SHA-256 with the one on the page if needed. The production APK's default server is `https://geotree.museuma7k9x3.tech`.
2. Sign in as **User** (`user@gmail.com`; password from the server credentials file, never prefilled in hosted builds). Check: Settings has no Administration section; tag a tree outdoors with a fresh GPS fix and a photo; it syncs (Dashboard → Synced); open the tree → Assess Leaf. Until a production model is trained the result is "Assessment unavailable · model not ready", recorded as a failed attempt in the tree's Assessment History.
3. Sign out (Settings) and sign in as **Admin** (`admin@gmail.com`; password from the server credentials file). Check: the trees tagged as User are visible after sync (Android 0.8.1 shows the same field screens for every role; model status is in the web admin).
4. Offline check: turn on airplane mode (Location on), tag another tree, confirm it saves as Pending and the history stays visible; turn networking back on and confirm it syncs.

**Caddy.** The only museum-side changes: the GEO Tree site block appended to `/opt/museum-app/deploy/Caddyfile` (the `api.museuma7k9x3.tech` block is byte-identical; a backup is in `/opt/geotree/backups/`), applied with `caddy reload` (no restart), and `docker network connect geo-tree-network museum-guide-caddy-prod`. The Caddyfile is a single-file bind mount, so it must be edited **in place** (same inode; `sed -i` would break it). If the museum stack ever recreates its Caddy container, re-run the network connect, or GEO Tree answers 502:

```bash
docker network connect geo-tree-network museum-guide-caddy-prod
```

**DNS.** `A  geotree  72.61.120.104` (Hostinger hPanel, added by the owner on 2026-10-08; verified on Google, Cloudflare, Quad9, OpenDNS and both authoritative nameservers). Caddy obtained the Let's Encrypt certificate automatically and renews it.

**Database.** Production uses the Docker MongoDB (`MONGO_URI=mongodb://geo-tree-mongodb:27017`, database `geo_tree`), never Atlas. On 2026-10-08 the Atlas `geo_tree` data (3 trees and the tree version counter) was imported with `mongodump`/`mongorestore` and verified byte-identical; the Atlas `users` document was not imported because the production admin (same email, generated password) already existed. Backups: `/opt/geotree/backups/atlas-geo_tree-*.archive.gz` and `docker-geo_tree-before-atlas-import-*.archive.gz` (mode 600). Atlas itself was left untouched as the migration source.

**Caddy gateway caveat.** Attaching Caddy to `geo-tree-network` made that network Caddy's default gateway (Docker picks it by name order), so the museum's 80/443 forwards now enter Caddy through it. Everything works, but to restore the museum network as Caddy's gateway (requires owner approval: each step briefly re-creates the museum's port forwards): `docker network disconnect geo-tree-network museum-guide-caddy-prod && docker network connect --gw-priority -1 geo-tree-network museum-guide-caddy-prod`. Use the same `--gw-priority -1` form whenever the attachment has to be redone.

**Update GEO Tree** (from the repository root on the laptop):

```powershell
# 1. build the web administration, then package the backend + deploy files + the web build
#    (git-tracked and new files, never .env/ai_data/uploads; backend/web_dist is gitignored, so add it explicitly)
cd web; npm ci; npm run build; cd ..
git ls-files -co --exclude-standard backend deploy | Select-String -NotMatch '^backend/tests/' | % { $_.Line } > $env:TEMP\files.txt
git ls-files -o backend/web_dist >> $env:TEMP\files.txt
tar --force-local -czf $env:TEMP\geotree-source.tgz -T $env:TEMP\files.txt
scp -i ~/.ssh/id_ed25519_hostinger_vps $env:TEMP\geotree-source.tgz root@<vps>:/opt/geotree/source/
# 2. on the server
cd /opt/geotree/source && tar -xzf geotree-source.tgz && rm geotree-source.tgz && cp deploy/hostinger/compose.yaml /opt/geotree/compose.yaml
cd /opt/geotree && docker compose -p geo_tree_prod up -d --build geo-tree-api
```

**Publish a new APK** (the URL and the printed QR never change):

```powershell
# Distribution build (0.7.0+): non-debuggable release signed with the SAME certificate as earlier releases
# (SHA-256 071b9904…, this machine's Android debug keystore), so installed apps upgrade in place. The keystore
# passwords live outside the repository in %USERPROFILE%\GeoTreeSigning (keystore backup in its backup\ folder).
.\gradlew.bat :android:app:assembleRelease -Pgeotree.baseUrl=https://geotree.museuma7k9x3.tech -Pgeotree.signingProperties=$env:USERPROFILE/GeoTreeSigning/geotree-distribution-signing.properties
tools\distribution\.venv\Scripts\python.exe tools\distribution\prepare_release.py --apk android\app\build\outputs\apk\release\app-release.apk
# on the server first: back up the live files
#   B=/opt/geotree/backups/distribution-<old version>-$(date -u +%Y%m%d-%H%M%S); mkdir -p $B
#   cp -p /opt/geotree/distribution/{GEO_Tree.apk,index.html,SHA256SUMS} $B/ && (cd $B && sha256sum -c SHA256SUMS)
scp -i ~/.ssh/id_ed25519_hostinger_vps tools\distribution\release\GEO_Tree.apk root@<vps>:/opt/geotree/distribution/.GEO_Tree.apk.upload
scp -i ~/.ssh/id_ed25519_hostinger_vps tools\distribution\release\index.html  root@<vps>:/opt/geotree/distribution/.index.html.upload
scp -i ~/.ssh/id_ed25519_hostinger_vps tools\distribution\release\SHA256SUMS  root@<vps>:/opt/geotree/distribution/.SHA256SUMS.upload
# on the server (hidden names are never served): check sha256sum of .GEO_Tree.apk.upload equals the local SHA-256, then
#   cd /opt/geotree/distribution && mv -f .GEO_Tree.apk.upload GEO_Tree.apk && mv -f .index.html.upload index.html && mv -f .SHA256SUMS.upload SHA256SUMS && sha256sum -c SHA256SUMS
# from outside: download https://geotree.museuma7k9x3.tech/download/GEO_Tree.apk; compare SHA-256, aapt versionCode, apksigner certificate
# rollback: move the files from $B back the same way (phones already on the newer version cannot downgrade without uninstalling)
```

Published 2026-10-12 14:06 UTC: **0.7.0** (versionCode 10), 215,375,455 bytes, SHA-256 `0d31a154672ef521a8e57705c0f74c051820c5221218b4bb4b5182353b0216c1`; previous 0.6.0 files in `/opt/geotree/backups/distribution-0.6.0-20261012-140258` (also `C:\Users\Admin\GeoTreeReleases\0.6.0`).

The page `https://geotree.museuma7k9x3.tech/download` and the APK `…/download/GEO_Tree.apk` are served by `geo-tree-api` from `/opt/geotree/distribution` (read-only mount), separate from the museum's distribution folder. The QR (`tools/distribution/release/geotree_download_qr.png`, generate once) encodes exactly `https://geotree.museuma7k9x3.tech/download`. Install the QR tooling once with `python -m venv tools\distribution\.venv; tools\distribution\.venv\Scripts\python.exe -m pip install segno`.

**Rollback (GEO Tree only; never touches museum containers, volumes or the museum Compose project).**

```bash
cd /opt/geotree && docker compose -p geo_tree_prod stop                 # pause GEO Tree, keep data
# remove the route: restore the museum Caddyfile IN PLACE from the backup, then reload
cat /opt/geotree/backups/museum-Caddyfile.before-geotree-<timestamp> > /opt/museum-app/deploy/Caddyfile
docker exec museum-guide-caddy-prod caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
docker network disconnect geo-tree-network museum-guide-caddy-prod
# full removal (deletes GEO Tree containers and networks; add -v ONLY to also delete GEO Tree data):
docker compose -p geo_tree_prod down
```

Never run `docker system prune`, `docker volume prune`, or any command against `museum_app_prod`.

## 13. Web administration and roles

**Run it locally.** `run.bat` builds `web/` (needs Node.js 20+; without Node the API still starts) and the API serves it at `http://localhost:8000/`. For live UI development: `cd web; npm ci; npm run dev` (Vite on `http://localhost:5173`, proxying `/api`, `/uploads` and `/download` to the local backend). Sign in with an Admin or Super Admin account; Field Tester accounts are told to use the Android app.

```powershell
cd web; npm ci; npm run typecheck; npm test; npm run build; cd ..   # build output: backend\web_dist (gitignored)
```

| Role | Web administration | Android app |
|---|---|---|
| `SUPER_ADMIN` | everything, including creating/editing/suspending Admins and assigning any role | field work + admin screens |
| `ADMIN` | trees, tags, images, classifiers/training images, model training, assessments, analytics, activity; manages **Field Tester** accounts only | field work + admin screens |
| `FIELD_TESTER` | no access (every `/api/v1/admin/*` route answers 403) | field work only |

A user stored before roles existed is treated as a Field Tester. Nobody can change their own role or suspend themselves, and the last active Super Admin can never be demoted or suspended. The role and status are read from MongoDB on every request (a change applies at once); tokens carry `iat`, and a token issued before the account's `password_changed_at` is rejected.

**First Super Admin (no hardcoded password).** Promote an existing account; its ID, password hash and history are unchanged:

```bash
docker exec geo-tree-api python scripts/promote_super_admin.py --email admin@gmail.com            # dry run
docker exec geo-tree-api python scripts/promote_super_admin.py --email admin@gmail.com --confirm
```

**Passwords.** There is no email reset system: an administrator sets a new password in Users → Reset password (a random one is generated in the browser) and passes it on privately; the user's sessions end immediately. Passwords are never stored in the repository, the APK or the README. Ten failed sign-ins for one email within 15 minutes lock that email for the rest of the window (`LOGIN_MAX_FAILURES`, `LOGIN_WINDOW_SECONDS`).

**Maps.** Tree Records → **View Map** (`/trees/map`, also *Field Map* in the sidebar) and Leaf Assessments or Images → **View Map** (`/field-evidence/map`) render interactive Leaflet maps inside the admin, with OpenStreetMap raster tiles (`tile.openstreetmap.org`, attribution on every map; the only third-party origin the CSP allows, images only; within the OSMF tile usage policy for light interactive use, no bulk downloading). Markers come only from records with valid coordinates (finite, in range, not 0,0); others are counted and listed, never placed. Field evidence (registration photos and leaf scans) has no GPS of its own and is shown at its tree's recorded position, one marker per tree. The tree detail page embeds a small map instead of linking out. Data: `GET /api/v1/admin/map/trees` and `GET /api/v1/admin/map/evidence` (marker fields only, a fixed number of queries per request).

**Data rules the web follows.** GPS coordinates, accuracy and capture time are field evidence and are never edited. A metadata edit is a normal synced change (new `updated_at` + `server_version`, so phones pull it; newer `updated_at` wins). Tags are web-only (`tags` collection, `tag_ids` on trees; phone sync never removes them). Archive sets `archived` and a new `server_version` but keeps `updated_at`, so a pending phone edit is not discarded. Leaf assessments are read-only from the web; a later model never changes a recorded prediction. Significant actions go to the `audit_log` collection (no passwords, hashes or tokens). Dashboard "data completeness" = registration photo + GPS accuracy ≤ 10 m + at least one of age, taste category, yearly yield, fruit quality.

## API

```text
GET  /api/v1/health                     (also reports "ai": "enabled" | "disabled" and "imageStorage": "local" | "disabled")
POST /api/v1/auth/login                 (user includes "role")
GET  /api/v1/auth/me
POST /api/v1/trees/sync
GET  /api/v1/trees/changes?after_version=<n>&limit=<1..500>
GET  /api/v1/trees/{id}
POST /api/v1/trees/{id}/image           (multipart field: file)  (IMAGE_STORAGE=disabled: 200 without storing, header X-GeoTree-Image-Storage: disabled)
GET  /uploads/tree_images/{filename}

# Leaf assessment (any signed-in user; 503 AI_DISABLED where AI is off). Android 0.7.0+ classifies on the phone and
# uses only status + model/head; the server-side assessment route stays for older APKs and tools.
POST /api/v1/ai/leaf-assessment         (multipart: file, optional treeId) → classId, className, confidence, uncertain, model, modelVersion, probabilities
GET  /api/v1/ai/status                  → ready, model, modelVersion, classes
GET  /api/v1/ai/model/head              → the active classifier head in the on-device format (409 AI_MODEL_NOT_READY)
# Leaf assessment history (any signed-in user; snake_case and ISO-8601 like tree sync)
POST /api/v1/leaf-assessments/sync                 (one finished scan; idempotent by assessment_id)
POST /api/v1/leaf-assessments/{assessmentId}/image (multipart field: file; stored once)
GET  /api/v1/leaf-assessments/{assessmentId}
GET  /api/v1/leaf-assessments/changes?after_version=<n>&limit=<1..500>
GET  /api/v1/trees/{treeId}/leaf-assessments       (newest first)
GET  /uploads/leaf_assessments/{filename}
GET  /download                                     (public APK page, when DISTRIBUTION_DIR is set)
GET  /download/GEO_Tree.apk
GET  /api/v1/ai/status
# Web administration (role ADMIN or SUPER_ADMIN; camelCase responses; paginated lists take page & pageSize)
GET    /api/v1/admin/dashboard
GET    /api/v1/admin/activity?action=&actor=
GET    /api/v1/admin/users?q=&role=&status=        POST /api/v1/admin/users
PATCH  /api/v1/admin/users/{id}                    {"displayName"?, "role"?, "active"?}
POST   /api/v1/admin/users/{id}/password           {"password"}  (revokes the user's older tokens)
GET    /api/v1/admin/trees?q=&status=active|archived|all&hasImage=&tagId=&sort=
GET    /api/v1/admin/trees/{id}                    PATCH (treeCode, age, tasteCategory, yearlyYield, fruitQuality, notes, tagIds)
POST   /api/v1/admin/trees/{id}/archive            POST /api/v1/admin/trees/{id}/restore
POST   /api/v1/admin/trees/{id}/image              DELETE /api/v1/admin/trees/{id}/image
GET    /api/v1/admin/tags                          POST, PATCH /{id}, DELETE /{id} (detaches from trees)
GET    /api/v1/admin/assessments?q=&treeId=&status=&className=&from=&to=&hasImage=
GET    /api/v1/admin/assessments/facets            GET /api/v1/admin/assessments/{id}
GET    /api/v1/admin/ai/models                     GET /api/v1/admin/ai/jobs
GET    /api/v1/admin/map/trees?status=&tagId=&q=   GET /api/v1/admin/map/evidence?type=all|registration|assessment&status=&from=&to=&treeId=&q=&includeArchived=
GET    /api/v1/admin/mobile/overview               served APK facts, release checks, current record, health, download URL + QR
GET    /api/v1/admin/mobile/releases?page=&pageSize=   POST (Super Admin) {"notes", "datasetArchiveId"?, "baseline"?}
GET    /api/v1/admin/mobile/model                  GET /api/v1/admin/mobile/dataset
GET    /api/v1/admin/mobile/dataset/archives       POST (multipart field: file, a ZIP; validated in the background)
GET    /api/v1/admin/mobile/dataset/archives/{id}  GET …/{id}/manifest.csv   DELETE …/{id} (discard a staged archive)
# Classifier administration (role ADMIN or SUPER_ADMIN; also used by the Android admin screens)
GET    /api/v1/admin/classifiers
POST   /api/v1/admin/classifiers                          {"name": "..."}
PATCH  /api/v1/admin/classifiers/{id}                     {"name": "..."}
DELETE /api/v1/admin/classifiers/{id}
GET    /api/v1/admin/classifiers/{id}/images
POST   /api/v1/admin/classifiers/{id}/images              (multipart field: files, 1-50)
DELETE /api/v1/admin/classifiers/{id}/images/{imageId}
GET    /api/v1/admin/classifiers/{id}/images/{imageId}/thumbnail   (and /file)
GET    /api/v1/admin/ai/model
POST   /api/v1/admin/ai/retrain
GET    /api/v1/ai/jobs/{jobId}
```

AI/admin responses use camelCase keys (the leaf-assessment contract); the older routes keep snake_case. No route returns server file paths, hashes or artifact locations.

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
- Roles: Super Admin, Admin, Field Tester (section 13). JWTs last 7 days; a password change revokes older tokens. Sign-in throttling is in memory (cleared by an API restart). Trees archived on the web stay visible in APKs older than 0.6.0. After expiry, sync pauses ("Sign in to sync") while local work continues.
- Editing after save is limited to changing the Tree Code (to resolve conflicts). There is no delete in Give 1.
- **On-device model limits.** The APK carries one backbone (DINOv2 ViT-S/14 at 336 px); only the small classifier head can be updated over the network. A new class created on the web reaches phones only after the model is retrained and the phone syncs online with a backend that has `GET /api/v1/ai/model/head` (0.7.0+); until then phones keep predicting with their current classes. Changing the backbone or preprocessing needs a new APK. Inference takes about a second on the emulator (measured below); slower phones will take longer, and the ONNX session uses roughly 250 MB of native memory while loaded.
- **Decoding differences.** The phone decodes photos with Android's decoder and the server with Pillow. On the emulator, all 137 JPEG/PNG photos decoded to identical pixels (section 11). A HEIC photo picked straight from the gallery (e.g. an iPhone transfer) is decoded by Android's HEIF decoder while the server converts HEIC with libheif; that path was not measured and may differ slightly. HEIC needs Android 9+.
- **Not yet measured on a physical phone.** Parity, timing and memory figures come from the x86_64 emulator; a mid-range ARM phone may be slower. The first assessment after the app starts also creates the ONNX session (about 0.4–10 s on the emulator).
- A scan of a tree that has not reached the server yet waits (sync FAILED, "Waiting for its tree to sync first.") and is retried on the next sync. A photo format the server cannot store (e.g. a HEIC picked from the gallery) stays on the phone; the result still syncs.
- Scans pulled from another device show their photo only while the server is reachable (there is no offline copy of other devices' photos). Assessments cannot be deleted or edited in the app.
- Back-stack behaviour (result → Tree Detail, history → detail → Tree Detail) was verified on the emulator; it has no automated UI test (the unit tests run on the JVM without an Android navigation host).
- **Model v3 is not field-validated.** Its 0.92 locked-test accuracy comes from photos of the same capture sessions as its training photos; leave-one-source-out scored 0.49–0.71 and showed reliance on background/session cues (section 11). The `Mold` → Powdery Mildew mapping is user-authorized, not botanically verified. Collect new sessions (other days, trees, photographers, both backgrounds per class) before trusting it in the field.
- **The Render API stores no photos** (ephemeral disk): photos stay on the phone that took them, and the web admin shows no photo for records synced through Render.
- **AI accuracy was measured on a small dataset** (section 11) and is not a general claim. The admin photo picker reports Android's media names (e.g. `241.png`) as file names, not the original camera names.
- The debug APK is large: about 220 MB in 0.7.0 (on-device model 81 MB compressed, ONNX Runtime native libraries about 75 MB and MapLibre about 42 MB for four ABIs, offline map 5.4 MB). Restricting ABIs to arm64-v8a/armeabi-v7a, ABI splits or an App Bundle would roughly halve the download.

## Next milestone

Collect new labeled photo sessions (different days, trees and photographers, each class on both backgrounds), keep one whole session as an independent test, resolve the `IMG_7259` label conflict, and run the offline assessment test on a physical phone. Decide on durable photo storage for the hosted API. Field-test the offline map, GPS accuracy and guidance at PSAU.
