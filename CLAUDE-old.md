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
6. DINOv2-based tamarind leaf condition assessment
7. Admin classifier/class-management and dataset CRUD
8. Local Docker AI testing and model evaluation
9. Physical-device testing and APK delivery

---

## Stable Baseline / Do Not Redesign

The following working subsystems are now considered the stable baseline:

- Kotlin + Jetpack Compose application architecture
- Room local-first field data architecture
- WorkManager synchronization
- FastAPI → MongoDB backend gateway
- Existing GPS/geotagging behavior
- Existing MapLibre/offline map behavior
- Existing field navigation
- Admin classifier CRUD and dataset image management
- DINOv2 ViT-S/14 frozen-backbone classifier
- Cached DINOv2 feature pipeline
- Lightweight classifier-head training and model versioning
- Local Docker AI execution
- Android → local FastAPI AI testing
- Render configuration with AI disabled unless explicitly enabled

Do not replace or redesign these subsystems merely to make the code look cleaner, to introduce another AI model, or because another framework looks newer. The current development priority is reliability, dataset quality, evaluation, and historical leaf-assessment tracking.

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
21. Historical leaf assessments must never be silently overwritten by later scans.
22. One completed or attempted scan represents one assessment event with its own unique ID.
23. The latest leaf condition is a derived summary; it is not the assessment history itself.
24. Assessment images are separate from the classifier training dataset and must never be added to training automatically.

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
- leaf-assessment section with explicit AI model status, latest result, assessment count, and Assess Leaf action
- current/latest leaf condition summary
- Assessment History section showing previous scans newest first
- tapping a history item opens its read-only assessment details

The latest condition must be visually separate from the historical list so the user understands that a new scan does not erase previous scans.

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
    leafassessment/
    admin/
    classifiers/
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
- `classifier_classes`
- `classifier_images`
- `ai_models`
- `ai_jobs`
- `leaf_assessments`

The AI subsystem is part of this milestone. Do not add Qdrant. Do not add a separate vector database for DINOv2. DINOv2 feature vectors are persisted as application-managed arrays/files only when needed for cached training features.

---

## Development Authentication

Seed only in development and only if missing:

```text
admin@gmail.com
admin123
```

Store a secure password hash on the backend.

The development admin account is the only administrative account needed for this milestone. Add a minimal `role` field to the user model with `ADMIN` as the development admin value. Do not build a large multi-role/permissions system. Administrative classifier CRUD endpoints must require an authenticated admin role.

Minimum routes:

```text
GET  /api/v1/health
POST /api/v1/auth/login
GET  /api/v1/auth/me

AI/admin routes are documented in the DINOv2 section below and must reuse the existing JWT authentication pipeline.
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

### Leaf Assessment History Sync

Leaf assessments are historical records, not one mutable status field. Each scan receives a unique assessment UUID and is stored separately from the TreeEntity.

Preferred flow:

```text
Leaf Scan
→ create assessment in Room
→ PROCESSING
→ local FastAPI AI assessment
→ COMPLETED / UNCERTAIN / FAILED
→ history immediately visible from Room
→ WorkManager sync when backend is reachable
→ FastAPI
→ MongoDB `leaf_assessments`
→ SYNCED
```

A tree may have many assessment records. Never use `treeId` as an assessment primary key or a unique database key. Re-syncing the same `assessmentId` must be idempotent. Pulling remote history must never replace or delete newer local history.

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

## AI: DINOv2 Tamarind Leaf Condition Assessment

This milestone adds tamarind leaf condition recognition using an open-source DINOv2 visual backbone and a lightweight supervised classifier head. The design must preserve the existing geotagging system and must not turn the entire application into an online-only application.

### Model decision

Use **DINOv2 ViT-S/14** as the default backbone for local development because the development computer is an AMD Ryzen 5 5500 system with 16 GB RAM and the previously specified local GPU is a GTX 1050 Ti-class device. ViT-S/14 is the conservative choice for this hardware. Do not use ViT-L or ViT-g. ViT-B/14 may be tested later only as an explicit performance experiment if the local machine has sufficient VRAM.

DINOv2 is a pretrained visual feature model, not a disease classifier by itself. It produces a visual representation that a small downstream classifier can use. The official DINOv2 model card documents a 384-dimensional embedding for ViT-S, a 14-pixel patch size, support for downstream linear/logistic/k-NN classification, and Apache 2.0 licensing for the DINOv2 models. The official repository also provides the ViT-S/14 backbone and classifier-head tooling. Do not claim a fixed disease-recognition accuracy from DINOv2 alone. The project's accuracy must come from the project's labeled tamarind dataset and evaluation.

### Recognition architecture

```text
Android CameraX / Local Test Image
              ↓
        FastAPI AI endpoint
              ↓
       Image validation/crop
              ↓
        DINOv2 ViT-S/14
              ↓
       384-dim visual feature
              ↓
      Tamarind classifier head
              ↓
   Healthy | Spotting | Mold | ...
              ↓
     confidence + model version
              ↓
       Android / stored result
