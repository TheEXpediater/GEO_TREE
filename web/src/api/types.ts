/** Response shapes of the GEO Tree API (see backend/app/routes/admin_*.py and app/ai/service.py). */

export type Role = "SUPER_ADMIN" | "ADMIN" | "FIELD_TESTER";

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface LoginResponse {
  access_token: string;
  token_type: string;
  expires_at: string;
  user: { email: string; display_name: string; role: string | null };
}

export interface Tag {
  id: string;
  name: string;
  color: TagColor;
  description: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  treeCount?: number;
}

export type TagColor = "forest" | "moss" | "clay" | "amber" | "sky" | "stone" | "berry";

export type AccuracyLevel = "GOOD" | "ACCEPTABLE" | "LOW";

export interface AssessmentBrief {
  id: string;
  status: string | null;
  className: string | null;
  confidence: number | null;
  createdAt: string | null;
}

export interface Tree {
  id: string;
  treeCode: string;
  latitude: number;
  longitude: number;
  accuracyMeters: number;
  accuracyLevel: AccuracyLevel;
  altitudeMeters: number | null;
  locationCapturedAt: string | null;
  age: number | null;
  tasteCategory: string | null;
  yearlyYield: number | null;
  fruitQuality: string | null;
  notes: string | null;
  imageUrl: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  serverUpdatedAt: string | null;
  serverVersion: number | null;
  createdBy: string | null;
  updatedBy: string | null;
  archived: boolean;
  archivedAt: string | null;
  archivedBy: string | null;
  tags: Tag[];
  completeness: { photo: boolean; accuracy: boolean; descriptive: boolean; complete: boolean };
  assessmentCount: number;
  latestAssessment: AssessmentBrief | null;
}

export interface Assessment {
  assessmentId: string;
  treeId: string;
  treeCode: string | null;
  status: string | null;
  classId: string | null;
  className: string | null;
  confidence: number | null;
  modelName: string | null;
  modelDisplayName: string | null;
  modelVersion: number | null;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string | null;
  completedAt: string | null;
  imageUrl: string | null;
  createdBy: string | null;
  serverVersion: number | null;
}

export interface User {
  id: string;
  email: string;
  displayName: string;
  role: Role;
  roleAssigned: boolean;
  active: boolean;
  temporary: boolean;
  createdAt: string | null;
  lastLoginAt: string | null;
  passwordChangedAt: string | null;
  isSelf: boolean;
  canManage: boolean;
}

export interface ActivityEntry {
  id: string;
  at: string;
  actorEmail: string | null;
  actorRole: string | null;
  action: string;
  summary: string | null;
  targetType: string | null;
  targetId: string | null;
  details: Record<string, unknown>;
}

export interface WeeklyBucket {
  weekStart: string;
  count: number;
}

export interface JobView {
  jobId: string;
  status: "QUEUED" | "TRAINING" | "COMPLETE" | "ERROR" | string;
  trigger: string | null;
  createdAt: string | null;
  runAfter?: string | null;
  startedAt?: string | null;
  finishedAt: string | null;
  coalescedChanges?: number;
  modelVersion: number | null;
  embeddingsComputed?: number | null;
  embeddingsReused?: number | null;
  errorMessage: string | null;
}

export interface Dashboard {
  generatedAt: string;
  trees: {
    total: number;
    archived: number;
    registeredLast7Days: number;
    withRegistrationPhoto: number;
    accuracy: { good: number; acceptable: number; low: number };
    completeness: {
      complete: number;
      total: number;
      percent: number | null;
      criteria: { photo: number; accuracy: number; descriptive: number };
      definition: string;
    };
    weekly: WeeklyBucket[];
  };
  images: {
    treeRegistration: number;
    training: { total: number; byClass: Array<{ classId: string; name: string; count: number; embedded: number }> };
    assessmentEvidence: number;
  };
  assessments: {
    total: number;
    withEvidencePhoto: number;
    last7Days: number;
    byStatus: Record<string, number>;
    byPredictedClass: Array<{ className: string; count: number }>;
    weekly: WeeklyBucket[];
  };
  users: { total: number; active: number; suspended: number; byRole: Record<Role, number> };
  model: {
    aiEnabled: boolean;
    status: string;
    stale: boolean;
    activeVersion: number | null;
    backbone: string | null;
    trainedAt: string | null;
    validationAccuracy: number | null;
    validationMacroF1: number | null;
    currentJob: JobView | null;
    lastJob: JobView | null;
    recentFailures: JobView[];
  };
  recentActivity: ActivityEntry[];
}

