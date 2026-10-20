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