```

Do not use OpenCV for the DINOv2 recognition pipeline. Do not describe DINOv2 embeddings as the same mechanism as OpenCLIP embeddings. OpenCLIP remains an optional supporting similarity/verification model, but it is not required for the primary DINOv2 classifier to function.

### Important terminology

The admin UI may be titled **Classifiers**, but each row such as `Healthy`, `Spotting`, and `Mold` is a **classification class/label**, not a separate DINOv2 model. The system should use one tamarind-leaf condition classifier with a flexible set of labels/classes.

Seed these classes in development when the classifier collection is empty:

```text
Healthy
Spotting
Mold
```

Names are editable. A future class such as `Other` or `Unknown` must be addable without changing application code.

### Dataset strategy

Persistent admin-uploaded dataset images belong to the server AI dataset, not the temporary root test folder.

Use a persistent directory/volume similar to:

```text
backend/ai_data/
  classifier_images/
    <class-id>/
      <image-id>.jpg
  embeddings/
    <image-id>.npy
  models/
    active_model.pt
    model_metadata.json
  jobs/
```

Do not store large image blobs or model binaries directly inside MongoDB. MongoDB stores metadata, references, counts, status, metrics, and model versions. Docker named volumes must preserve the AI dataset and model artifacts across container recreation.

Each dataset image record should include at least:

```text
id
classId
originalFilename
storedPath
mimeType
fileSize
sha256
createdAt
embeddingPath
embeddingModel
embeddingDimension
active
```

Reject exact duplicate uploads using a deterministic file/content hash where practical.

### Training strategy: do not retrain DINOv2 after every upload

The system must **freeze the DINOv2 backbone** for the first implementation. Train only the lightweight classification head.

The intended incremental pipeline is:

```text
Admin uploads image
        ↓
Save image + metadata
        ↓
Compute DINOv2 feature once
        ↓
Persist cached feature
        ↓
Dataset changed
        ↓
Queue classifier-head refresh
        ↓
Train/re-fit only the small classifier head
        ↓
Validate
        ↓
