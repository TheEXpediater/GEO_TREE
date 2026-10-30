# GEO Tree Engineering Charter

## 0. Current override (2026-10-24, later the same day): production is Render + MongoDB Atlas

**This section wins over every Hostinger statement below (Sections 1-11 and the Hostinger parts of Section 12).** Hostinger has expired and the VPS is unreachable: do not renew, access, back up or deploy to it. The owner's checklist for this state is the "Restore Render + MongoDB Atlas" prompt of 2026-10-24.

- **Production API:** the existing Render service `https://geo-tree.onrender.com` (verified 2026-10-24: `/api/v1/health` -> `database: ok`, `ai: disabled`, ~32 s cold start). Lightweight FastAPI only (`render.yaml`); secrets (`MONGO_URI`, `JWT_SECRET`) live in the Render dashboard, never in Git, the APK or the web build.
- **Data:** MongoDB Atlas (structured records only), reached only through FastAPI. Never reset Atlas or delete accounts/trees/assessments.
- **Local Docker:** development, DINOv2 features, training, ONNX export, full tests, and the web admin with real AI data.
- **Android:** release builds default to the Render URL; debug builds to `http://10.0.2.2:8000`. `verifyReleaseBaseUrl` rejects local/LAN, non-HTTPS and retired Hostinger hosts. Leaf inference stays on-device from the bundled DINOv2 ViT-S/14 + head (classes Healthy, Spotting, Powdery Mildew).
- **No durable files on Render:** no photo bytes (`IMAGE_STORAGE=disabled`), no dataset archives (`DATASET_ARCHIVE_DIR` is ignored while `RENDER=true` unless `DATASET_ARCHIVE_PERSISTENT=true` marks a real persistent disk), no APK (`DISTRIBUTION_DIR` unset, so `/download` answers 404). Hosting the APK needs a separately approved durable location (e.g. GitHub Releases, object storage or a Render disk).
- **QR:** the existing QR encodes `https://geotree.museuma7k9x3.tech/download` (dead VPS; domain on Hostinger `dns-parking` nameservers). It works again only if the owner keeps that domain and points it at a verified download route. Do not generate a replacement QR until a new download URL serves the correct signed APK; keep the old QR image.
- **Mobile Settings** (web `/mobile`, API `/api/v1/admin/mobile/*`) is kept: it works fully on local Docker; on Render it shows release/download/archives as not available until those storage decisions are made. The web admin is not part of the Render build (`backend/web_dist` is gitignored).
- Do not commit, push or deploy without separate authorization. Museum isolation rules (Appendix A.14) remain mandatory.

---

## Superseded for hosting: owner's Hostinger charter (2026-10-24, earlier)

**Revision:** 2026-10-24  
**Production direction:** Hostinger VPS for GEO Tree  
**Repository:** `C:\CAPSTONE.2` (verify before acting)

This charter supersedes earlier GEO Tree instructions that selected Render + MongoDB Atlas as the production target or prohibited reconnecting to Hostinger. The user has now explicitly authorized returning GEO Tree to its existing Hostinger VPS. Preserve the stable architecture, testing, data safety, and Museum-isolation rules from earlier project documentation unless this charter expressly updates them.

## 1. Mission and scope

GEO Tree is an offline-first Android field-management and tamarind-leaf assessment system with a web administration platform. The requested direction is to:

1. Return GEO Tree's production online services to the existing Hostinger VPS.
2. Make the Android release use the verified Hostinger HTTPS API for online authentication and synchronization while retaining offline-first field workflows and local on-device leaf inference.
3. Transfer and verify the current classifier dataset archive on Hostinger, then support safe administrator uploads of future dataset archives.
4. Add **Mobile Settings** to the web admin menu for APK release/version history, a stable app-download QR code, and an accurate AI model/dataset profile.
5. Build, sign, test, package, publish, and verify the new Android APK at the download target referenced by the existing QR code, preserving that QR's URL whenever technically possible.
6. Preserve existing tree, user, assessment, image, map, and model data. Do not fabricate metrics or claim unperformed tests.

Do not treat this as permission to rewrite the architecture or delete older cloud resources. Make the smallest safe changes that achieve the direction above.

