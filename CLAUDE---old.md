# GEO Tree — Project Engineering Charter

## 0. Current Milestone Override (2026-10-20)

This section takes priority over any older deployment notes below where they conflict. The old Hostinger VPS is not the target for the current milestone. Do not access it, deploy to it, modify it, or rely on it. The former VPS may have hosted both GEO Tree and the separate Museum App; the Museum project must remain completely untouched.

Current goal:

1. Replace the active GEO Tree **classifier training dataset only** with the valid labeled image set in the `data-sets.zip` archive at the repository root (expected source parts include `Annah Kyla Part 1` and `Annah Kyla Part 2`).
2. Use canonical classifier classes `Healthy`, `Spotting`, and `Powdery Mildew`. Map source label `Mold` to `Powdery Mildew` for the new dataset as requested, while flagging apparent label contamination instead of pretending the mapping is scientifically verified.
3. Train and evaluate locally with the existing frozen DINOv2 ViT-S/14 + lightweight head pipeline, export a compatible ONNX/model-head/manifest bundle, and include that bundle in the Android APK.
4. Keep leaf prediction fully on-device. A first inference must work without internet, a hosted model, or a model download.
5. Use local Docker for heavy feature extraction/training/export and development tests.
6. Prepare a lightweight Render FastAPI service that uses MongoDB Atlas for structured GEO Tree records. Do not host DINOv2, training, model caches, or durable image storage on Render free local filesystem.
7. Preserve local Room data, tree/assessment history, sync/idempotency, offline map, GPS/navigation, and all unrelated field features.
8. Run the relevant tests and build a client-test APK. Commit/push safe, relevant source and required mobile model assets to the verified `origin` only after release gates pass. Do not commit secrets or raw datasets by default.

For this milestone, the step-by-step `CLAUDE_EXECUTION_PROMPT.md` is the task checklist. Continue to follow the stable architecture, test, security, and Museum-isolation rules below. Never remove the local Docker training environment just because Render won't run the AI workload.

### Current hosting rules

- **Render:** lightweight API service only, configured using the confirmed app module and `$PORT`; keep startup dependencies light. Do not store user uploads, SQLite files, classifier datasets, or model artifacts on the Render local filesystem.
- **MongoDB Atlas:** structured account/tree/assessment metadata only, using environment secrets. Never reset or wipe Atlas during deploy. The deployed Android client must not contain the MongoDB URI or secrets.
- **Local Docker:** backend development, classifier image administration/import as supported by the repo, DINOv2 features, model training, model export, and full tests.
- **Android APK:** bundled, checksum-verified on-device model; offline leaf inference and local saving must work without a network connection. Cloud authentication/sync may require connectivity and must gracefully defer when offline.
- Render free services may cold-start and their local filesystem is ephemeral; Atlas free storage and throughput are limited. Verify current provider documentation during deployment.

### Dataset replacement safety

The user authorizes replacing only the active classifier training dataset and derived artifacts/cache that depend on it. Back up the current affected volume/files and classifier metadata first. Do **not** delete tree/account/assessment records, assessment/tree photos, general uploads, offline map assets, secrets, or unrelated records. Old training files must not leak into the new active dataset, but copies remain in the rollback archive. Rebuild caches if provenance/compatibility cannot be proven. Keep a provenance manifest and deterministic train/validation/test split with duplicate/group leakage controls.

### Historical notes below

Any older statements describing a live Hostinger GEO Tree deployment, Caddy/DNS edits, GEO Tree volumes on that VPS, or the old production URL are historical context only. Do not perform those actions for this milestone. Museum isolation is permanent and remains mandatory.

## 1. Mission and Current Direction

GEO Tree is an offline-first Android field-management and tamarind-leaf assessment system. It is evolving into two coordinated clients sharing one backend:

1. **Web Administration Platform** for users, field data review, tags/metadata, image management, classifier datasets, DINOv2 model status, analytics, and administrative CRUD.
2. **Android Field Tester Application** for authentication, registering/geotagging trees, field data collection, image capture, leaf assessments, and viewing a tree's assessment history.

The Android application is not being replaced. Its administrative responsibilities will be moved to the web in a staged migration, while its field workflows remain reliable and offline-capable.

Treat this repository as the source of truth for GEO Tree. Do not conflate it with the separate Museum App repository.

### Known local repository paths

- GEO Tree: `C:\CAPSTONE.2`
- Existing Museum App: `C:\Capstone\Museum_App`

Verify these paths before acting. Do not assume the current working directory is correct.

### Historical hosted environment (do not use for this milestone)

