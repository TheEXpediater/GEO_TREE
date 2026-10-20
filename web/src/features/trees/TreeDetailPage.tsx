import { Archive, ArchiveRestore, Camera, Check, Map as MapIcon, MapPin, RotateCcw, Save, ScanSearch, Tags, Trash2, Upload } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";

import { ApiError } from "@/api/client";
import { useArchiveTree, useAssessments, useDeleteTreeImage, useReplaceTreeImage, useTags, useTree, useUpdateTree, type TreeEdit } from "@/api/hooks";
import type { Tree } from "@/api/types";
import { AuthImage } from "@/components/AuthImage";
import { LeafletMap } from "@/components/map/LeafletMap";
import { Button } from "@/components/ui/Button";
import { Badge, Card, CardHeader, DataItem, EmptyState, ErrorState, Notice, PageHeader, ProgressBar, Skeleton, StatusBadge, errorMessage } from "@/components/ui/Display";
import { Field, Input, Textarea } from "@/components/ui/Form";
import { ConfirmDialog } from "@/components/ui/Overlay";
import { useToast } from "@/components/ui/Toast";
import { cn } from "@/lib/cn";
import { formatCoordinate, formatDateTime, formatMeters, formatPercent, formatRelative } from "@/lib/format";

import { TAG_SWATCHES } from "../tags/TagChip";
import { AccuracyBadge } from "./TreesPage";

const TREE_CODE = /^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$/;

interface FormState {
  treeCode: string;
  age: string;
  tasteCategory: string;
  yearlyYield: string;
  fruitQuality: string;
  notes: string;
  tagIds: string[];
}

function toForm(tree: Tree): FormState {
  return {
    treeCode: tree.treeCode,
    age: tree.age?.toString() ?? "",
    tasteCategory: tree.tasteCategory ?? "",
    yearlyYield: tree.yearlyYield?.toString() ?? "",
    fruitQuality: tree.fruitQuality ?? "",
    notes: tree.notes ?? "",
    tagIds: tree.tags.map((t) => t.id),
  };
}

function diff(tree: Tree, form: FormState): { edit: TreeEdit; errors: Partial<Record<keyof FormState, string>> } {
  const errors: Partial<Record<keyof FormState, string>> = {};
  const edit: TreeEdit = {};
  const code = form.treeCode.trim().toUpperCase();
  if (!TREE_CODE.test(code)) errors.treeCode = "Use 1–40 letters, digits, - or _ (starting with a letter or digit).";
  else if (code !== tree.treeCode) edit.treeCode = code;
  const age = form.age.trim() === "" ? null : Number(form.age);
  if (age !== null && (!Number.isInteger(age) || age < 0 || age > 2000)) errors.age = "Age must be a whole number of years (0–2000).";
  else if (age !== tree.age) edit.age = age;
  const yieldValue = form.yearlyYield.trim() === "" ? null : Number(form.yearlyYield);
  if (yieldValue !== null && (Number.isNaN(yieldValue) || yieldValue < 0)) errors.yearlyYield = "Yield must be zero or more.";
  else if (yieldValue !== tree.yearlyYield) edit.yearlyYield = yieldValue;
  for (const key of ["tasteCategory", "fruitQuality", "notes"] as const) {
    const value = form[key].trim() || null;
    if (value !== tree[key]) edit[key] = value;
  }
  const current = tree.tags.map((t) => t.id);
  if (form.tagIds.length !== current.length || form.tagIds.some((id, i) => id !== current[i])) edit.tagIds = form.tagIds;
  return { edit, errors };
}

export function TreeDetailPage() {
  const { treeId } = useParams();
  const tree = useTree(treeId);
  if (tree.isLoading) {
    return (
      <div className="space-y-6" aria-busy="true">
        <Skeleton className="h-10 w-64" />
        <div className="grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
          <Skeleton className="h-96" />
          <Skeleton className="h-96" />
        </div>
      </div>
    );
  }
  if (tree.error || !tree.data) {
    return tree.error instanceof ApiError && tree.error.status === 404 ? (
      <EmptyState title="Tree not found" description="It may have been removed, or the link is wrong." action={<Link to="/trees" className="text-sm font-medium text-forest hover:underline">Back to tree records</Link>} />
    ) : (
      <ErrorState error={tree.error} onRetry={() => tree.refetch()} />
    );
  }
  return <TreeDetail tree={tree.data} />;
}