## 2. Hosting target and cutover authority

### 2.1 Hostinger is the intended GEO Tree production target

The historical candidate production URL is `https://geotree.museuma7k9x.tech/`. Verify current DNS, TLS, route behavior, and live health before using it as the APK base URL. Do not assume a historical URL, IP, port, container name, path, or service is still accurate without inspection.

Historical GEO Tree VPS identifiers include `/opt/geotree`, `geo-tree-api`, `geo-tree-mongodb`, and related GEO Tree volumes. Treat these only as discovery hints. Inspect the actual VPS and compose configuration before running commands or modifying resources.

The intended production responsibilities on Hostinger are:

- GEO Tree FastAPI API and web administration service(s) already present in the deployment.
- GEO Tree's production MongoDB and structured account/tree/assessment metadata.
- Persistent GEO Tree tree images, assessment images, classifier training images, archive files, model artifacts, and APK release files as supported by the existing deployment.
- The stable public download endpoint consumed by the existing QR code.
- Dataset validation/import and, only after resource checks, controlled DINOv2 feature extraction and classifier-head training.

The Android app must not connect directly to MongoDB or any private service. It must use the public HTTPS API.

### 2.2 Safe migration and source-of-truth rules

Before switching clients or deploying:

1. Inventory the active Hostinger API, database, persistent volumes, uploads, model artifacts, dataset metadata, current APK, web admin, and QR/download target.
2. Determine where the latest authoritative records currently live. Compare available Hostinger data with any still-existing Render/Atlas data using counts, IDs, timestamps, and hashes where appropriate. Do not assume either environment is complete.
3. Back up the actual GEO Tree database with a restore-tested dump where feasible, plus relevant persistent data, classifier files/artifacts, configuration, APK, and QR/download target. Record paths, timestamps, sizes, and SHA-256 hashes. Keep the backup outside directories that a deploy might overwrite.
4. Do not blindly restore a dump over a live database. Do not reset/drop collections, replace user IDs/password hashes, or overwrite newer records. If data needs merging, make a reversible, documented plan and preserve IDs and idempotency keys.
5. Switch the Android production URL only after the Hostinger HTTPS API and the relevant authentication/tree/sync endpoints pass smoke tests.
6. Do not decommission, wipe, stop, or remove Render/Atlas resources as part of the cutover. Leave them untouched until Hostinger parity and successful operation are proven and a separate explicit authorization exists.

### 2.3 Strict Museum isolation

GEO Tree and the PSAU Agricultural Museum app are separate projects. Museum source, services, databases, Qdrant, uploads, models, containers, domains, Caddy routes, TLS, and data must remain untouched.

The Hostinger VPS may be shared. Inspect resource ownership before operating anything. Do not run global Docker prune/cleanup commands, `docker compose down -v` in production, broad volume deletion, or commands that may stop other projects. If changing a shared reverse proxy, first back up and inspect the existing configuration, add only the GEO Tree change, validate the complete configuration, and reload only when safe. If safe isolation cannot be proven, stop and report the blocker.

## 3. Stable product architecture

### Android field application

- Kotlin + Jetpack Compose.
- ViewModel/StateFlow and repository pattern.
- Room as the local source of truth.
- WorkManager for queued/retryable synchronization.
- FastAPI over HTTPS for online operations.
- Existing authentication/session handling.
- GPS tree registration, tree details, image capture/selection, field metadata, tags, navigation, and assessment history.
- MapLibre and the existing offline MBTiles coverage for PSAU/Magalang.
- Bundled ONNX model for leaf assessment, with inference executed on-device even when no network is available.
- Existing assessment history remains append-only, newest-first, and keyed by unique assessment IDs.

The desired mode is **online through Hostinger when connected, offline-capable when disconnected**. Do not disable offline-first behavior just because Hostinger is the production server. Local tree registration, GPS capture, saved photos, assessment history, offline maps, and on-device predictions must not require an API round trip. Sync should resume when connectivity returns and remain idempotent.

### AI pipeline