Publish new model version
```

This means the system does not repeatedly re-run DINOv2 feature extraction for every old image and does not fine-tune the 21M-parameter backbone every time an admin adds a picture. The classifier head may still read the existing cached feature vectors from the full active dataset during a refresh. That is intentional and is far cheaper than retraining the DINOv2 backbone.

Do not implement online one-sample gradient updates as the default. Re-fitting the head from cached features is more deterministic, easier to evaluate, and safer against class imbalance.

### Automatic retraining behavior

When the admin uploads one or many images:

1. Persist the images first.
2. Validate that every file is an accepted image.
3. Compute and cache the DINOv2 embedding once per new image.
4. Mark the dataset as changed.
5. Schedule a classifier-head training job.
6. If additional images arrive while a short debounce window is active, coalesce them into one training job instead of starting one training process per file.
7. Train from cached embeddings.
8. Evaluate on a deterministic validation split.
9. If validation succeeds, write a new model version and atomically make it active.
10. If training fails, keep the previous active model and mark the job `ERROR` with a clear message.

The UI must show `UNTRAINED`, `QUEUED`, `TRAINING`, `READY`, or `ERROR` rather than pretending training is immediate.

Provide a visible **Train / Rebuild Model** action as a manual fallback. Automatic retraining is the default, but the manual action is required for recovery and controlled testing.

### Training requirements

At minimum:

- supervised classification head over frozen DINOv2 features
- deterministic random seed for reproducible evaluation
- stratified train/validation split when class counts permit
- no data leakage from exact duplicate images between train and validation
- per-class image counts
- accuracy
- precision
- recall
- F1-score
- confusion matrix or equivalent per-class summary
- model version
- training timestamp
- class-to-index mapping stored with the model artifact

Do not report only overall accuracy. Do not claim that a model is accurate based on training accuracy alone.

A class with too few images must be shown as insufficient for training instead of being silently ignored. The system may allow dataset uploads while keeping the model `UNTRAINED` or `ERROR` until the minimum training requirements are met.

### Admin classifier UI

Add an authenticated **Admin → Classifiers** destination. Do not add a separate web frontend because the current repository contains an Android client plus FastAPI/MongoDB and no existing browser admin application.

The screen should be professional, responsive, and consistent with the existing GEO Tree field-research design.

Header/status area:

- `Tamarind Leaf Classifier`
- current model status
- current model version
- total dataset image count
- last successful training timestamp
- validation accuracy/F1 when available
- `Train / Rebuild Model` action

Class list:

```text
Healthy        128 images    READY
Spotting       142 images    READY
Mold            97 images    READY
```

Each class row supports:

- Edit class name
- View images
- Add/upload images
- Delete selected images
- Delete class

Provide **Add Classifier** to add a new classification class/label. Internally this creates a class record, not a new model.

Class deletion must require confirmation, invalidate the active model if necessary, and clearly explain that the model must be rebuilt after changing the class set.

### Image gallery

Inside a class:

- grid/list thumbnails
- image count
- original filename
- upload timestamp
- selection mode
- multi-select delete
- add/upload images
- loading/progress state
- empty state
- image preview
- clear confirmation for deletion

After an image is deleted, its cached embedding must also be deleted or marked invalid and the classifier model must be marked stale/queued for rebuild.

### API contract

Implement authenticated endpoints following the repository's existing `/api/v1` style. The exact route names may follow existing conventions, but functionality must cover:

```text
GET    /api/v1/admin/classifiers
POST   /api/v1/admin/classifiers
PATCH  /api/v1/admin/classifiers/{id}
DELETE /api/v1/admin/classifiers/{id}

GET    /api/v1/admin/classifiers/{id}/images
POST   /api/v1/admin/classifiers/{id}/images
DELETE /api/v1/admin/classifiers/{id}/images/{imageId}

GET    /api/v1/admin/ai/model
POST   /api/v1/admin/ai/retrain

POST   /api/v1/ai/leaf-assessment
GET    /api/v1/ai/jobs/{jobId}
```

Use multipart uploads for images. Reuse existing authentication, structured error format, and image validation patterns.

The public/field assessment endpoint must return at least:

```json
{
  "classId": "...",
  "className": "Spotting",
  "confidence": 0.94,
  "model": "dinov2_vits14",
  "modelVersion": 7
}
```

If no trained model is available, return an explicit machine-readable error such as `AI_MODEL_NOT_READY`; never fall back to an unverified class.

### Android leaf assessment

Add a `LeafAssessment` feature that can be opened from Tree Detail.

Required flow:

```text
Tree Detail
   ↓
Assess Leaf
   ↓
Capture/select leaf image
   ↓
Review image
   ↓
Submit to local FastAPI AI endpoint
   ↓
DINOv2 classification
   ↓
Show class + confidence + model version
```

For this milestone, AI assessment is **backend-assisted** and uses the local development server. Do not make the entire app online-only. Geotagging, maps, Room records, and field navigation must remain offline-capable.

When the AI server is unavailable, explain clearly that leaf assessment requires the local AI backend for this milestone and preserve the captured image locally rather than losing it.

Do not store only a mutable latest assessment. Every scan must create a dedicated historical record.

### Leaf assessment history flow

The required user journey is:

```text
Tree Detail
    ↓
Assess Leaf
    ↓
Capture / Select Image
    ↓
Review
    ↓
Run Assessment
    ↓
Result Page
    ↓
Back to Tree
    ↓