Earlier work used a Hostinger VPS and a shared Caddy reverse proxy. The VPS may have hosted both projects. This information is historical, not evidence of current reachability or data retention. The current migration target is Render + MongoDB Atlas for GEO Tree only. Do not reconnect to or modify the former VPS as part of this task.

The Museum App uses its own source, services, databases, Qdrant data, uploads, and model caches. Do not stop, disable, reconfigure, inspect internally, or alter any Museum resource.

---

## 2. Stable Baseline — Preserve These Features

The following behavior is the stable baseline. Do not redesign, replace, or remove it merely for stylistic reasons, to introduce a newer framework, or to simplify the code.

### Android and field operations

- Kotlin and Jetpack Compose
- Room local-first data storage
- WorkManager synchronization
- FastAPI as the Android-to-server gateway
- MongoDB server persistence
- Existing authentication and session handling, migrated carefully
- GPS geotagging and tree registration
- MapLibre and the current offline map package/coverage behavior
- Current direct field navigation, including distance/bearing/speed/ETA handling
- Existing tree images and tree records
- Offline access to already downloaded tree data and assessment history

### AI and assessments

- DINOv2 ViT-S/14 as the primary current visual backbone
- Frozen backbone for current operation
- Cached DINOv2 features for the training dataset
- Lightweight supervised classifier head
- Configurable classes/labels such as `Healthy`, `Spotting`, and `Powdery Mildew` (source `Mold` is migrated to `Powdery Mildew`)
- Admin image CRUD for training datasets
- Automatic, coalesced classifier-head rebuilding after dataset changes
- Model-version records and safe activation/rollback
- Dedicated leaf-assessment records and assessment history per tree
- On-device (offline) leaf inference in Android with a bundled, checksum-verified model that matches the server pipeline (`backend/scripts/export_mobile_model.py`, README section 11)
- Assessment images kept separate from training dataset images
- Local Docker AI testing

The current assessment-history implementation was reported to pass 92 backend tests and 228 Android tests. A manual emulator test recorded six scans as six unique assessment records, preserved history offline, confirmed newest-first ordering, and verified that repeated synchronization did not add duplicates. Re-run tests on the current checkout; do not treat historical test counts as a substitute for current verification.

### Local development

- Root `compose.yaml` runs local GEO Tree services.
- Emulator API default: `http://10.0.2.2:8000`.
- Local host API: `http://localhost:8000`.
- Physical phone: laptop LAN IP and backend port, after firewall/network validation.
- Local Docker is an optional development environment, not a prerequisite for every test. It is needed only for work that requires the server's PyTorch/DINOv2 runtime (e.g. local training, `backend/scripts/export_mobile_model.py`).
- Historical note: `https://geotree.museuma7k9x3.tech` was reported healthy on 2026-10-12, but it is not the target for the current milestone and must not be assumed to remain available. Use the configured local environment and the newly authorized Render environment for new checks; do not use the former VPS.
- Android tools: `adb` is not on PATH; use `<sdk.dir from local.properties>\platform-tools\adb.exe`. A physical-device claim requires a device listed as `device` by `adb devices -l`; the `Medium_Phone` emulator is for preliminary checks only.

---

## 3. Engineering Principles

1. **Inspect before editing.** Read `README.md`, `CLAUDE.md`, `CLAUDE1.md` if present, build files, API routes, database schemas, and tests.
2. **Preserve before replacing.** Map current features, dependencies, users, data, and APIs before moving them.
3. **No blind rewrite.** Do not rewrite the backend or Android architecture wholesale.
4. **Feature parity before removal.** Do not remove an Android admin feature until its web replacement is implemented and tested, unless the user explicitly approves a different sequence.
5. **Small vertical slices.** Implement a coherent feature, test it, then proceed.
6. Keep explicit data flow: Compose → ViewModel → repository → Room/API; web UI → API client → FastAPI → repositories/services → MongoDB/storage.
7. Android never connects directly to MongoDB or a private AI container.
8. Never claim tests, deployment, backups, DNS, TLS, image persistence, model training, or service health that were not actually verified.
9. Never commit secrets, `.env` files, tokens, SSH keys, keystores, user images, cached embeddings, or trained weights by default.
10. Make migrations backwards-compatible where practical and include rollback steps.
11. Any operation that might damage or interrupt the Museum App is a hard stop until specifically approved.
12. Never run global Docker prune/cleanup commands on the shared Hostinger VPS.
13. Never run `docker compose down -v` against production.
14. Use the existing UI/design system and patterns when good; avoid unnecessary dependencies.
15. Report remaining work and limits accurately.

---

## 4. Target System Architecture

