import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { request, uploadWithProgress } from "./client";
import type {
  ActivityEntry,
  AppRelease,
  Assessment,
  ClassImages,
  ClassifierClass,
  ClassifierList,
  Dashboard,
  DatasetArchive,
  DatasetProfile,
  EvidenceResponse,
  JobView,
  MapTreesResponse,
  MobileOverview,
  ModelProfile,
  ModelStatus,
  ModelView,
  Page,
  Role,
  Tag,
  TagColor,
  Tree,
  UploadResult,
  User,
} from "./types";

const ADMIN = "/api/v1/admin";

type Query = Record<string, string | number | boolean | null | undefined>;

// Dashboard / activity --------------------------------------------------------------------------
export function useDashboard() {
  return useQuery({ queryKey: ["dashboard"], queryFn: ({ signal }) => request<Dashboard>(`${ADMIN}/dashboard`, { signal }), refetchInterval: 60_000 });
}

export function useActivity(query: Query) {
  return useQuery({
    queryKey: ["activity", query],
    queryFn: ({ signal }) => request<Page<ActivityEntry>>(`${ADMIN}/activity`, { query, signal }),
    placeholderData: keepPreviousData,
  });
}

// Trees -----------------------------------------------------------------------------------------
export function useTrees(query: Query) {
  return useQuery({
    queryKey: ["trees", query],
    queryFn: ({ signal }) => request<Page<Tree>>(`${ADMIN}/trees`, { query, signal }),
    placeholderData: keepPreviousData,
  });
}

export function useTree(id: string | undefined) {
  return useQuery({
    queryKey: ["tree", id],
    queryFn: ({ signal }) => request<Tree>(`${ADMIN}/trees/${id}`, { signal }),
    enabled: Boolean(id),
  });
}

export interface TreeEdit {
  treeCode?: string;
  age?: number | null;
  tasteCategory?: string | null;
  yearlyYield?: number | null;
  fruitQuality?: string | null;
  notes?: string | null;
  tagIds?: string[];
}

function useTreeMutation<V>(fn: (vars: V) => Promise<Tree>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (tree) => {
      client.setQueryData(["tree", tree.id], tree);
      client.invalidateQueries({ queryKey: ["trees"] });
      client.invalidateQueries({ queryKey: ["dashboard"] });
      client.invalidateQueries({ queryKey: ["tags"] });
      client.invalidateQueries({ queryKey: ["map-trees"] });
      client.invalidateQueries({ queryKey: ["map-evidence"] });
    },
  });
}

export function useUpdateTree(id: string) {
  return useTreeMutation((edit: TreeEdit) => request<Tree>(`${ADMIN}/trees/${id}`, { method: "PATCH", json: edit }));
}

export function useArchiveTree(id: string) {
  return useTreeMutation((archive: boolean) => request<Tree>(`${ADMIN}/trees/${id}/${archive ? "archive" : "restore"}`, { method: "POST" }));
}

export function useReplaceTreeImage(id: string) {
  return useTreeMutation(({ file, onProgress }: { file: File; onProgress: (f: number) => void }) => {
    const form = new FormData();
    form.append("file", file);
    return uploadWithProgress<Tree>(`${ADMIN}/trees/${id}/image`, form, onProgress);
  });
}

export function useDeleteTreeImage(id: string) {
  return useTreeMutation<void>(() => request<Tree>(`${ADMIN}/trees/${id}/image`, { method: "DELETE" }));
}

// Tags ------------------------------------------------------------------------------------------
export function useTags() {
  return useQuery({ queryKey: ["tags"], queryFn: ({ signal }) => request<{ items: Tag[] }>(`${ADMIN}/tags`, { signal }) });
}

export interface TagInput {
  name: string;
  color: TagColor;
  description: string | null;
}

export function useSaveTag() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, input }: { id?: string; input: TagInput }) =>
      id ? request<Tag>(`${ADMIN}/tags/${id}`, { method: "PATCH", json: input }) : request<Tag>(`${ADMIN}/tags`, { method: "POST", json: input }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["tags"] });
      client.invalidateQueries({ queryKey: ["trees"] });
      client.invalidateQueries({ queryKey: ["tree"] });
    },
  });
}