Tree Detail shows Latest Result + Assessment History
    ↓
Tap a previous scan
    ↓
Assessment Detail
```

The result page represents one specific scan. It must persist the result before navigation away.

Show on the result page:

- assessed image
- predicted condition/class
- confidence
- assessment timestamp
- DINOv2 model name
- model version
- Completed / Uncertain / Failed state
- Back to Tree
- optional View History

Tree Detail must show the latest completed result as a current-summary card and then show an Assessment History list ordered newest first. Each history item is tappable and opens a read-only detail view.

Example:

```text
Current Leaf Status
Spotting
94%
Latest scan · Oct 8, 2026 · 7:20 PM

Assessment History
────────────────────────
Oct 8 · 7:20 PM  Spotting · 94%
Oct 5 · 3:12 PM  Healthy · 91%
Oct 1 · 9:44 AM  Mold · 83%
```

### LeafAssessmentEntity

Use a dedicated Room entity/table rather than storing history inside `TreeEntity`:

```text
LeafAssessmentEntity
  id: String                  // assessment UUID
  treeId: String              // owning tree UUID
  imageLocalPath: String?
  remoteImagePath: String?
  classId: String?
  className: String?
  confidence: Float?
  modelName: String
  modelVersion: Int
  status: PENDING | PROCESSING | COMPLETED | UNCERTAIN | FAILED | SYNCING | SYNCED
  errorCode: String?
  errorMessage: String?
  createdAt: Long
  completedAt: Long?
  updatedAt: Long
  serverVersion: Long?
  lastSyncError: String?
```

Add DAO queries for:

```text
observeAssessmentsForTree(treeId) -> createdAt DESC
observeLatestCompletedAssessment(treeId)
observeAssessmentById(assessmentId)
```

Add indexes for `treeId`, `createdAt`, and `status` where useful. Update Room schema version with an explicit migration.

### MongoDB `leaf_assessments`

Use a dedicated collection. Minimum fields:

```text
assessment_id
tree_id
class_id
class_name
confidence
model_name
model_version
status
image_path
created_at
completed_at
updated_at
server_version
```

Indexes:

- unique `assessment_id`
- `tree_id + created_at`
- `server_version`

Do not make `tree_id` unique. One tree must support many scans.

### Assessment image isolation

Assessment photos are separate from training dataset images. Do not automatically add an assessment photo to `backend/ai_data/classifier_images/`. Only the explicit admin classifier-data workflow may add a photo to training data.

### Local/offline history

Assessment history must remain visible from Room even when FastAPI is unavailable. A failed scan must not silently delete the captured image or the historical event. The UI must distinguish an assessment failure from a successful classified result.

### Local test dataset folder

The repository root must support a temporary, developer-only batch test folder:

```text
ai_test_input/
  healthy/
  spotting/
  mold/