```text
Android Field App (Kotlin/Compose)
  |-- local Room data, photos, offline map
  |-- on-device ONNX inference (bundled model; no network required)
  |-- WorkManager queues sync while offline
  |
  +---- HTTPS GEO Tree API (FastAPI on Render; lightweight routes only)
                  |
                  +---- MongoDB Atlas (structured account/tree/assessment metadata)

Local Docker (development only for heavy AI)
  |-- classifier dataset import and validation
  |-- frozen DINOv2 feature extraction/cache
  |-- lightweight head training and evaluation
  +-- export/parity tests for Android ONNX assets
```

The Android app must not connect directly to MongoDB Atlas or a private AI service. A prediction must run from the model bundle inside the APK. The hosted API does not train or infer with DINOv2 and must not depend on model files or writable local storage to preserve user data.

### Current deployment shape

- Render Free web service: lightweight FastAPI API, with the confirmed module/start command and `$PORT` binding.
- MongoDB Atlas: structured data only, via server-side `MONGO_URI` environment secret.
- No DINOv2 inference/training on Render; no model cache or user-uploaded image persistence on Render's ephemeral filesystem.
- Local Docker remains available for training, dataset administration/import as supported by the existing app, feature cache, model export, and tests.
- Android production API URL must use the configured Render URL after it is confirmed. Preserve emulator `http://10.0.2.2:8000`, host `http://localhost:8000`, and physical-device local configuration for development.

Do not reset Atlas or make destructive schema changes as part of deployment. Render may cold-start after idle; handle slow first responses, retries, and offline operation without preventing local inference. Confirm current Render/Atlas limits from official documentation before deployment.
---

## 5. Product Responsibility Split

### 5.1 Web Administration Platform

The web app is the primary administrative interface. It must use the existing FastAPI and MongoDB foundation rather than creating an independent database or bypassing current business logic.

Modules:

- Dashboard/analytics
- Tree and field-record review
- Image management and metadata
- General tags/categories/relationships, based on existing data structures
- DINOv2 classifier-class management
- Per-class training-image management
- AI job/model status and model-version history
- Assessment history review
- User/account management
- Admin management, subject to role permissions
- Activity/audit log where practical
- Release/download page or route for the Android APK

Do not confuse generic record tags with classifier classes. For example, `Healthy`, `Spotting`, and `Powdery Mildew` are the current canonical classifier labels (the old source label `Mold` is mapped during the dataset migration); a tag such as `PSAU field`, `survey round 1`, or a tree-category label is a different concept unless the existing model explicitly defines it otherwise.

### 5.2 Android Field Tester Application

Keep Android focused on field operations:

- Login/session handling
- Tree selection and/or tree registration as supported by current workflows
- GPS geotagging
- Image capture/selection
- Required field metadata and tagging workflow
- Save locally first
- Queue/synchronize data when possible
- Assess leaf on the device with the bundled DINOv2 ViT-S/14 + classifier head (ONNX Runtime, since 0.7.0); no network is needed for a prediction, and the server only syncs records and distributes compatible classifier-head updates
- Display each scan's result
- Show current/latest leaf status on Tree Detail
- Show the tree's persistent assessment history newest first
- Open a historical scan in a read-only detail screen
- Keep tree data/history accessible offline once stored locally

Move administrative classifier CRUD and user administration into the web application. Do not remove leaf assessment, tree history, local-first field data, maps, navigation, or sync as part of that simplification.

---

## 6. Web Technology and UX

Preferred starting point:

- React
- TypeScript
- Vite for a static frontend build unless repository constraints justify a different option
- Tailwind CSS
- A maintained accessible component library only if useful
- A chart library only where it supports meaningful analytics

A static frontend is preferred for deployment simplicity and lower persistent server load. Evaluate existing code before committing to this stack. Do not introduce Next.js server rendering if a static app can meet the use case without extra server processes.

### UX requirements

- Professional, restrained data-management UI
- Desktop sidebar navigation
- Responsive mobile navigation
- Search, filtering, sort, and pagination for long lists
- Proper loading, empty, error, success, forbidden, and offline states
- Accessible labels and keyboard navigation
- Clear confirmation for destructive actions
- No fake chart data
- Charts and counts must come from real API responses
- Do not put secrets or authorization logic in frontend code

### Dashboard data

Use real values where data exists, including:

- total registered trees
- total tree-registration images
- total leaf assessments
- training-image count by class
- class distribution
- active/suspended users, if accurately tracked
- recent uploads
- latest assessment activity
- AI model state/version
- training job state
- data-completeness indicators with documented definitions

If a metric is unavailable, omit it or label it as unavailable. Never substitute sample numbers in production.

---

## 7. Authentication, Authorization, and Roles

Target roles:

### Super Admin

- Full administrative permissions
- Manage Admin accounts and Field Tester accounts
- Configure administrative settings
- Access all management modules
- Delete records where policy permits