function TreeDetail({ tree }: { tree: Tree }) {
  const toast = useToast();
  const archive = useArchiveTree(tree.id);
  const [confirmArchive, setConfirmArchive] = useState(false);

  async function toggleArchive() {
    try {
      const updated = await archive.mutateAsync(!tree.archived);
      toast.success(updated.archived ? `${tree.treeCode} archived` : `${tree.treeCode} restored`,
        updated.archived ? "Field devices hide it after their next sync. Its scans and photos are kept." : "It is visible on field devices again after their next sync.");
      setConfirmArchive(false);
    } catch (error) {
      toast.error("Couldn't change the archive state", errorMessage(error));
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Tree record"
        title={tree.treeCode}
        description={<>Registered {formatDateTime(tree.createdAt)}{tree.createdBy ? ` by ${tree.createdBy}` : ""} · server version {tree.serverVersion}</>}
        actions={
          tree.archived ? (
            <Button icon={ArchiveRestore} onClick={() => setConfirmArchive(true)}>Restore</Button>
          ) : (
            <Button icon={Archive} onClick={() => setConfirmArchive(true)}>Archive</Button>
          )
        }
      />
      {tree.archived ? (
        <Notice tone="amber" icon={Archive} title="This tree is archived">
          Archived {formatDateTime(tree.archivedAt)}{tree.archivedBy ? ` by ${tree.archivedBy}` : ""}. Field devices hide it; its assessment history and photos are kept. Restore it to bring it back.
        </Notice>
      ) : null}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <div className="space-y-6">
          <PhotoCard tree={tree} />
          <Card>
            <CardHeader icon={MapPin} title="Field evidence" description="Recorded by the field app; read-only" />
            <dl className="grid grid-cols-2 gap-x-4 gap-y-4 p-5">
              <DataItem label="Latitude" mono>{formatCoordinate(tree.latitude)}</DataItem>
              <DataItem label="Longitude" mono>{formatCoordinate(tree.longitude)}</DataItem>
              <DataItem label="GPS accuracy"><AccuracyBadge tree={tree} /></DataItem>
              <DataItem label="Altitude" mono>{tree.altitudeMeters === null ? "—" : formatMeters(tree.altitudeMeters)}</DataItem>
              <DataItem label="Location captured">{formatDateTime(tree.locationCapturedAt)}</DataItem>
              <DataItem label="Last updated">{formatDateTime(tree.updatedAt)}{tree.updatedBy ? <span className="block text-xs text-ink-faint">{tree.updatedBy}</span> : null}</DataItem>
            </dl>
            <div className="h-56 border-t border-line">
              <LeafletMap
                label={`Location of tree ${tree.treeCode}`}
                markers={[{ id: tree.id, lat: tree.latitude, lng: tree.longitude, variant: tree.archived ? "archived" : "tree", title: tree.treeCode }]}
                selectedId={tree.id}
                scrollZoom={false}
              />
            </div>
            <div className="border-t border-line px-5 py-3">
              <Link
                to={`/trees/map?${new URLSearchParams({ focus: tree.id, ...(tree.archived ? { status: "all" } : {}) })}`}
                className="inline-flex items-center gap-1.5 text-[13px] font-medium text-forest hover:underline"
              >
                <MapIcon className="h-3.5 w-3.5" aria-hidden /> View on the tree map
              </Link>
            </div>
          </Card>
        </div>
        <div className="space-y-6">
          <MetadataCard tree={tree} />
          <HistoryCard tree={tree} />
        </div>
      </div>

      <ConfirmDialog
        open={confirmArchive}
        onCancel={() => setConfirmArchive(false)}
        onConfirm={toggleArchive}
        busy={archive.isPending}
        tone={tree.archived ? "primary" : "danger"}
        title={tree.archived ? `Restore ${tree.treeCode}?` : `Archive ${tree.treeCode}?`}
        confirmLabel={tree.archived ? "Restore tree" : "Archive tree"}
      >
        {tree.archived ? (
          <p>The tree becomes active again and reappears on field devices after their next sync.</p>
        ) : (
          <>
            <p>The tree is hidden from active lists and from field devices after their next sync.</p>
            <p>Nothing is deleted: its {tree.assessmentCount} leaf assessment{tree.assessmentCount === 1 ? "" : "s"} and photos are kept, and you can restore it at any time.</p>
          </>
        )}
      </ConfirmDialog>
    </div>
  );
}

function PhotoCard({ tree }: { tree: Tree }) {
  const toast = useToast();
  const input = useRef<HTMLInputElement>(null);
  const replace = useReplaceTreeImage(tree.id);
  const remove = useDeleteTreeImage(tree.id);
  const [progress, setProgress] = useState<number | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  async function upload(file: File) {
    setProgress(0);
    try {
      await replace.mutateAsync({ file, onProgress: setProgress });
      toast.success("Registration photo updated");
    } catch (error) {
      toast.error("Upload failed", errorMessage(error));
    } finally {
      setProgress(null);
      if (input.current) input.current.value = "";
    }
  }

  return (
    <Card className="overflow-hidden">
      <AuthImage src={tree.imageUrl} alt={`Registration photo of tree ${tree.treeCode}`} className="aspect-[4/3] w-full" />
      <div className="space-y-3 p-4">
        {progress !== null ? <ProgressBar value={progress} label="Uploading photo" /> : null}
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="flex items-center gap-2 text-[13px] text-ink-muted">
            <Camera className="h-4 w-4 text-moss" aria-hidden />
            Tree registration photo
          </p>
          <div className="flex gap-2">
            <input ref={input} type="file" accept="image/jpeg,image/png,image/webp" className="sr-only" tabIndex={-1} aria-hidden
              onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])} />
            <Button size="sm" icon={Upload} loading={replace.isPending} onClick={() => input.current?.click()}>
              {tree.imageUrl ? "Replace" : "Upload"}
            </Button>
            {tree.imageUrl ? (
              <Button size="sm" variant="ghost" icon={Trash2} className="text-danger hover:bg-danger-soft hover:text-danger" onClick={() => setConfirmDelete(true)}>
                Delete
              </Button>
            ) : null}
          </div>
        </div>
      </div>
      <ConfirmDialog
        open={confirmDelete}
        onCancel={() => setConfirmDelete(false)}
        busy={remove.isPending}
        title="Delete the registration photo?"
        confirmLabel="Delete photo"
        onConfirm={async () => {
          try {
            await remove.mutateAsync();
            toast.success("Registration photo deleted");
            setConfirmDelete(false);
          } catch (error) {
            toast.error("Couldn't delete the photo", errorMessage(error));
          }
        }}
      >
        <p>The server copy of this photo is removed permanently. A copy on the phone that captured it is not affected.</p>
        <p>Leaf assessment photos and training images are separate and are not touched.</p>
      </ConfirmDialog>
    </Card>
  );
}