```

The subfolder names are expected/gold labels for evaluation only. They must not automatically become persistent training data.

Provide a local batch command/script that:

1. discovers images recursively under `ai_test_input/`
2. uses subfolder names as expected labels
3. sends images through the same FastAPI recognition path used by Android where practical
4. records predicted class and confidence
5. calculates a confusion matrix and aggregate metrics when enough labels exist
6. writes a report to a clearly named temporary result file/folder outside the input folder
7. only after successful completion, allows the entire `ai_test_input/` folder to be deleted/cleaned

Do not delete the input folder before results are captured. Never delete `backend/ai_data/`, MongoDB volumes, or model artifacts as part of test cleanup.

A Claude/Codex agent may use this folder during local verification, but must report the prediction results before cleaning it.

### Local development and Render separation

The current repository may be connected to a Git/Render deployment, but **this AI milestone must be tested against the local Docker backend**.

Development default:

```text
FastAPI: http://localhost:8000
Android emulator: http://10.0.2.2:8000
Physical phone: http://<laptop-lan-ip>:8000
```

Do not point the Android test build at the Render deployment unless the user explicitly requests a hosted test.

Keep hosted deployment configuration intact. Do not add Render-specific behavior to the recognition pipeline merely to make local development work.

### Docker AI dependencies

The local Docker backend may include the DINOv2/PyTorch runtime. Prefer a single backend service with a persistent AI-data/model volume unless the existing repository makes a dedicated AI service clearly safer.

Use CPU-safe defaults. If NVIDIA GPU execution is implemented, it must be an optional capability that cleanly falls back to CPU. Do not make a GTX-specific configuration mandatory.

Recommended development defaults for the shown hardware:

```text
DINOv2 model:      dinov2_vits14
Device:            auto, then CPU fallback
Embedding dim:     384
Initial image size: 336 x 336
Backbone:          frozen
Head:              lightweight supervised classifier
Batch size:        conservative, configurable
Workers:           0 by default on Windows/Docker for stability
```

The model accepts larger image shapes when they are multiples of the 14-pixel patch size, but larger inputs consume more memory. Keep the input size configurable and benchmark before increasing it. For small tamarind leaflets, prioritize a tight leaf crop or detection step over blindly increasing the whole-image resolution.

### OpenCLIP support

OpenCLIP may remain installed as an optional supporting model. It must not be required for the DINOv2 classifier to train or predict.

Do not mix OpenCLIP cosine similarity scores with DINOv2 classifier probabilities as if they were identical probabilities. If the two systems are compared or combined, calibrate any combination on a held-out validation set and document the method.

### AI filesystem and Git rules

Add AI runtime/data paths to `.gitignore`, including:

```text
backend/ai_data/
backend/ai_cache/
ai_test_input/
ai_test_results/
*.pt
*.pth
*.onnx
*.npy
*.npz
```

Do not commit trained model weights, uploaded dataset images, cached embeddings, temporary test images, `.env`, or personal machine paths unless the user explicitly requests a versioned model artifact.

### APK/root-output rule

Do not copy any APK to the repository root.

Build output remains at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Remove the old root `GEO_Tree_Test.apk` and its checksum copy if they exist, because local AI testing must not depend on a root APK artifact. Update README instructions so they point to the normal Gradle output path or an explicit user-selected copy destination outside the repository root.

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
- admin authorization for classifier endpoints
- classifier CRUD
- duplicate dataset image handling
- image delete invalidates cached embedding/model state
- automatic training job queue/debounce behavior
- model version activation/rollback on training failure
- AI prediction when model is ready
- explicit `AI_MODEL_NOT_READY` behavior
- AI batch-test endpoint/script behavior
- leaf assessment creation
- multiple assessments for the same tree
- assessment IDs remain unique
- old assessments are never overwritten
- assessment history ordering
- assessment detail retrieval
- assessment sync idempotency
- assessment change-feed behavior

Android:

- GPS accuracy-state mapping
- form validation
- duplicate Tree Code handling
- repository local-first save
- sync transitions
- login loading/error/success state
- dashboard counts/state mapping
- admin-only Classifiers visibility/authorization state
- classifier list/add/edit/delete UI state
- multi-image upload progress and failure handling
- dataset image deletion state
- model status transitions
- leaf assessment loading/success/error/model-not-ready states
- creating a new assessment history item
- two scans for one tree produce two history items
- newest-first history ordering
- latest-result derivation
- opening assessment detail
- offline history visibility
- assessment back-stack/navigation behavior
- Room migration for assessment history
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
- DINOv2 ViT-S/14 loads locally with CPU fallback
- classifier classes are configurable instead of hardcoded
- admin can add/edit/delete classes
- admin can upload/view/delete dataset images per class
- new images create cached DINOv2 features without recomputing old features unnecessarily
- automatic classifier-head rebuild is queued/coalesced after dataset changes
- previous active model remains available when a new training job fails
- model versions and class mappings are persisted
- leaf assessment API returns class, confidence, and model version
- Android leaf assessment states are handled cleanly
- each leaf scan creates a unique historical assessment record
- multiple scans for one tree are preserved
- Tree Detail shows latest result separately from history
- Assessment History is newest first
- each history item opens a read-only detail view
- assessment history remains visible offline from Room
- assessment metadata includes confidence, timestamp, model name, and model version
- assessment history sync is idempotent when the backend is reachable
- assessment images remain separate from training dataset images
- local `ai_test_input/` batch test produces a real report
- local AI testing uses Docker FastAPI, not Render
- root APK artifacts are removed and are not regenerated
- backend tests pass
- Android unit tests pass
- debug APK builds
- smoke test runs on the existing Medium Phone emulator
- physical-test APK can be produced after successful tests
- README contains exact setup/run/test instructions
- no fake or unverified success is reported

---

## Out of Scope Unless Explicitly Requested

Do not implement in this milestone:

- DINOv2 full backbone fine-tuning on the development PC
- TensorFlow Lite/on-device DINOv2 deployment
- OpenCV-based disease recognition
- Roboflow
- Teachable Machine
- Qdrant or a separate vector database
- cloud-hosted AI training/inference
- complex enterprise role/permission system
- university SSO
- full road-routing engine
- turn-by-turn voice navigation
- push notifications
- 3D features

The AI classifier must remain a focused extension of the existing Android + FastAPI + MongoDB architecture.

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
9. For AI work, report the selected model, device, dataset counts, model version, metrics, and whether the result was actually exercised on local Docker.
10. For history work, verify that a second scan for the same tree creates a second record and does not overwrite the first.
11. Report files changed, commands run, results, blockers, limitations, and next safest step.

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
Room  ← Android source of truth for field data
     ↓
WorkManager
     ↓
FastAPI
     ↓
MongoDB

Admin Classifiers / Leaf Assessment
     ↓
FastAPI AI layer
     ↓
DINOv2 ViT-S/14 (frozen)
     ↓
Cached visual features
     ↓
Lightweight classifier head
     ↓
Versioned model artifact

---

## AI Baseline Protection and Dataset Evaluation

The current DINOv2 ViT-S/14 implementation is the stable recognition baseline.

Do not switch models or redesign the AI architecture merely because an alternative appears newer. Improve the dataset and evaluation methodology first.

### Dataset rules

Prioritize real, correctly labeled, diverse tamarind leaf photographs over raw image count.

Recommended dataset diversity:

- multiple tamarind trees
- multiple collection sessions
- different lighting conditions
- direct sunlight and shade
- different phones/cameras
- different distances and angles
- young and mature leaves
- mild and severe symptoms
- realistic field backgrounds

Avoid exact duplicates and near-duplicate burst/session photos across train and test. Investigate contradictory labels before training.

Keep assessment images separate from training images unless the admin explicitly promotes them into a classifier class.

### Evaluation rules

Report:

- images per class
- training count
- validation count
- independent test count
- accuracy
- precision
- recall
- macro F1
- confusion matrix
- model version

For meaningful evaluation, prefer a session/tree-grouped test split instead of randomly splitting highly similar photos from the same capture session.

Do not use training accuracy as evidence of field performance.

The next AI development phase is dataset audit, leakage-resistant evaluation, physical-device testing, and error analysis. Only after those steps should model architecture changes be considered.

## Hosted Migration: Hostinger Shared Deployment

GEO Tree may be deployed on the same Hostinger VPS currently used by the Museum App, but it must be treated as a completely separate production application.

The migration goal is:

```text
Existing Museum App
        │
        ├── remains in its existing directory
        ├── remains on its existing Docker Compose project
        ├── keeps its existing MongoDB/Qdrant/TripoSR resources
        └── keeps its existing API hostname