### Admin

- Manage tree data and associated metadata
- Manage tags/categories where supported
- Review field records and assessments
- Manage classifier classes and training images
- Start or inspect model jobs where allowed
- View analytics
- Cannot promote another user to Super Admin unless explicitly granted that permission

### Field Tester

- Use the Android field workflows
- Access only authorized field data and permitted uploads/assessment history
- No access to web administration APIs or pages

Implement authorization on the FastAPI backend. Hiding a menu or route in React is not authorization.

### Migration compatibility

- Inspect the current user schema and JWT claims first.
- Preserve current user IDs and password hashes.
- Do not lock out existing users.
- Map existing roles deliberately; do not automatically promote every legacy admin to Super Admin.
- Provide a secure, documented, one-time bootstrap path for the initial Super Admin using an environment variable/CLI or another private mechanism. Never hardcode a production password.
- Avoid unauthenticated password-reset endpoints. If password reset is not implemented, provide an admin-assisted secure workflow and document the limitation.
- Reuse current password hashing and JWT infrastructure unless a demonstrated security defect requires an approved migration.
- Use short, structured 401/403 responses without leaking sensitive details.
- Audit high-impact role changes and destructive administrative operations.

---

## 8. Backend and API Rules

Stack:

- Python
- FastAPI
- Pydantic
- PyMongo
- MongoDB
- existing JWT/password-hashing implementation
- pytest

Preserve existing Android API contracts. New browser-admin endpoints should normally follow the versioned route convention, for example:

```text
/api/v1/admin/auth/...
/api/v1/admin/dashboard
/api/v1/admin/trees/...
/api/v1/admin/images/...
/api/v1/admin/tags/...
/api/v1/admin/users/...
/api/v1/admin/activity/...
/api/v1/admin/classifiers/...
/api/v1/admin/ai/...
/api/v1/admin/assessments/...
```

Inspect actual current routes before choosing exact paths. Do not create duplicate endpoint families if a current route can be safely extended.

Backend responsibilities:

- enforce JWT authentication and role permissions
- validate request bodies, query parameters, uploaded MIME/content, size, and dimensions as appropriate
- apply pagination limits
- use deterministic file IDs rather than trusting user filenames for storage paths
- avoid path traversal and arbitrary file access
- centralize business rules in services/repositories, not duplicated across clients
- return stable machine-readable error codes
- retain idempotency for sync endpoints
- rate-limit or otherwise guard expensive/abusive endpoints where practical
- ensure uploads require authentication and relevant authorization

Never expose MongoDB, the AI service, model files, or server filesystem directly.

---

## 9. Data Model and Storage Separation

Inspect real MongoDB documents and current collections before making schema changes. Do not assume that an example schema matches production data.

Likely GEO Tree collections include:

- `users`
- `trees`
- `tags` if required by the existing/tag-management feature
- `classifier_classes`
- `classifier_images`
- `ai_models`
- `ai_jobs`
- `leaf_assessments`
- `activity_logs` if activity auditing is implemented

Use existing names if already established. Do not duplicate collections just to match this list.

### Three separate image domains

1. **Tree-registration images:** image evidence attached to a tree record.
2. **Classifier training images:** admin-managed labeled examples for the DINOv2 classifier.
3. **Leaf-assessment images:** photos attached to individual historical scan events.

Keep file storage and metadata relationships separate. Normal field assessment photos must never automatically enter the classifier training dataset.

Recommended isolated on-disk layout or equivalent volume-backed layout:

```text
uploads/
  tree_images/
  leaf_assessments/

ai_data/
  classifier_images/<class-id>/
  embeddings/<image-id>.npy
  models/
    active model metadata/artifact
    versions/<version>/
  jobs/
```

Use dedicated persistent volumes for upload and AI data. MongoDB stores metadata, references, labels, statuses, timestamps, and metrics; do not store large image binaries/model weights in MongoDB by default.

### Upload requirements

- Validate actual file content, not only extension or client MIME type.
- Support common image formats that the implementation can decode.
- Normalize unsupported mobile formats such as HEIC into a consistent JPEG/PNG representation where appropriate, retaining the original filename as metadata.
- Enforce size limits and give clear client errors.
- Use SHA-256/content hashes for exact duplicate detection where appropriate.
- Never trust a supplied path or filename to choose a filesystem path.
- Store files under generated IDs and known category directories.
- Do not serve the entire uploads directory publicly. Serve assets through authenticated routes or short-lived access mechanisms where needed.

---

## 10. DINOv2 AI Requirements

Current primary model: **DINOv2 ViT-S/14**.

Current architecture:

