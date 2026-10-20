# GEO Tree — Project Engineering Charter

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

### Existing hosted environment

The existing Museum App uses the domain `museuma7k9x3.tech` and its API at `api.museuma7k9x3.tech`. The prior local audit reported a Hostinger VPS, Caddy reverse proxy, a Docker Compose project named `museum_app_prod`, source/deployment files under `/opt/museum-app/`, and approximately 8 GB RAM. These are **previously reported facts, not proof of the live server's current state**. Re-inspect the live VPS read-only before a production change.

A prior audit also reported that the Museum's TripoSR workload reached approximately 7.7 GB RAM at peak. Treat shared memory as a serious risk. Do not stop, disable, reconfigure, or alter Museum TripoSR or other Museum resources without explicit approval for that exact action.

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
- Configurable classes/labels such as `Healthy`, `Spotting`, and `Mold`
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
- The hosted deployment `https://geotree.museuma7k9x3.tech` is live (verified 2026-10-12: HTTPS health OK). Use it for non-destructive integration checks: health, authentication with an authorized account, role permissions, reading records/model status, and sync-contract compatibility. Do not create, modify, or delete production records for testing without a designated test resource or explicit approval, and do not run training or bulk inference against it.
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
                         GEO Tree Users
                               |
                 +-------------+--------------+
                 |                            |
          Web Administration           Android Field App
          React + TypeScript           Kotlin + Compose
                 |                            |
                 +-------------+--------------+
                               |
                      HTTPS GEO Tree API
                            FastAPI
                               |
                +--------------+---------------+
                |              |               |
              MongoDB     persistent files   private AI service
                |          and model data      DINOv2
                +--------------+---------------+
                               |
                        leaf assessment history
```

The browser and Android app use the same versioned API and authorization rules. The Android app must not call the AI service directly. The AI service and MongoDB remain private/internal, not public internet endpoints.

### Preferred deployment shape

Where confirmed compatible with the live Hostinger environment:

- Existing Museum: remains in its existing project and resources.
- GEO Tree: separate directory, Compose project, containers, database, volumes, network, environment file, and logs.
- Existing Caddy: reused as the single public HTTPS reverse proxy with only an additive GEO Tree route.
- One proposed GEO Tree hostname: `geotree.museuma7k9x3.tech`.
- Proposed Android production API base: `https://geotree.museuma7k9x3.tech`.
- Proposed stable download page: `https://geotree.museuma7k9x3.tech/download`.

These hostnames are proposals until DNS, existing routes, TLS, and live server configuration are verified. Do not state that they are live before testing them.

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

Do not confuse generic record tags with classifier classes. For example, `Healthy`, `Spotting`, and `Mold` are classifier labels; a tag such as `PSAU field`, `survey round 1`, or a tree-category label is a different concept unless the existing model explicitly defines it otherwise.

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

Classifier classes are labels, not separate DINOv2 models. Initial examples are `Healthy`, `Spotting`, and `Mold`, but classes should remain configurable.

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

## 14. Hostinger Migration and Museum Isolation

### Protected resources

The existing Museum App is protected production infrastructure. Previously reported details include:

- local source: `C:\Capstone\Museum_App`
- hosted root: `/opt/museum-app/`
- deployment directory: `/opt/museum-app/deploy`
- Compose project: `museum_app_prod`
- Museum API: `api.museuma7k9x3.tech`
- Caddy container reported as `museum-guide-caddy-prod`
- Museum backend, MongoDB, Qdrant, uploads, OpenCLIP storage, TripoSR worker/jobs/cache

Verify the actual live names read-only; do not assume all names/configurations are still current.

**Never**:

- deploy GEO Tree inside `/opt/museum-app/`
- overwrite Museum source or deployment files
- reuse Museum MongoDB/Qdrant databases, volumes, credentials, uploads, or model caches
- stop/restart/rebuild Museum containers as part of GEO Tree deployment
- run Museum deployment scripts
- change the existing `api.museuma7k9x3.tech` route
- run global Docker prune/cleanup
- modify/disable TripoSR without separate explicit approval

### GEO Tree isolation

Preferred directory:

```text
/opt/geotree/
  deploy/
  backend/ and/or app source
  web/ build artifacts as required
```

Use a separate Compose project and resources with unique names, for example:

```text
project: geo_tree_prod
containers: geo-tree-api, geo-tree-ai, geo-tree-mongodb, geo-tree-web (if a static web container is used)
network: geo-tree-network
volumes: geo_tree_mongodb_data, geo_tree_uploads, geo_tree_ai_data
```

These names are suggestions. Check conflicts first. MongoDB must be internal-only. AI must be private/internal-only. Only the API and/or static web entry should be reachable through Caddy.