export interface ClassifierClass {
  id: string;
  name: string;
  slug: string;
  imageCount: number;
  embeddedCount: number;
  status: "INSUFFICIENT" | "PENDING_FEATURES" | "READY";
  inActiveModel: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ClassifierList {
  items: ClassifierClass[];
  minImagesPerClass: number;
}

export interface DatasetImage {
  id: string;
  classId: string;
  originalFilename: string;
  mimeType: string;
  fileSize: number;
  width: number | null;
  height: number | null;
  embeddingStatus: string;
  createdAt: string;
}

export interface ClassImages {
  classId: string;
  className: string;
  items: DatasetImage[];
}

export interface UploadResult {
  uploaded: DatasetImage[];
  rejected: Array<{ filename: string; code: string; message: string }>;
  embeddingsComputed: number;
  datasetVersion: number;
  job: JobView | null;
}

export interface ModelView {
  modelVersion: number;
  model: string;
  modelDisplayName: string;
  trainedAt: string;
  activatedAt: string | null;
  datasetVersion: number | null;
  trainCount: number;
  validationCount: number;
  classes: Array<{ classId: string; name: string; trainCount: number; validationCount: number }>;
  metrics: {
    accuracy: number;
    macroPrecision: number;
    macroRecall: number;
    macroF1: number;
    perClass: Array<{ classId: string; className: string; precision: number; recall: number; f1: number; support: number }>;
    confusionMatrix: { classIds: string[]; labels: string[]; matrix: number[][] };
  };
  active?: boolean;
  status?: string;
  invalidatedReason?: string | null;
}

export interface ModelStatus {
  status: "UNTRAINED" | "QUEUED" | "TRAINING" | "READY" | "ERROR" | string;
  stale: boolean;
  datasetVersion: number;
  datasetImageCount: number;
  readiness: { canTrain: boolean; minImagesPerClass: number; problems: string[] };
  activeModel: ModelView | null;
  currentJob: JobView | null;
  lastJob: JobView | null;
  lastError: string | null;
  backbone: { name: string; displayName: string; embeddingDimension: number; imageSize: number; loaded: boolean; device: string | null; error: string | null };
  confidenceThreshold: number;
  resourceGuard: { enabled: boolean; deferred: boolean; message: string | null };
}

// Maps (/api/v1/admin/map/*) -------------------------------------------------------------------
export interface MapTree {
  id: string;
  treeCode: string;
  latitude: number;
  longitude: number;
  accuracyMeters: number | null;
  accuracyLevel: AccuracyLevel;
  archived: boolean;
  hasPhoto: boolean;
  imageUrl: string | null;
  tags: Array<Pick<Tag, "id" | "name" | "color">>;
  assessmentCount: number;
  latestAssessment: AssessmentBrief | null;
  createdAt: string | null;
  locationCapturedAt: string | null;
}

export interface MapExcluded {
  id: string | null;
  treeCode: string | null;
  reason: "MISSING_COORDINATES" | "INVALID_COORDINATES";
}

export interface MapTreesResponse {
  items: MapTree[];
  total: number;
  placed: number;
  excludedCount: number;
  excluded: MapExcluded[];
  truncated: boolean;
}

export type EvidenceType = "REGISTRATION_PHOTO" | "LEAF_ASSESSMENT";

export interface EvidenceItem {
  id: string;
  type: EvidenceType;
  at: string | null;
  imageUrl: string | null;
  treeId: string;
  treeCode: string | null;
  latitude: number;
  longitude: number;
  accuracyMeters: number | null;
  treeArchived: boolean;
  assessment?: Assessment;
}

export interface EvidenceResponse {
  items: EvidenceItem[];
  placed: number;
  excludedCount: number;
  treeCount: number;
  truncated: boolean;
}

// Mobile Settings --------------------------------------------------------------------------------
export interface BundledModel {
  backbone: string | null;
  backboneDisplayName: string | null;
  embeddingModel: string | null;
  embeddingDimension: number | null;
  modelVersion: number | null;
  classes: string[];
  confidenceThreshold: number | null;
  headSha256: string | null;
  onnxSha256: string | null;
  onnxBytes: number | null;
  integrityOk: boolean;
  exportParity?: Record<string, unknown> | null;
}

export interface ApkFacts {
  fileName: string;
  bytes: number;
  sha256: string;
  packageName: string | null;
  versionName: string | null;
  versionCode: number | null;
  minSdk: number | null;
  targetSdk: number | null;
  debuggable: boolean;
  signing: { schemes: string[]; certificateSha256: string[]; note: string | null };
  bundledModel: BundledModel | null;
}

export interface ReleaseCheck {
  id: string;
  label: string;
  /** null = not configured, so not checked. */
  ok: boolean | null;
}

export type ReleaseStatus = "PUBLISHED" | "SUPERSEDED" | "ROLLED_BACK";

export interface AppRelease {
  id: string;
  status: ReleaseStatus;
  baseline: boolean;
  versionName: string | null;
  versionCode: number | null;
  packageName: string | null;
  apkFileName: string;
  apkBytes: number;
  apkSha256: string;
  signerSha256: string[];
  signatureSchemes: string[];
  minSdk: number | null;
  targetSdk: number | null;
  debuggable: boolean | null;
  model: { modelVersion: number | null; backbone: string | null; classes: string[] | null; headSha256: string | null; onnxSha256: string | null } | null;
  dataset: { archiveId: string; fileName: string | null; sha256: string | null } | null;
  notes: string;
  downloadUrl: string | null;
  registeredAt: string;
  originalPublishedAt: string | null;
  registeredBy: { email: string | null; role: string | null } | null;
  events: Array<{ at: string; action: string; actor: { email: string | null; role: string | null } | null }>;
}

export type ReleaseHealth = "HEALTHY" | "UNHEALTHY" | "NOT_PUBLISHED" | "UNREGISTERED";

export interface MobileOverview {
  health: ReleaseHealth;
  healthReason: string;
  download: { configured: boolean; pageUrl: string | null; apkUrl: string | null; https: boolean; distributionConfigured: boolean };
  servedApk: ApkFacts | null;
  servedApkError: string | null;
  checks: ReleaseCheck[];
  pinnedSignerSha256: string | null;
  currentRelease: AppRelease | null;
  servedRelease: AppRelease | null;
  qr: { url: string; pngDataUri: string } | null;
}

export interface MetricRow {
  name: string;
  precision: number | null;
  recall: number | null;
  f1: number | null;
  support: number | null;
}

export interface EvaluationRecord {
  id: string;
  modelVersion: number;
  headSha256: string | null;
  type: string;
  label: string;
  samples: number | null;
  accuracy: number | null;
  balancedAccuracy: number | null;
  macroF1: number | null;
  perClass: MetricRow[];
  confusionMatrix: { labels: string[]; matrix: number[][] } | null;
  details: Record<string, any>;
  limitations: string;
  sourceFile: string | null;
  sourceSha256: string | null;
  evaluatedAt: string | null;
  recordedAt: string;
}

export interface ServerModelProfile {
  modelVersion: number;
  backbone: string | null;
  backboneDisplayName: string | null;
  embeddingModel: string | null;
  embeddingDimension: number | null;
  head: string | null;
  status: string | null;
  trainedAt: string | null;
  activatedAt: string | null;
  classes: string[];
  trainCount: number | null;
  validationCount: number | null;
  perClassCounts: Array<{ name: string; train: number | null; validation: number | null }>;
  validation: {
    type: string;
    label: string;
    samples: number | null;
    accuracy: number | null;
    macroF1: number | null;
    macroPrecision: number | null;
    macroRecall: number | null;
    perClass: MetricRow[];
    confusionMatrix: { labels: string[]; matrix: number[][] } | null;
  } | null;
}

export interface ModelProfile {
  pipeline: string | null;
  server: ServerModelProfile | null;
  serverAiRuntime: boolean;
  bundled: (BundledModel & { apkVersionName: string | null; apkVersionCode: number | null; inference: string }) | null;
  bundledError: string | null;
  comparison: { sameVersion: boolean; sameClassOrder: boolean; sameFeatureExtractor: boolean } | null;
  evaluations: EvaluationRecord[];
  latestJob: { id: string; status: string; trigger: string | null; createdAt: string | null; finishedAt: string | null; modelVersion: number | null; errorMessage: string | null } | null;
  updateNote: string;
}

export type ArchiveStatus = "VALIDATING" | "READY" | "REJECTED" | "FAILED" | "ACTIVE" | "RETIRED" | "DISCARDED";

export interface ArchiveSummary {
  entries: number;
  imageFiles: number;
  accepted: number;
  statusCounts: Record<string, number>;
  formats: Record<string, number>;
  byClass: Record<string, Record<string, number>>;
  acceptedByClass: Record<string, number>;
  acceptedBySource: Record<string, Record<string, number>>;
  remappedLabels: Array<{ from: string; to: string; count: number }>;
  unknownLabels: string[];
  conflictFamilies: number;
  alreadyInActiveDataset: number;
  uncompressedBytes: number;
  compressedBytes: number;
  decodeChecked: boolean;
  heicDecodeChecked: boolean;
  labelPolicy: string;
}

export interface DatasetArchive {
  id: string;
  fileName: string;
  bytes: number;
  sha256: string;
  status: ArchiveStatus;
  origin: string;
  uploadedAt: string;
  uploadedBy: { email: string | null; role: string | null } | null;
  validatedAt: string | null;
  summary: ArchiveSummary | null;
  error: string | null;
  split: { perClass: Record<string, Record<string, number>>; totals: Record<string, number>; manifestFile: string; manifestSha256?: string } | null;
  activation: { activatedAt: string | null; method: string; note: string } | null;
}

export interface DatasetProfile {
  activeTrainingImages: { total: number; byClass: Record<string, number>; bySource: Record<string, Record<string, number>>; note: string };
  activeArchive: DatasetArchive | null;
  consistency: { activeImages: number; inActiveArchive: number; notInActiveArchive: number } | null;
  activeModel: { modelVersion: number; trainCount: number | null; validationCount: number | null } | null;
  leafAssessmentPhotos: number;
  leafAssessmentNote: string;
  archives: DatasetArchive[];
  archivesTotal: number;
  uploadsEnabled: boolean;
  maxUploadBytes: number;
  decodeChecked: boolean;
}