- Primary backbone: DINOv2 ViT-S/14, frozen for the current feature pipeline.
- 384-dimensional features and a lightweight supervised classifier head, where confirmed by the actual artifacts.
- Canonical classes: `Healthy`, `Spotting`, and `Powdery Mildew`.
- Source label `Mold` was authorized to map to `Powdery Mildew` for the current dataset. Preserve the mapping as provenance and flag likely label contamination instead of describing it as scientifically confirmed.
- Keep classifier training images, tree-registration images, and leaf-assessment images logically separate. Never promote a field assessment photo into the training set automatically.
- Keep a prior working model active until a new model passes evaluation, export checks, and Android parity checks. Failed training must not break the active model.
- Do not introduce Qdrant for GEO Tree or replace DINOv2 with OpenCV/OpenCLIP as the disease classifier.

### Hosted processing capacity

The user wants GEO Tree's online services and persistent data on Hostinger. Before enabling dataset extraction/training there, inspect actual RAM, CPU, disk capacity, Python/container compatibility, and current VPS utilization. Use a bounded queue with one training job at a time by default. Do not run repeated heavyweight jobs per image upload. If the VPS cannot safely run feature extraction/training, keep the archive and serving responsibilities on Hostinger and report the precise limitation before selecting any alternative. Do not silently shift production back to Render or another cloud.

## 4. Dataset baseline, transfer, and future uploads

### 4.1 Existing archive

The expected archive is `data-sets.zip` in the repository root, but verify the actual path and archive before transfer. Previous inventory reported:

- SHA-256: `82775506a025599e74ed5717ee09786f06446d90158d4c6e77ff36c3365239b7`
- 184 image files: 91 JPG and 93 HEIC.
- Three images excluded from the active split because of duplicate/conflicting content, leaving 181 eligible images.
- Prior split report: 144 images in training + validation and 37 in the locked test set.
- Prior split by class: training/validation Healthy 27, Spotting 31, Powdery Mildew 86; locked test Healthy 7, Spotting 8, Powdery Mildew 22.

These are a **previously reported baseline, not proof of the current files**. Recalculate counts and hash from the actual archive/manifests. If the archive's hash differs, do not automatically reject it, but inventory and reconcile the difference before import. Never silently substitute another ZIP.

### 4.2 Safe transfer and import

- Back up the current active dataset, classifier metadata, feature cache, current model, and relevant database records before modifying active data.
- Transfer the archive privately to a GEO Tree-only persistent directory on Hostinger. Verify its remote SHA-256 matches the local source. The raw archive must not be publicly downloadable or placed inside the APK or public Git repository.
- Inventory every ZIP member, source folder, extension, size, image readability, duplicate hash, label, and path. Guard against path traversal, archive bombs, unsupported files, malformed images, and unrecognized labels.
- Extract to a staging directory, not the active dataset. Validate labels and counts; provide an import preview and explicit commit/activate step for admin-controlled uploads.
- Keep exact duplicates and conflicting-label cases in a review report; do not choose labels by guessing. Preserve source paths and original filenames as metadata.
- Retain an import manifest with archive hash, image hashes, source folder/class, canonical class, validation status, duplicate family, split group, dimensions, and split assignment as applicable.
- Only after validation and rollback proof should a new dataset version become active. Inactive/older dataset versions and rollback artifacts must remain recoverable.
- Dataset upload must not remove or rewrite tree records, user accounts, tree images, assessment records/images, map assets, or unrelated files.
- Enforce admin authorization, upload-size limits, image/content checks, safe file IDs, deduplication, job progress, error states, and audit metadata. Never trust client filenames as server paths.

### 4.3 Honest model accuracy reporting

A previous report stated:

- Validation accuracy: 95.0% on 20 images.
- Locked-test accuracy: 34/37 = 91.9%.
- The locked test came from the same photo sessions as training and may be optimistic.
- Cross-source folder evaluation fell to approximately 0.49–0.71 accuracy, with possible background confounding.

Do not show “95% accuracy” without specifying the evaluation set and sample count. Re-open the current evaluation/manifests and use the metric matching the model/version being displayed. If the current release bundle cannot be linked to that evaluation, label the result as the previous reported evaluation or “not verified for this APK”. The admin page should show evaluation type, sample count, date/model version, and known limitations. Never invent or inflate metrics.