```text
Image
  ↓
DINOv2 ViT-S/14 (frozen backbone)
  ↓
384-dimensional visual feature
  ↓
lightweight supervised classifier head
  ↓
condition label + confidence + model version
```

Do not replace the primary model during the web/admin migration. Do not add Qdrant for GEO Tree. OpenCLIP may remain optional supporting infrastructure but is not required for DINOv2 inference.

### Training pipeline

When an Admin uploads classifier training images:

1. Validate and persist images/metadata.
2. Identify exact duplicates.
3. Compute DINOv2 features only for new or invalidated images.
4. Cache each feature and associate it with backbone/preprocessing version.
5. Queue a coalesced classifier-head refresh.
6. Allow no more than one model-training job at a time by default.
7. Fit the lightweight head from cached features.
8. Evaluate against a validation split that avoids known duplicates/leakage where possible.
9. Save a new model version and metadata.
10. Atomically activate the new version only after the required checks pass.
11. Keep the previous model active if training fails.

Do not fine-tune the 21M-parameter backbone for each upload. Do not rerun feature extraction on every historical image unnecessarily. Do not run one training process per uploaded file.

### Model metadata

Keep a versioned record of:

- model/version ID
- backbone name/version
- preprocessing configuration
- class-to-index mapping
- training timestamp
- dataset counts per class
- validation metrics
- artifact path/checksum
- activation state
- training job ID/status

An assessment must preserve the exact class, confidence, model name/version, and timestamps used at that time. Later model updates must not rewrite old assessment predictions.

### Honest evaluation

Report dataset size by class, independent test size, accuracy, precision, recall, macro F1, and a confusion matrix when enough labeled test data exists. Avoid random train/test splits that put near-identical burst/session photos on both sides. Prefer tree/session-grouped separation for independent evaluation.

Do not claim accuracy based on training metrics alone.

---

## 11. Leaf Assessment History

Every attempted/completed leaf scan is a distinct historical event with a unique `assessmentId`.

Required user flow:

```text
Tree Detail
  → Assess Leaf
  → capture/select image
  → review
  → run assessment
  → result page
  → return to Tree Detail
  → latest status + historical scans
  → tap an older scan to open its read-only details
```

A new assessment never overwrites an older assessment. Retrying a failed attempt creates a new record and leaves the original failed attempt visible.

### Storage

- Android Room has a dedicated assessment table/entity indexed by `treeId`, timestamp, and status where appropriate.
- MongoDB has a dedicated `leaf_assessments` collection.
- Do not store an ever-growing assessment array inside `trees`.
- Do not make `treeId` unique in `leaf_assessments`.
- `assessmentId` is unique and used for idempotent sync.
- History is newest-first.
- The current/latest condition is a derived query over completed assessments, not a replacement for history.
- Existing history stays visible offline from Room.
- Images remain separate from classifier training images.

Assessment fields should include, as applicable:

```text
assessmentId
 treeId
 classId
 className
 confidence
 modelName
 modelVersion
 status
 imageLocalPath / image reference
 createdAt
 completedAt
 syncStatus
 serverVersion
 errorCode / errorMessage
```

Use consistent naming conventions in actual Kotlin/Pydantic models.

### Sync

- Save the attempt locally before remote work where current flow supports it.
- Synchronize by unique `assessmentId`.
- Repeated syncs must not create duplicates.
- A failed server request does not silently delete a local event/photo.
- Offline history remains accessible.
- Never invent an AI result if the server/model is unavailable.

---

## 12. Dataset Quality and Admin Classifier UX

Classifier classes are labels, not separate DINOv2 models. The current canonical names for this migration are `Healthy`, `Spotting`, and `Powdery Mildew`; the source folder label `Mold` maps to `Powdery Mildew` for this new dataset. Keep class handling configurable where the existing architecture supports it.

Each class supports create/rename/delete with duplicate/blank validation and confirmation for deletion. Each class has a Manage Images page with previews, upload, multi-upload, selection, single/multi-delete, progress, empty/error states, content-hash duplicate handling, and model rebuild status.

Classifier card design should have a single vertical three-dot menu with exactly the actions:

- Rename Classifier
- Manage Images
- Delete Classifier

Do not place separate edit/delete icons or redundant bottom action buttons on each card.

Data quality rules:

- inspect exact duplicates and conflicting labels
- report unreadable files
- retain originals unless the admin explicitly confirms cleanup
- avoid near-duplicate photos from one burst/session across training and test
- keep classes reasonably balanced without making fake duplicates
- include real field lighting, trees, distances, angles, and symptom severity
- never auto-promote field assessment photos to training data

---

## 13. Android Simplification Rules