function MetadataCard({ tree }: { tree: Tree }) {
  const toast = useToast();
  const tags = useTags();
  const save = useUpdateTree(tree.id);
  const [form, setForm] = useState<FormState>(() => toForm(tree));
  const [errors, setErrors] = useState<Partial<Record<keyof FormState, string>>>({});

  useEffect(() => {
    setForm(toForm(tree));
    setErrors({});
  }, [tree]);

  const { edit } = diff(tree, form);
  const dirty = Object.keys(edit).length > 0;

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((current) => ({ ...current, [key]: value }));
    setErrors((current) => ({ ...current, [key]: undefined }));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    const result = diff(tree, form);
    setErrors(result.errors);
    if (Object.keys(result.errors).length > 0 || Object.keys(result.edit).length === 0) return;
    try {
      await save.mutateAsync(result.edit);
      toast.success("Tree record saved", result.edit.tagIds && Object.keys(result.edit).length === 1 ? undefined : "Field devices receive the change on their next sync.");
    } catch (error) {
      if (error instanceof ApiError && error.code === "TREE_CODE_CONFLICT") setErrors({ treeCode: error.message });
      else toast.error("Couldn't save the tree", errorMessage(error));
    }
  }

  return (
    <Card>
      <CardHeader icon={Tags} title="Details and tags" description="Descriptive data you can correct; changes are recorded in the activity log" />
      <form onSubmit={submit} className="space-y-4 p-5" noValidate>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Tree Code" required error={errors.treeCode} hint="Unique across all trees">
            {(p) => <Input {...p} value={form.treeCode} onChange={(e) => set("treeCode", e.target.value)} className="font-mono uppercase" maxLength={40} />}
          </Field>
          <Field label="Age (years)" error={errors.age}>
            {(p) => <Input {...p} inputMode="numeric" value={form.age} onChange={(e) => set("age", e.target.value)} />}
          </Field>
          <Field label="Taste category">
            {(p) => <Input {...p} value={form.tasteCategory} onChange={(e) => set("tasteCategory", e.target.value)} maxLength={40} placeholder="e.g. Sweet, Sour" />}
          </Field>
          <Field label="Fruit quality">
            {(p) => <Input {...p} value={form.fruitQuality} onChange={(e) => set("fruitQuality", e.target.value)} maxLength={40} />}
          </Field>
          <Field label="Yearly yield" error={errors.yearlyYield}>
            {(p) => <Input {...p} inputMode="decimal" value={form.yearlyYield} onChange={(e) => set("yearlyYield", e.target.value)} />}
          </Field>
        </div>
        <Field label="Notes">
          {(p) => <Textarea {...p} value={form.notes} onChange={(e) => set("notes", e.target.value)} maxLength={2000} />}
        </Field>
        <fieldset>
          <legend className="text-[13px] font-medium text-ink">Tags</legend>
          <p className="mt-0.5 text-[13px] text-ink-faint">Web-only labels for organising records. They are not the leaf-condition classes.</p>
          <div className="mt-2.5 flex flex-wrap gap-2">
            {tags.isLoading ? <Skeleton className="h-7 w-48" /> : null}
            {(tags.data?.items ?? []).length === 0 && !tags.isLoading ? (
              <p className="text-[13px] text-ink-muted">No tags yet. <Link to="/tags" className="font-medium text-forest hover:underline">Create tags</Link></p>
            ) : null}
            {(tags.data?.items ?? []).map((tag) => {
              const selected = form.tagIds.includes(tag.id);
              const swatch = TAG_SWATCHES[tag.color] ?? TAG_SWATCHES.moss;
              return (
                <button
                  key={tag.id}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => set("tagIds", selected ? form.tagIds.filter((id) => id !== tag.id) : [...form.tagIds, tag.id])}
                  className={cn(
                    "inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-[13px] font-medium transition-colors",
                    selected ? "border-forest bg-forest text-cream" : "border-line-strong bg-surface text-ink-muted hover:border-ink-faint hover:text-ink",
                  )}
                >
                  {selected ? <Check className="h-3.5 w-3.5" aria-hidden /> : <span className={cn("h-2 w-2 rounded-full", swatch.dot)} aria-hidden />}
                  {tag.name}
                </button>
              );
            })}
          </div>
        </fieldset>
        <div className="flex flex-col-reverse gap-2 border-t border-line pt-4 sm:flex-row sm:items-center sm:justify-end">
          {dirty ? <span className="text-[13px] text-amber sm:mr-auto">Unsaved changes</span> : null}
          <Button icon={RotateCcw} variant="ghost" disabled={!dirty || save.isPending} onClick={() => { setForm(toForm(tree)); setErrors({}); }}>
            Discard
          </Button>
          <Button type="submit" variant="primary" icon={Save} loading={save.isPending} disabled={!dirty}>
            Save changes
          </Button>
        </div>
      </form>
    </Card>
  );
}