## 5. Web admin: Mobile Settings / release management

Add a clearly named **Mobile Settings** item to the existing authenticated admin menu using the current web stack and design system. Inspect existing routes/components before adding new ones. This is a web-admin feature; do not add mobile-only admin screens back to Android.

The page should be backed by real persisted release/model/dataset metadata and server responses, not static mock values.

### 5.1 Current APK profile

Show, where verified:

- Current/published app version name and version code.
- Release status and release timestamp (do not invent the original release date).
- APK filename, byte size, SHA-256, package/application ID, signing status/certificate fingerprint, and minimum supported Android version if available from the artifact.
- A working public HTTPS download URL.
- Release notes and compatibility notes.
- The model bundle version/checksum included in that exact APK, if the build manifest permits it.

The APK actually served by the download route must match the stored SHA-256 and the currently published release record. If not, flag an error and do not label it current.

### 5.2 Version history starting now

Persist a release record for each newly published version, including version name/code, creation/publish timestamp, APK size/hash, download route, release notes, model version and dataset version, and actor where audit logging exists.

Begin trustworthy release tracking now. Do not fabricate historical releases or their dates. If the existing 0.8.1 APK is captured as the initial baseline, mark it as a baseline record with tracking-start timestamp and state that its original publication timestamp is unknown unless verified. Future releases should be listed newest-first with status (draft, published, superseded, failed, or rolled back, as appropriate).

Protect release upload/publish/rollback actions with server-side admin authorization. Do not allow a normal Field Tester to publish an APK or edit model/dataset metadata.

### 5.3 Existing QR code and stable download target

- Locate the **actual current QR code** in the existing admin UI/assets/deployed page and decode its embedded destination. Do not guess the route or generate a random new URL.
- Prefer preserving the exact existing QR image and encoded URL. If it points directly to a stable APK file path, replace that file at the same path only after backing it up. If it points to a stable download/landing endpoint, update the release behind that endpoint to the latest verified APK.
- A QR code encoding an old direct-file URL cannot be changed just by replacing its image in the admin UI; the exact encoded URL must continue serving the updated APK or redirect safely to the new download. Maintain compatibility at the old URL when possible.
- Show the QR in Mobile Settings with the exact decoded target, a copy-link action, and an “open/test download” link. Verify the QR decodes to the displayed URL and the downloaded bytes hash to the current APK record.
- Do not expose private datasets, internal paths, signed credentials, or secret query parameters in the QR URL.

### 5.4 AI model profile

Display the model that is actually active and/or bundled in the current APK, clearly distinguishing those two states if they differ:

- Backbone: DINOv2 ViT-S/14, only if confirmed from actual artifacts.
- Classifier architecture/head and its version.
- Number of classifier **classes**: expected 3, with names `Healthy`, `Spotting`, and `Powdery Mildew` after verification.
- Current active server model version, bundled mobile model version, and checksums where available.
- Inference location: on-device / offline for the released mobile bundle; training location/status separately.
- Dataset version associated with the model.
- Evaluation metrics with evaluation type, count, date, per-class metrics/confusion matrix when available, and limitations.

Use terms precisely: there is one DINOv2-based classification pipeline/head with three classes, not “three DINOv2 models” unless the repository proves that architecture. Do not imply a new training dataset immediately updates models already embedded in existing APKs. A retrained model requires export/parity checks and a subsequent APK release unless the existing supported update mechanism is proven to safely deliver compatible head updates.

### 5.5 Dataset profile and upload controls

Show archive/dataset versions and reliable counts:

- Archive filename/hash/size and import timestamp.
- Total archive images, accepted active images, rejected/excluded files, exact duplicates, and unreadable/unknown-label counts.
- Counts per canonical class, with training/validation/test counts separated from total class counts.
- Split method/grouping, manifest link/download for authorized admins, import status, model-training status, and model version produced from the dataset.
- Keep “archive files”, “active training dataset images”, “validation/test images”, and “field assessment images” as separate metrics.

Provide authenticated admin upload for future dataset archives. Validate and stage first; show a review summary and require explicit confirmation before activation/retraining. Preserve rollback. Do not allow dataset upload to bypass validation or silently start multiple simultaneous training jobs.