Before removing an Android administration screen, verify that the equivalent web feature exists, is authorized, and has passed API/UI tests.

Keep Android field tester capabilities:

- login and session handling
- geotagging/tree registration
- field image capture/selection
- field metadata/tagging
- offline storage and synchronization
- map/offline map and navigation
- leaf assessment, result screen, and history
- local display of existing data when disconnected

Move administrative operations to web only after parity has been proved:

- user/admin management
- classifier class CRUD
- classifier training-image management
- admin analytics
- tag/system data management where the field workflow does not require it

Do not delete a feature just because it is inconvenient to move. Document a feature matrix with `Keep in Android`, `Move to Web`, `Shared`, or `Remove only with approval` before changing Android navigation.

---

## 14. Historical Hostinger Notes and Museum Isolation

The previous Hostinger/VPS deployment is not used for the current GEO Tree milestone. Do not SSH to that VPS, edit its files, alter DNS/Caddy, redeploy there, or use it for training. The current hosting target is Render + MongoDB Atlas as described in Section 0 and Section 4.

### Protected Museum resources

The separate Museum App is protected and outside the scope of this task. Known local source: `C:\Capstone\Museum_App`. Historical hosted roots included `/opt/museum-app/` and a Compose project named `museum_app_prod`; exact live details are not relevant to this migration.

Never:

- deploy GEO Tree inside the Museum directory;
- overwrite Museum source/deployment files;
- inspect or operate Museum containers, MongoDB, Qdrant, uploads, OpenCLIP storage, TripoSR jobs/caches, or volumes;
- change Museum domains, routes, DNS, Caddy, TLS, or service configuration;
- stop/restart/rebuild a Museum service or run Museum deployment scripts;
- run global Docker prune/cleanup or broad resource deletion commands.

Keep local GEO Tree Compose services and volumes separate from Museum resources. If any local Docker resource or path ownership is ambiguous, do not modify it. Do not change anything merely to make a cleanup easier.

### Render + Atlas production constraints

Render is for a lightweight API, not AI workloads or durable file storage. Do not install/execute PyTorch, DINOv2 inference/training, classifier feature extraction, or bulk image operations on the free service. Use MongoDB Atlas for structured records. Never reset Atlas or write secrets in source control. Since Render's local filesystem is ephemeral, do not promise that uploaded image bytes stored on disk will persist; retain field photos on device unless a separate durable object-storage solution is explicitly approved. Keep the backend health/auth/sync endpoints compatible with the Android app, and keep AI inference fully on-device.
---

## 15. Client-Test APK and Download/QR Handling

Build the updated client-test APK only after the dataset, evaluation, model-export parity, Android tests, and offline inference checks pass. The APK must bundle the new compatible model assets and canonical label mapping.

Do not assume the old hosted download URL remains available. Do not edit or regenerate an existing QR as part of this dataset/model migration unless the task explicitly requires it. First inspect and decode the actual QR target if a download-page change is later authorized. Do not invent a new production URL or claim a download page is live before it is deployed and tested.

Provide the APK path, SHA-256, size, app version name/code, model asset files/checksums, and signing status. Preserve existing APK/release files instead of overwriting them without a backup. Never publish a keystore, signing password, raw dataset, or private images alongside the APK. If the repo has a release publishing convention, follow it only after all gates pass.
---

## 16. Local Development Defaults and Environment Configuration

Android emulator:

```text
http://10.0.2.2:8000
```

Local host:

```text
http://localhost:8000
```

Production candidate: the confirmed Render service URL, supplied through the existing build/configuration mechanism. Do not use the expired VPS hostname/IP as a production default and do not invent a Render URL before it exists.

Do not hardcode personal LAN IPs. Keep development/production base URL configurable using the repository's existing Gradle/build configuration and server-settings flow. Before saving a manual URL, confirm the GEO Tree health endpoint. Release builds should use HTTPS.

Keep local Docker support and tests functional after adding hosted configuration.

---

## 17. Testing Standards

Run and report actual command results. Re-run the current suites before and after significant refactors.

### Backend

- authentication and roles
- legacy user migration compatibility
- role-protected admin APIs
- tree CRUD/sync/idempotency
- image validation, normalization, storage, authorization, deletion
- tags/metadata CRUD
- classifier CRUD and dataset-image operations
- duplicate image handling
- DINOv2 cache reuse
- model training job coalescing/locking
- rollback if training fails
- AI model-not-ready/offline behavior
- assessment creation/history/detail
- multiple assessments per tree
- history ordering and preservation
- assessment sync idempotency
- analytics use real data
- web route/API contracts

### Android

- existing geotagging/map/navigation/offline tests
- login/session handling by role
- field tester navigation
- leaf assessment result/history flow
- multiple scans create multiple records
- local history remains available offline
- sync retry/idempotency
- Room migrations
- release base URL configuration