A dedicated GEO Tree network must not be used by Museum services except for the minimum proxy connectivity needed by the existing Caddy. If Caddy needs network attachment, inspect the live topology and choose the lowest-risk additive method. Do not recreate the Museum Compose stack just to attach GEO Tree.

### Caddy/DNS/TLS

The existing Caddy instance owns public ports 80/443. Do not launch another proxy on those ports.

Potential hostname:

```text
geotree.museuma7k9x3.tech
```

Potential layout:

- `https://geotree.museuma7k9x3.tech/` → web admin static frontend
- `https://geotree.museuma7k9x3.tech/api/v1/...` → GEO Tree FastAPI
- `https://geotree.museuma7k9x3.tech/download` → stable download page
- the private AI service is never routed publicly

This is only a target until live config, DNS, and TLS are verified. Keep `api.museuma7k9x3.tech` for the Museum. Add GEO Tree routing additively; back up the active Caddy configuration, validate syntax, make the smallest route-only change, and reload rather than restarting the Museum stack if the current Caddy deployment supports a safe reload. Check Museum health before and after. If a safe reload/route change cannot be established, stop and report the blocker.

SSH access does not imply DNS-panel/API access. If DNS cannot be safely edited from the available environment, provide the exact required DNS record and do not claim it is live.

### Host resource policy

The VPS has been reported at roughly 8 GB RAM. A Museum TripoSR workload previously reached roughly 7.7 GB at peak. Re-check actual current resource usage before AI enablement.

- Do not infer current spare memory from total RAM.
- Do not launch DINOv2 training during a heavy Museum job unless safe coexistence has been demonstrated.
- Keep a single AI model process where practical; avoid multiple Uvicorn workers duplicating model memory.
- Permit only one classifier training job at a time.
- Check memory/disk before training and uploads.
- If insufficient resources are available, do not risk the Museum. Keep training disabled/deferred and report the required choice: local training + hosted inference, an idle-time guarded job, or a separate AI host.
- Do not disable the Museum's 3D capability automatically just because it is less frequently used.

### Production data persistence

Persistent storage must cover:

- GEO Tree MongoDB data
- tree-registration images
- leaf-assessment images
- classifier training images
- cached DINOv2 features
- model versions/artifacts
- job metadata/logs needed for diagnostics

Use named volumes or another verified persistent Hostinger path. Do not rely only on writable container layers. Verify persistence after a GEO Tree-only container restart/recreation and, when feasible, a VPS reboot. Never delete data volumes during routine deploys.

### Deployment phases

1. Local read-only audit and tests.
2. Local web/API integration and tests.
3. Hostinger read-only preflight.
4. Backup GEO Tree deployment assets and the active Caddy configuration before a route change.
5. Create only GEO Tree directories, Compose resources, volumes, and network.
6. Deploy and verify GEO Tree API/web/DB health.
7. Add the minimal Caddy route and verify TLS/host routing.
8. Verify Museum health and containers again.
9. Verify persistent uploads, model artifacts, assessment history, and sync.
10. Publish an APK only after the production API URL works.

If any step threatens Museum availability or data, stop before that step and report the reason.

---

## 15. Stable APK Download and QR Code

The existing QR must not be regenerated as part of this migration.

First locate the actual QR asset/link and decode/verify its current destination. Do not assume that a proposed URL is already encoded by it.

- If the current QR points to a stable URL, preserve that exact URL and make that route serve the current GEO Tree APK.
- If it points to a version-specific APK path, preserve the encoded URL and create a safe compatibility/alias route to the latest release where possible.
- If no QR asset or destination can be found, stop and report this. Do not pretend a new target preserves the old QR.

Proposed new stable URL, **only if consistent with the existing QR or explicitly approved by the user**:

```text
https://geotree.museuma7k9x3.tech/download
```

The URL must resolve to a download page that always links to the current APK. Versioned APK filenames may change behind that page; the QR URL must not contain a version number. Do not change the QR for each release.

Important distinction:

- The QR encodes a URL.
- The APK SHA-256 checksum verifies a particular APK file.
- A new APK normally has a new SHA-256 checksum; this does not require changing the QR.

Keep GEO Tree release files separate from the Museum APK/distribution directory. Show the current app version, release notes, APK download, SHA-256 checksum, and minimum Android version where known. Verify the actual download in a browser/HTTP client before reporting success.

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

Production candidate:

```text
https://geotree.museuma7k9x3.tech
```

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

### Hostinger

- GEO Tree has a separate directory, Compose project, containers, network, volumes, database, and environment
- MongoDB and AI are private
- Caddy route is additive
- Museum source, data, containers, and existing API route are not repurposed
- memory/disk constraints are verified
- uploads/models/history persist across GEO Tree container recreation
- actual Museum health was checked before and after
- rollback affects GEO Tree only

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