## 6. API, database, and security

- Inspect existing API routes and schemas first; reuse current business logic and data structures where safe.
- Keep JWT/session validation and server-side role checks. UI hiding is not authorization.
- Use the existing password hashing and user IDs. Do not reset accounts or seed/change production users unless explicitly necessary and authorized.
- Keep database URI, JWT secret, SSH credentials, signing keys, tokens, and passwords out of source control, APK assets, reports, and UI logs.
- Add bounded pagination, upload validation, deterministic IDs, idempotency, structured errors, and audit events for release/dataset activation where supported.
- Make schema migrations additive/backwards-compatible where practical. Back up before migrations and include rollback.
- The APK must never contain MongoDB credentials or connect directly to the database.
- Keep public access limited to the intended APK/download landing route and other intentionally public resources. Protect admin pages, dataset archives, training images, model-management routes, and user records.

## 7. Android API configuration and release versioning

- Production release should use the verified Hostinger HTTPS API URL, expected to be `https://geotree.museuma7k9x.tech` only after live verification.
- Development configuration may retain `http://10.0.2.2:8000` for emulators, `http://localhost:8000` for host development, and an explicitly configured LAN address for physical-device development. Do not put personal LAN addresses in production builds.
- Add/retain a build-time check that rejects local, private-LAN, or plain-HTTP production base URLs.
- Inspect the current Gradle version and increment it. Historical current release is 0.8.1/versionCode 12, so use 0.8.2/versionCode 13 only if the actual checkout has not advanced. Never reuse a version code.
- Preserve `com.geotree.app` and the established release signing certificate. Do not generate a new signing key or expose signing material. If credentials are unavailable, do not produce a fake “signed release”; report the blocker.
- Build a signed, non-debuggable release APK and test upgrade installation over the existing signed version without uninstalling or clearing data.
- Bundle the correct ONNX model, class order, preprocessing metadata, version/checksum and inference code; verify that the APK contains the expected assets.
- Preserve local Room data and map assets. Offline assessment and map use must continue even if the VPS is unavailable.

## 8. Required tests and deployment gates

Run appropriate current-checkout tests, not historical counts alone.

### Backend/database

- Health, sign-in, protected endpoint, role authorization.
- Tree create/read/sync with stable IDs and idempotency.
- Assessment history create/read/sync and no duplicates.
- Persistence after container/service restart.
- Dataset archive validation, staging, deduplication, label mapping, import/rollback.
- Dataset/AI model metadata and version registry.
- APK publish/download permissions and correct bytes/hash.
- Mobile Settings API authorization and real-data UI behavior.

### Android

- Release configuration points to Hostinger HTTPS.
- Login/token handling and sync using a valid account only if authorized credentials are available.
- Offline tree registration, GPS, photo persistence, map, local assessment, history, force-stop/restart.
- Reconnection sync is idempotent and does not erase local data on failure.
- ONNX prediction works in airplane mode on a stored test image.
- App version/model metadata matches the release record.
- Upgrade install, signature continuity, manifest/package/version, non-debuggable release.

### Web and hosting

- Production web build/tests.
- Mobile Settings page with current release, release history, QR/download, model profile, dataset profile.
- QR target is decoded and verified against the downloaded APK SHA-256.
- Hostinger health/TLS and the real login/tree/sync endpoints.
- Data and files persist after a controlled GEO Tree service restart.
- Confirm only GEO Tree resources were modified. Do not touch Museum resources.

Do not claim any test passed unless it ran. Label emulator-only and physical-device results accurately. A health endpoint alone is not proof that authentication or syncing works.

## 9. Backups, deploy, and rollback

Before touching production, back up:

- GEO Tree MongoDB data using the existing supported backup method.
- GEO Tree persistent uploads, classifier datasets, AI artifacts, releases, and relevant configuration.
- Current APK and ZIP, current QR image/page/route, and current web/API deployment configuration.
- Current CLAUDE.md and relevant repository changes if updating the charter.