### Web

- production build
- login/logout
- protected routes
- API role enforcement (not only UI restrictions)
- dashboards show real values
- CRUD workflows
- image upload/preview/delete
- classifier management and model status
- user/account management
- error/loading/empty states
- responsive layout and basic accessibility

### Deployment smoke test

- health endpoint over HTTPS
- login and authenticated endpoint
- web admin loads
- image upload persists
- AI inference works if enabled
- model/job status is accurate
- assessment history persists and synchronizes
- APK download route serves the expected file/checksum
- GEO Tree containers use only GEO Tree storage
- Museum API and existing Museum containers remain healthy

Do not start expensive training or TripoSR simply to test resource limits.

---

## 18. Definition of Done

### Web administration

- responsive and accessible
- authenticated and server-authorized by roles
- real dashboard metrics
- tree/image/tag metadata management based on actual schemas
- account/role management with safe migration
- classifier class and training-image CRUD
- model/job/version status and controls
- assessment history review
- no sample data presented as production data

### Android field app

- administrative features are moved only after web parity is verified
- existing geotagging/map/navigation/offline behavior remains intact
- field image and tagging workflow works
- leaf assessment remains available
- result and per-tree assessment history work
- offline history survives backend outages
- APK builds and uses the appropriate configured API URL

### AI/data

- DINOv2 ViT-S/14 remains the default backbone
- cached features are reused
- training jobs are controlled and versioned
- previous active model survives a failed train
- training and assessment photos remain separated
- user-facing confidence/model version is stored per assessment

### Render and MongoDB Atlas (current target)

- Render runs only lightweight FastAPI routes and starts with the correct `$PORT`
- MongoDB Atlas stores structured application data and survives deploys/restarts
- No AI training, DINOv2 runtime, caches, or persistent user uploads rely on Render's local filesystem
- required secrets are configured outside source control
- Android inference works from the bundled model while offline
- local Docker training/export environment remains functional
- local and hosted sync contracts are compatible and idempotent
- data replacement has a verified rollback archive
- Museum source, data, containers, and deployments remain untouched

### QR/download

- existing QR destination is verified and preserved
- stable route serves the latest APK
- per-release SHA-256 checksum is provided
- no new QR is generated unless the user separately approves it

---

## 19. Out of Scope Unless Explicitly Approved

- replacing DINOv2 ViT-S/14
- full-backbone fine-tuning
- Qdrant for GEO Tree
- OpenCV disease recognition
- automatically promoting assessment photos into training
- removing working Android field features
- modifying Museum source/database/Qdrant/uploads/model caches
- disabling Museum TripoSR or 3D functions
- global Docker cleanup
- database deletion/reinitialization
- DNS changes unrelated to GEO Tree
- QR regeneration
- production deployment steps that may interrupt Museum availability

---

## 20. Required Engineering Report

For every substantial task, report:

1. Initial state and branch/commit/working-tree status.
2. Architecture decisions and why.
3. Files changed.
4. Database/API migrations.
5. Tests actually run and exact results.
6. Manual/emulator/device checks actually performed.
7. Hosting resources measured, not guessed.
8. Museum health checks before/after deployment if production was touched.
9. Security/storage risks.
10. Rollback procedure.
11. Known limitations and next safest step.

Never claim completion for untested functionality.


---

## 21. Dataset ZIP Replacement and Provenance

The expected input archive is `data.zip` in the repository root. If absent, inventory root ZIP candidates such as `data-zip.zip`; never choose an unrelated ZIP or download unknown content automatically.

Before extraction, record the archive's size, modified time, SHA-256, full member listing, folder structure, compressed/uncompressed totals, and image formats. Validate archive paths against path traversal and extract to a staging directory outside the active dataset. Recognize every labeled folder under `Annah Kyla Part 1`, `Annah Kyla Part 2`, and any other included source part. Never inspect only one part.

Canonical labels:

- `Healthy` → `Healthy`
- `Spotting` → `Spotting`
- source `Mold` → `Powdery Mildew` (user-authorized remapping for this new dataset)

Keep a CSV/JSON manifest linking each source archive entry to original part/folder, canonical class, dimensions, file size, SHA-256, duplicate family, validation status, and train/validation/test split. Preserve unexpected labels in staging and report them instead of guessing their mapping. Validate representative samples for obvious label contamination; describe uncertainty honestly.