GEO Tree
        │
        ├── separate directory
        ├── separate Docker Compose project
        ├── separate containers
        ├── separate volumes
        ├── separate MongoDB database/storage
        ├── separate AI/model storage
        └── separate subdomain
```

### Protected Museum Deployment

Known Museum production resources are protected and must not be reused by GEO Tree.

Known Museum deployment root:

```text
/opt/museum-app/
```

Known Museum Compose project:

```text
museum_app_prod
```

Never deploy GEO Tree into `/opt/museum-app/source` or `/opt/museum-app/deploy`.

Never reuse or modify the Museum:

- MongoDB data/volume
- Qdrant data/volume
- OpenCLIP model or embedding volumes
- TripoSR jobs/model cache
- upload directories
- environment files
- container names
- database credentials
- application source
- production API routes

Never run Museum deployment scripts as part of GEO Tree deployment.

### GEO Tree Server Layout

Preferred:

```text
/opt/
├── museum-app/
└── geotree/
    ├── deploy/
    ├── backend/
    └── ...
```

GEO Tree must have its own Compose project and must not depend on the Museum Compose project for its application/data lifecycle.

Preferred GEO Tree services:

```text
geo-tree-api
geo-tree-ai
geo-tree-mongodb
```

Preferred dedicated volumes:

```text
geo_tree_mongodb_data
geo_tree_uploads
geo_tree_ai_data
```

Preferred dedicated network:

```text
geo-tree-network
```

Names must be checked against the live server before creation.

### Reverse Proxy and Ports

The existing Hostinger deployment uses Caddy for public HTTPS.

Do not run another reverse proxy on host ports 80 or 443.

GEO Tree should be exposed through the existing Caddy instance using an additive route only:

```text
https://geotree.museuma7k9x3.tech
        ↓