Verify backup readability and record hashes. Deploy only the GEO Tree service and web/API assets needed. Validate the exact GEO Tree compose project/service names and volumes before using them. Avoid global Docker cleanup or shared-service restarts. After deploy, test the existing QR URL and verify the served APK hash. Keep the old APK and previous model version available for rollback. If a release fails, restore the previous APK target and compatible service/config version without deleting new field data.

Do not automatically commit or push to GitHub unless the user separately directs it. Never put `data-sets.zip`, extracted datasets, private photos, database dumps, `.env`, keys, keystores, or secrets in a public repository.

## 10. Required deliverables

When carrying out a release task, provide as applicable:

- New signed APK named `geotree-<version>.apk` in `C:\CAPSTONE.2`.
- ZIP named `geotree-<version>.zip` containing the APK only unless the user explicitly asks otherwise.
- APK and ZIP byte sizes and SHA-256 hashes; verify the APK inside the ZIP hashes identically to the standalone APK.
- Application ID, version name/code, signing certificate fingerprint, non-debuggable status, and bundled model asset/hash.
- Verified Hostinger API URL and exact stable QR/download URL.
- Dataset archive remote path, size/hash, accepted/excluded/class counts, manifest path, and rollback path.
- Mobile Settings features, the source of each model/dataset metric, tests actually run, deployment status, and any unverified items.
- A rollback procedure and any deployment blockers.

Do not claim that an APK has been built, files transferred, a service deployed, a QR tested, or data migrated unless the operation actually succeeded and was verified.

## 11. Definition of done

The work is complete only when the Hostinger production target is verified; database and dataset backups are usable; the selected source data is preserved; the dataset archive has a matching local/remote checksum; the web admin displays real release/version/model/dataset information; the existing QR URL resolves to the newly verified APK; the new signed APK targets Hostinger online while preserving offline field operations and on-device inference; the app installs over the prior version; relevant tests pass; and the final report clearly states all untested items. Museum resources must remain untouched.

## 12. Verification log (2026-10-24, measured from the development PC)

These are measurements, not assumptions. Re-check before acting; update this log when they change.

- `geotree.museuma7k9x.tech` (the candidate URL in Sections 2.1 and 7) **does not resolve in DNS**. The historically working host is `geotree.museuma7k9x3.tech` (note the `3`), A record `72.61.120.104`.
- `72.61.120.104` answered no ping and no TCP connection on ports 22, 80 or 443 (all timed out). Hostinger is therefore **not verified**: no backup, transfer, deploy, QR test or Hostinger-targeted release can be performed until the VPS is reachable again.
- **QR decoded:** `tools/distribution/release/geotree_download_qr.png` (gitignored; SHA-256 `c907008e…8904`) decodes to exactly `https://geotree.museuma7k9x3.tech/download`, a stable landing page whose APK is `/download/GEO_Tree.apk`. Keep that URL; publish new APKs behind it; do not regenerate the QR. `C:\Users\Admin\Downloads\museum_app_download_qr.png` (2026-09-23) predates GEO Tree hosting and belongs to the Museum App: do not use or modify it.
- Last VPS state recovered read-only on 2026-10-20: `C:\GEO_TREE_RECOVERY\2026-10-20` (database dump, volumes, `/opt/geotree` minus secrets, distribution folder serving APK 0.7.0 sha `0d31a154…16c1`).
- Android: `geotree.museuma7k9x3.tech` stays in `BackendConfig.RETIRED_HOSTS` (a saved VPS address is dropped at startup) and Gradle's `verifyReleaseBaseUrl` refuses it for releases; release builds default to Render (Section 0).
- Model bundled in 0.8.x is **v3** (head `4ddbdc65…`, ONNX `58f899dc…`). Its own metrics: validation 93.1% (27/29, internal split) and locked test 91.9% (34/37, same photo sessions). The "95.0% on 20" figure in Section 4.3 belongs to the earlier hosted v1/v2 models and must not be shown for v3.

---

# Appendix A: Preserved engineering rules (from the 2026-10-20 charter)

Carried over unchanged except for heading levels and removal of Render/Atlas hosting text. The full previous charter is backed up at `C:\GEO_TREE_RECOVERY\hostinger-restore-2026-10-24\backup_before\CLAUDE.md.charter-2026-10-20`. **Where this appendix and Sections 1–12 above conflict, Sections 1–12 win.** Section numbers keep the old numbering (A.n = old Section n).