function HistoryCard({ tree }: { tree: Tree }) {
  const history = useAssessments({ treeId: tree.id, pageSize: 50 });
  const latest = history.data?.items.find((a) => a.status === "COMPLETED" || a.status === "UNCERTAIN");
  return (
    <Card>
      <CardHeader
        icon={ScanSearch}
        title="Leaf assessment history"
        description="Every scan is kept as its own record, newest first. Read-only."
        action={<Link to={`/assessments?tree=${tree.id}`} className="text-[13px] font-medium text-forest hover:underline">Open in Assessments</Link>}
      />
      {history.isLoading ? (
        <div className="space-y-2 p-5">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-12" />)}</div>
      ) : history.error ? (
        <ErrorState className="m-5" error={history.error} onRetry={() => history.refetch()} />
      ) : !history.data || history.data.items.length === 0 ? (
        <EmptyState className="m-5" icon={ScanSearch} title="No leaf scans yet" description="Scans made with the field app appear here after they sync." />
      ) : (
        <>
          {latest ? (
            <div className="mx-5 mt-5 flex items-center justify-between gap-3 rounded-lg bg-moss-soft/70 px-4 py-3">
              <div>
                <p className="text-2xs font-semibold uppercase tracking-wider text-tamarind">Current leaf status</p>
                <p className="mt-0.5 text-lg font-semibold">{latest.className}</p>
                <p className="text-xs text-ink-muted">Latest classified scan · {formatDateTime(latest.createdAt)}</p>
              </div>
              <p className="num text-2xl font-semibold text-forest">{formatPercent(latest.confidence, 0)}</p>
            </div>
          ) : null}
          <ol className="mt-3 divide-y divide-line">
            {history.data.items.map((item) => (
              <li key={item.assessmentId} className="flex items-center gap-3 px-5 py-3">
                <AuthImage src={item.imageUrl} alt={`Leaf scan ${formatDateTime(item.createdAt)}`} className="h-10 w-10 shrink-0 rounded-md" />
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium">{item.className ?? (item.errorCode ? "Assessment unavailable" : "Not classified")}</p>
                  <p className="text-xs text-ink-faint">{formatDateTime(item.createdAt)} · {item.modelDisplayName ?? item.modelName} v{item.modelVersion}</p>
                </div>
                <div className="flex shrink-0 flex-col items-end gap-1">
                  <StatusBadge status={item.status} />
                  {item.confidence !== null ? <span className="num text-xs text-ink-muted">{formatPercent(item.confidence)}</span> : null}
                </div>
              </li>
            ))}
          </ol>
          {history.data.total > history.data.items.length ? (
            <p className="border-t border-line px-5 py-3 text-[13px] text-ink-muted">
              Showing the latest {history.data.items.length} of {history.data.total}. <Badge>{formatRelative(history.data.items.at(-1)?.createdAt)}</Badge>
            </p>
          ) : null}
        </>
      )}
    </Card>
  );
}