GEO Tree API
```

The GEO Tree AI service should remain private and should be reachable by the GEO Tree API over Docker networking only.

MongoDB must never be publicly exposed.

Do not expose the AI training/inference service directly to the public internet unless explicitly required by a later architecture decision.

Do not change the Museum hostname:

```text
https://api.museuma7k9x3.tech
```

Any Caddy configuration change must be additive. Preserve every existing Museum route exactly.

Before and after a Caddy change, verify that the Museum API remains healthy.

### Domain and DNS

The shared domain is:

```text
museuma7k9x3.tech
```

GEO Tree preferred hostname:

```text
geotree.museuma7k9x3.tech
```

Do not replace or alter the Museum API hostname.

SSH access to the VPS does not automatically grant DNS-management access. If the available environment cannot modify Hostinger DNS, report the exact required record instead of claiming the DNS change succeeded.

Preferred record concept:

```text
geotree.museuma7k9x3.tech  →  Hostinger VPS
```

Do not modify DNS without verifying the required Hostinger/DNS access and explicit deployment authorization.

### Hosted Storage and User Uploads

GEO Tree must store its server-side data persistently.

Persistent GEO Tree data includes:

```text
MongoDB data
Tree registration images
Leaf assessment images
Classifier training images
Cached DINOv2 features
Model versions
AI job artifacts/metadata
```

Recommended separation:

```text
uploads/
├── tree_images/
├── leaf_assessments/
└── classifier_images/
```

Training data is separate from field assessment data.

A normal field scan must never automatically become training data.

Only authorized classifier-management operations can add a photo to a training class.

New admin training images may be uploaded to the hosted server so the classifier dataset can improve over time.

The existing incremental training design remains:

```text
Admin adds training image(s)
        ↓
Persist hosted image
        ↓
Compute DINOv2 feature once per new image
        ↓
Cache feature
        ↓
Queue classifier-head rebuild
        ↓
Train from cached features
        ↓
Validate
        ↓
Create new model version
        ↓
Activate only if successful
```

Do not recompute old valid embeddings unnecessarily.

Do not retrain the full DINOv2 backbone after every upload.

### Hosted AI Resource Safety

The Hostinger VPS is reported to have approximately 8 GB RAM, and the existing Museum TripoSR workload has previously reached approximately 7.7 GB RAM.

Therefore GEO Tree must be resource-safe on a shared server.

Rules:

- DINOv2 ViT-S/14 remains the current model.
- Use one AI model process where practical.
- Do not create multiple unnecessary Uvicorn workers for the AI service.
- Do not allow concurrent training jobs.
- Training jobs must check available memory before starting.
- If resources are insufficient, keep the previous model active and mark the training job delayed/failed.
- Do not allow GEO Tree to destabilize Museum services.
- Do not intentionally run Museum TripoSR and GEO Tree training concurrently unless resource testing proves it safe.
- Prefer inference over training on the shared VPS when memory is constrained.

If hosted classifier training proves unsafe on the shared VPS, keep training local and deploy model artifacts for hosted inference instead. Do not silently switch strategies.

### Hosted Assessment Architecture

Android communicates only with the GEO Tree public API:

```text
Android
   ↓
https://geotree.museuma7k9x3.tech
   ↓
GEO Tree API
   ↓ private Docker network
GEO Tree AI
   ↓
DINOv2 ViT-S/14
   ↓
Classifier
   ↓
Leaf assessment record
   ↓