### A.2 Stable Baseline — Preserve These Features

The following behavior is the stable baseline. Do not redesign, replace, or remove it merely for stylistic reasons, to introduce a newer framework, or to simplify the code.

#### Android and field operations

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

#### AI and assessments

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

#### Local development

- Root `compose.yaml` runs local GEO Tree services.
- Emulator API default: `http://10.0.2.2:8000`.
- Local host API: `http://localhost:8000`.
- Physical phone: laptop LAN IP and backend port, after firewall/network validation.
- Local Docker is an optional development environment, not a prerequisite for every test. It is needed only for work that requires the server's PyTorch/DINOv2 runtime (e.g. local training, `backend/scripts/export_mobile_model.py`).
- Hosted target: see the Hostinger charter above (verify the host before use; never assume a historical URL is live).
- Android tools: `adb` is not on PATH; use `<sdk.dir from local.properties>\platform-tools\adb.exe`. A physical-device claim requires a device listed as `device` by `adb devices -l`; the `Medium_Phone` emulator is for preliminary checks only.

### A.3 Engineering Principles

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

### A.5 Product Responsibility Split

#### 5.1 Web Administration Platform

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

#### 5.2 Android Field Tester Application

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

### A.6 Web Technology and UX

Preferred starting point:

- React
- TypeScript
- Vite for a static frontend build unless repository constraints justify a different option
- Tailwind CSS
- A maintained accessible component library only if useful
- A chart library only where it supports meaningful analytics

A static frontend is preferred for deployment simplicity and lower persistent server load. Evaluate existing code before committing to this stack. Do not introduce Next.js server rendering if a static app can meet the use case without extra server processes.

#### UX requirements

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

#### Dashboard data

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

### A.7 Authentication, Authorization, and Roles

Target roles:

#### Super Admin

- Full administrative permissions
- Manage Admin accounts and Field Tester accounts
- Configure administrative settings
- Access all management modules
- Delete records where policy permits

#### Admin

- Manage tree data and associated metadata
- Manage tags/categories where supported
- Review field records and assessments
- Manage classifier classes and training images
- Start or inspect model jobs where allowed
- View analytics
- Cannot promote another user to Super Admin unless explicitly granted that permission

#### Field Tester

- Use the Android field workflows
- Access only authorized field data and permitted uploads/assessment history
- No access to web administration APIs or pages

Implement authorization on the FastAPI backend. Hiding a menu or route in React is not authorization.

#### Migration compatibility

- Inspect the current user schema and JWT claims first.
- Preserve current user IDs and password hashes.
- Do not lock out existing users.
- Map existing roles deliberately; do not automatically promote every legacy admin to Super Admin.
- Provide a secure, documented, one-time bootstrap path for the initial Super Admin using an environment variable/CLI or another private mechanism. Never hardcode a production password.
- Avoid unauthenticated password-reset endpoints. If password reset is not implemented, provide an admin-assisted secure workflow and document the limitation.
- Reuse current password hashing and JWT infrastructure unless a demonstrated security defect requires an approved migration.
- Use short, structured 401/403 responses without leaking sensitive details.
- Audit high-impact role changes and destructive administrative operations.

### A.8 Backend and API Rules

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

### A.9 Data Model and Storage Separation

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

#### Three separate image domains

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

#### Upload requirements

- Validate actual file content, not only extension or client MIME type.
- Support common image formats that the implementation can decode.
- Normalize unsupported mobile formats such as HEIC into a consistent JPEG/PNG representation where appropriate, retaining the original filename as metadata.
- Enforce size limits and give clear client errors.
- Use SHA-256/content hashes for exact duplicate detection where appropriate.
- Never trust a supplied path or filename to choose a filesystem path.
- Store files under generated IDs and known category directories.
- Do not serve the entire uploads directory publicly. Serve assets through authenticated routes or short-lived access mechanisms where needed.

### A.10 DINOv2 AI Requirements

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

#### Training pipeline

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

#### Model metadata

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

#### Honest evaluation