export function useDeleteTag() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => request<{ treesDetached: number }>(`${ADMIN}/tags/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["tags"] });
      client.invalidateQueries({ queryKey: ["trees"] });
      client.invalidateQueries({ queryKey: ["tree"] });
    },
  });
}

// Assessments -----------------------------------------------------------------------------------
export function useAssessments(query: Query) {
  return useQuery({
    queryKey: ["assessments", query],
    queryFn: ({ signal }) => request<Page<Assessment>>(`${ADMIN}/assessments`, { query, signal }),
    placeholderData: keepPreviousData,
  });
}

export function useAssessmentFacets() {
  return useQuery({
    queryKey: ["assessment-facets"],
    queryFn: ({ signal }) => request<{ statuses: string[]; classNames: string[] }>(`${ADMIN}/assessments/facets`, { signal }),
  });
}

// Users -----------------------------------------------------------------------------------------
export function useUsers(query: Query) {
  return useQuery({
    queryKey: ["users", query],
    queryFn: ({ signal }) => request<Page<User>>(`${ADMIN}/users`, { query, signal }),
    placeholderData: keepPreviousData,
  });
}

function useUserMutation<V>(fn: (vars: V) => Promise<User>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["users"] });
      client.invalidateQueries({ queryKey: ["dashboard"] });
    },
  });
}

export function useCreateUser() {
  return useUserMutation((input: { email: string; displayName: string; role: Role; password: string }) =>
    request<User>(`${ADMIN}/users`, { method: "POST", json: input }),
  );
}

export function useUpdateUser() {
  return useUserMutation(({ id, ...changes }: { id: string; displayName?: string; role?: Role; active?: boolean }) =>
    request<User>(`${ADMIN}/users/${id}`, { method: "PATCH", json: changes }),
  );
}

export function useResetPassword() {
  return useUserMutation(({ id, password }: { id: string; password: string }) =>
    request<User>(`${ADMIN}/users/${id}/password`, { method: "POST", json: { password } }),
  );
}

// Classifiers and AI ----------------------------------------------------------------------------
export function useClassifiers() {
  return useQuery({ queryKey: ["classifiers"], queryFn: ({ signal }) => request<ClassifierList>(`${ADMIN}/classifiers`, { signal }), retry: false });
}

export function useClassImages(classId: string | undefined) {
  return useQuery({
    queryKey: ["class-images", classId],
    queryFn: ({ signal }) => request<ClassImages>(`${ADMIN}/classifiers/${classId}/images`, { signal }),
    enabled: Boolean(classId),
    retry: false,
  });
}

function invalidateAi(client: ReturnType<typeof useQueryClient>) {
  for (const key of ["classifiers", "class-images", "model-status", "model-versions", "jobs", "dashboard", "activity"]) {
    client.invalidateQueries({ queryKey: [key] });
  }
}

export function useSaveClass() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, name }: { id?: string; name: string }) =>
      id
        ? request<ClassifierClass>(`${ADMIN}/classifiers/${id}`, { method: "PATCH", json: { name } })
        : request<ClassifierClass>(`${ADMIN}/classifiers`, { method: "POST", json: { name } }),
    onSuccess: () => invalidateAi(client),
  });
}

export function useDeleteClass() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => request<{ deletedImages: number; modelInvalidated: boolean }>(`${ADMIN}/classifiers/${id}`, { method: "DELETE" }),
    onSuccess: () => invalidateAi(client),
  });
}

export function useUploadClassImages(classId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ files, onProgress }: { files: File[]; onProgress: (f: number) => void }) => {
      const form = new FormData();
      for (const file of files) form.append("files", file, file.name);
      return uploadWithProgress<UploadResult>(`${ADMIN}/classifiers/${classId}/images`, form, onProgress);
    },
    onSettled: () => invalidateAi(client),
  });
}

export function useDeleteClassImage(classId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (imageId: string) => request(`${ADMIN}/classifiers/${classId}/images/${imageId}`, { method: "DELETE" }),
    onSettled: () => invalidateAi(client),
  });
}

export function useModelStatus(poll = false) {
  return useQuery({
    queryKey: ["model-status"],
    queryFn: ({ signal }) => request<ModelStatus>(`${ADMIN}/ai/model`, { signal }),
    retry: false,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return poll || status === "TRAINING" || status === "QUEUED" ? 5_000 : false;
    },
  });
}

export function useModelVersions() {
  return useQuery({ queryKey: ["model-versions"], queryFn: ({ signal }) => request<{ items: ModelView[] }>(`${ADMIN}/ai/models`, { signal }), retry: false });
}

export function useJobs() {
  return useQuery({ queryKey: ["jobs"], queryFn: ({ signal }) => request<{ items: JobView[] }>(`${ADMIN}/ai/jobs`, { signal }), retry: false });
}

export function useRetrain() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => request<JobView>(`${ADMIN}/ai/retrain`, { method: "POST" }),
    onSuccess: () => invalidateAi(client),
  });
}

// Maps ------------------------------------------------------------------------------------------
export function useMapTrees(query: Query) {
  return useQuery({
    queryKey: ["map-trees", query],
    queryFn: ({ signal }) => request<MapTreesResponse>(`${ADMIN}/map/trees`, { query, signal }),
    placeholderData: keepPreviousData,
  });
}

export function useEvidenceMap(query: Query) {
  return useQuery({
    queryKey: ["map-evidence", query],
    queryFn: ({ signal }) => request<EvidenceResponse>(`${ADMIN}/map/evidence`, { query, signal }),
    placeholderData: keepPreviousData,
  });
}

// Mobile Settings -------------------------------------------------------------------------------
const MOBILE = `${ADMIN}/mobile`;

function invalidateMobile(client: ReturnType<typeof useQueryClient>) {
  for (const key of ["mobile-overview", "mobile-releases", "mobile-model", "mobile-dataset", "activity"]) {
    client.invalidateQueries({ queryKey: [key] });
  }
}

export function useMobileOverview() {
  return useQuery({ queryKey: ["mobile-overview"], queryFn: ({ signal }) => request<MobileOverview>(`${MOBILE}/overview`, { signal }) });
}

export function useReleases(query: Query) {
  return useQuery({
    queryKey: ["mobile-releases", query],
    queryFn: ({ signal }) => request<Page<AppRelease>>(`${MOBILE}/releases`, { query, signal }),
    placeholderData: keepPreviousData,
  });
}

export function useRegisterRelease() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { notes: string; datasetArchiveId: string | null; baseline: boolean }) =>
      request<AppRelease>(`${MOBILE}/releases`, { method: "POST", json: body }),
    onSettled: () => invalidateMobile(client),
  });
}

export function useModelProfile() {
  return useQuery({ queryKey: ["mobile-model"], queryFn: ({ signal }) => request<ModelProfile>(`${MOBILE}/model`, { signal }) });
}

export function useDatasetProfile() {
  return useQuery({
    queryKey: ["mobile-dataset"],
    queryFn: ({ signal }) => request<DatasetProfile>(`${MOBILE}/dataset`, { signal }),
    // Poll only while an uploaded archive is still being validated.
    refetchInterval: (query) => (query.state.data?.archives.some((a) => a.status === "VALIDATING") ? 2_000 : false),
  });
}

export function useUploadArchive() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ file, onProgress }: { file: File; onProgress: (f: number) => void }) => {
      const form = new FormData();
      form.append("file", file, file.name);
      return uploadWithProgress<DatasetArchive>(`${MOBILE}/dataset/archives`, form, onProgress);
    },
    onSettled: () => invalidateMobile(client),
  });
}

export function useDiscardArchive() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => request<DatasetArchive>(`${MOBILE}/dataset/archives/${id}`, { method: "DELETE" }),
    onSettled: () => invalidateMobile(client),
  });
}