MongoDB
```

Android must not call `geo-tree-ai` directly.

The AI service must not expose MongoDB or the filesystem.

### Assessment History on Hosted Server

Every leaf scan remains a permanent historical event.

Use a dedicated `leaf_assessments` MongoDB collection and the existing dedicated Room entity.

Each assessment has its own assessment UUID.

A tree can have many assessments:

```text
Tree GEO-TAM-013

Oct 1  → Mold 83%
Oct 5  → Healthy 91%
Oct 8  → Spotting 94%
```

Never use `treeId` as a unique assessment key.

Repeated sync of the same `assessmentId` must be idempotent.

The latest result is a derived summary. It does not replace history.

### Stable APK Download URL and QR Code

The APK distribution URL must remain stable across versions.

Use:

```text
https://geotree.museuma7k9x3.tech/download
```

The QR code must encode this stable URL, not an APK filename or version.

Recommended flow:

```text
QR code
    ↓
https://geotree.museuma7k9x3.tech/download
    ↓
GEO Tree download page
    ↓
Current APK
```

When a new APK is released, update the current APK behind the same download page.

Do not regenerate the QR merely because the APK version changes.

For each APK release, publish a SHA-256 checksum for the APK separately.

Important distinction:

```text
QR destination = stable URL
APK SHA-256 = per-release file verification hash
```

The APK hash is expected to change when the APK changes.

### Local vs Hosted URLs

Local emulator:

```text
http://10.0.2.2:8000
```

Physical phone on LAN:

```text
http://<laptop-lan-ip>:8000
```

Hosted:

```text
https://geotree.museuma7k9x3.tech
```

The Android backend URL must remain configurable.

Do not hardcode production IP addresses.

Do not remove local Docker support.

### Migration Procedure

Before migration:

1. Inspect the GEO Tree repository.
2. Inspect the live Hostinger VPS read-only.
3. Verify Museum containers, volumes, networks, Caddy routes, ports, memory and disk.
4. Verify GEO Tree resource names do not collide.
5. Verify persistent storage.
6. Verify DNS feasibility.
7. Verify the actual Caddy configuration.
8. Establish a GEO Tree-only rollback plan.

During migration:

1. Create GEO Tree only under its dedicated directory.
2. Use a separate Compose project.
3. Use separate containers and volumes.
4. Keep MongoDB internal.
5. Keep AI internal.
6. Add only the required Caddy route.
7. Do not edit Museum application files.
8. Do not stop Museum containers.
9. Do not rebuild Museum containers.
10. Do not run global Docker cleanup.
11. Test GEO Tree independently.
12. Verify Museum health after every production routing/storage change.

If safe coexistence cannot be demonstrated, stop and report the blocker.

### Rollback

Rollback must be GEO Tree-only.

A GEO Tree rollback may remove:

- GEO Tree containers
- GEO Tree deployment files
- GEO Tree volumes only if explicitly approved
- GEO Tree Caddy route
- GEO Tree DNS record when explicitly approved

A GEO Tree rollback must never remove or roll back Museum resources.

### Hosted Migration Definition of Done

- GEO Tree has a dedicated server directory.
- GEO Tree has a dedicated Compose project.
- GEO Tree has dedicated containers.
- GEO Tree has dedicated volumes.
- GEO Tree has dedicated AI storage.
- GEO Tree has a dedicated MongoDB database/volume.
- Museum resources were not reused.
- MongoDB is not publicly exposed.
- AI is not publicly exposed.
- GEO Tree has its own HTTPS hostname.
- Museum API still works on its existing hostname.
- Hosted classifier image upload works.
- Hosted training images persist after container restart.
- Cached DINOv2 features persist.
- Model versions persist.
- Assessment images persist.
- Assessment history persists.
- Repeated assessment sync creates no duplicates.
- New admin training images can trigger classifier-head rebuild.
- Previous model remains active if training fails.
- Hosted leaf assessment works when AI resources are available.
- Local Docker AI remains functional for development.
- Stable `/download` URL works.
- QR points to the stable `/download` URL.
- Each release publishes a separate APK SHA-256 checksum.
- Museum health and containers were verified after migration.
- No unverified deployment result is reported.