Report dataset size by class, independent test size, accuracy, precision, recall, macro F1, and a confusion matrix when enough labeled test data exists. Avoid random train/test splits that put near-identical burst/session photos on both sides. Prefer tree/session-grouped separation for independent evaluation.

Do not claim accuracy based on training metrics alone.

### A.11 Leaf Assessment History

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

#### Storage

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

#### Sync

- Save the attempt locally before remote work where current flow supports it.
- Synchronize by unique `assessmentId`.
- Repeated syncs must not create duplicates.
- A failed server request does not silently delete a local event/photo.
- Offline history remains accessible.
- Never invent an AI result if the server/model is unavailable.

### A.12 Dataset Quality and Admin Classifier UX

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

### A.13 Android Simplification Rules

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

### A.14 Museum Isolation (protected resources)

#### Protected Museum resources

The separate Museum App is protected and outside the scope of this task. Known local source: `C:\Capstone\Museum_App`. Historical hosted roots included `/opt/museum-app/` and a Compose project named `museum_app_prod`; exact live details are not relevant to this migration.

Never:

- deploy GEO Tree inside the Museum directory;
- overwrite Museum source/deployment files;
- inspect or operate Museum containers, MongoDB, Qdrant, uploads, OpenCLIP storage, TripoSR jobs/caches, or volumes;
- change Museum domains, routes, DNS, Caddy, TLS, or service configuration;
- stop/restart/rebuild a Museum service or run Museum deployment scripts;
- run global Docker prune/cleanup or broad resource deletion commands.

Keep local GEO Tree Compose services and volumes separate from Museum resources. If any local Docker resource or path ownership is ambiguous, do not modify it. Do not change anything merely to make a cleanup easier.

### A.17 Testing Standards

Run and report actual command results. Re-run the current suites before and after significant refactors.

#### Backend

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

#### Android

- existing geotagging/map/navigation/offline tests
- login/session handling by role
- field tester navigation
- leaf assessment result/history flow
- multiple scans create multiple records
- local history remains available offline
- sync retry/idempotency
- Room migrations
- release base URL configuration

#### Web

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

#### Deployment smoke test

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

### A.19 Out of Scope Unless Explicitly Approved

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

### A.20 Required Engineering Report

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

### A.22 Split Hygiene and Model Evaluation

- Deduplicate using SHA-256; use perceptual hashing or embedding similarity as a review signal for near-duplicates. Do not automatically delete distinct images solely due to similarity.
- Keep captures of the same leaf/session, burst shots, and resized/cropped copies in one split. Use a deterministic seed and group-aware stratification where possible. Ensure every class is represented in validation/test; document any unavoidable limitation.
- Never use training images for the reported final test metrics. Keep the test split locked while selecting the model. If there is no independent test collection, call results an internal split estimate, not independent field validation.
- Prefer balanced accuracy and macro/per-class precision, recall, F1, support, and a confusion matrix, alongside total accuracy. Store split IDs and exact counts. Compare a baseline only on the same held-out images.
- Do not claim 95% or any other accuracy for the new model until measured on the new dataset with its documented split. Do not guarantee perfect accuracy.

### A.23 Mobile Model Bundle and Offline Acceptance Gate

Use the existing DINOv2 ViT-S/14 frozen feature extractor and classifier-head pipeline unless a measured reason and explicit approval justify changing it. Use the project's verified mobile exporter (expected: `backend/scripts/export_mobile_model.py`) and actual model asset names/path discovered in `LeafModelStore` and `OnDeviceLeafClassifier`.

The exported bundle must use the exact class order `Healthy`, `Spotting`, `Powdery Mildew`, correct preprocessing, compatible model/cache key, model version, and checksums. Python/ONNX parity and Android model parity must pass. Include the bundle in the APK and verify APK contents and sizes.

Required real acceptance check: enable airplane mode, launch the app, classify a new image stored on-device, verify the model produces a prediction without contacting the API, save the assessment locally, close/reopen as appropriate, and verify the local record/history persists. Reconnect and confirm sync is idempotent. Clearly state whether this was tested on a physical device or an emulator. Never treat an ordinary backend health check as proof of offline inference.