Create rollback copies of the current active dataset, relevant classifier image/class metadata, feature cache, model artifacts, and Android bundle before any replacement. Verify backups are readable. Replace only the active classifier training set and strictly dependent artifacts. Do not delete tree/assessment uploads, account/tree/assessment records, offline map assets, or unrelated data. The active dataset must contain the ZIP's approved images only; stale DB entries/directories/cache manifests must not cause old training images to be discovered. Recompute features when cache compatibility cannot be proven.

## 22. Split Hygiene and Model Evaluation

- Deduplicate using SHA-256; use perceptual hashing or embedding similarity as a review signal for near-duplicates. Do not automatically delete distinct images solely due to similarity.
- Keep captures of the same leaf/session, burst shots, and resized/cropped copies in one split. Use a deterministic seed and group-aware stratification where possible. Ensure every class is represented in validation/test; document any unavoidable limitation.
- Never use training images for the reported final test metrics. Keep the test split locked while selecting the model. If there is no independent test collection, call results an internal split estimate, not independent field validation.
- Prefer balanced accuracy and macro/per-class precision, recall, F1, support, and a confusion matrix, alongside total accuracy. Store split IDs and exact counts. Compare a baseline only on the same held-out images.
- Do not claim 95% or any other accuracy for the new model until measured on the new dataset with its documented split. Do not guarantee perfect accuracy.

## 23. Mobile Model Bundle and Offline Acceptance Gate

Use the existing DINOv2 ViT-S/14 frozen feature extractor and classifier-head pipeline unless a measured reason and explicit approval justify changing it. Use the project's verified mobile exporter (expected: `backend/scripts/export_mobile_model.py`) and actual model asset names/path discovered in `LeafModelStore` and `OnDeviceLeafClassifier`.

The exported bundle must use the exact class order `Healthy`, `Spotting`, `Powdery Mildew`, correct preprocessing, compatible model/cache key, model version, and checksums. Python/ONNX parity and Android model parity must pass. Include the bundle in the APK and verify APK contents and sizes.

Required real acceptance check: enable airplane mode, launch the app, classify a new image stored on-device, verify the model produces a prediction without contacting the API, save the assessment locally, close/reopen as appropriate, and verify the local record/history persists. Reconnect and confirm sync is idempotent. Clearly state whether this was tested on a physical device or an emulator. Never treat an ordinary backend health check as proof of offline inference.

## 24. GitHub, Render, and Release Hygiene

Push relevant tested source changes and mobile model assets required for reproducible APK builds to the verified `origin` and current/intended branch only after reviewing the diff and passing required gates. Never force-push. Check if pushing will trigger Render deployment and ensure the change is not destructive.

Keep raw `data.zip`, extracted image datasets, staging/manifest files with private metadata, local AI caches, recovery archives, Mongo dumps, uploaded personal/tree/assessment photos, `.env`, Atlas/JWT credentials, SSH keys, and Android signing material out of Git by default. If the ZIP is too large, private, or not licensed for redistribution, leave it local and report that explicitly. A complete app release does not require hosting the training ZIP on Render or embedding it in GitHub: only the derived model bundle belongs in the APK.

Use the lightweight base API dependencies in the Render service. Do not include local AI/training dependencies or mount model files in Render. Configure `MONGO_URI`, `MONGO_DATABASE=geo_tree`, `JWT_SECRET`, and production settings via Render secret/environment settings; never write secrets in `render.yaml`. Keep startup migrations/idempotent indexes non-destructive and test with a non-production DB or safe read-only checks. Preserve user data across deploys.

Provider references to re-check before deployment:
- Render free services: https://render.com/docs/free
- Render deployment guide: https://render.com/docs/your-first-deploy
- MongoDB Atlas free cluster limits: https://www.mongodb.com/docs/atlas/reference/free-shared-limitations/

## 25. Definition of Done for the Current Milestone

The task is complete only when the report proves:

1. The input ZIP was identified by path/hash and fully inventoried.
2. The active training dataset has been replaced with the verified ZIP images, with canonical labels and source provenance recorded.
3. Existing local data was backed up; unrelated GEO Tree records/photos remained intact.
4. Cache and model artifacts were regenerated for the new dataset; stale old training data is not included in active loading.
5. Evaluation metrics, per-class results, confusion matrix, split method, duplicates, and limitations were recorded.
6. The mobile model was exported, parity checks passed, and the APK contains the right model files and class order.
7. Offline inference and local assessment saving passed a real device/emulator test, with the test environment described accurately.
8. Relevant backend/Android tests and build tasks passed, with exact results reported.
9. The Render config is lightweight and Atlas-backed with secrets kept out of code; any live deployment is clearly distinguished from locally prepared configuration.
10. Relevant changes were pushed safely to `origin` after tests, or an exact blocker was reported. Raw dataset/secrets/PII were not published by default.
11. No Museum project resource was accessed or changed.
