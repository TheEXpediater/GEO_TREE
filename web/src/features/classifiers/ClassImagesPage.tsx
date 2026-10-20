import { ArrowLeft, CheckSquare, ImagePlus, Images, Square, Trash2, UploadCloud, X } from "lucide-react";
import { useRef, useState, type DragEvent } from "react";
import { Link, useParams } from "react-router-dom";

import { useClassImages, useDeleteClassImage, useUploadClassImages } from "@/api/hooks";
import type { DatasetImage, UploadResult } from "@/api/types";
import { AuthImage } from "@/components/AuthImage";
import { Button } from "@/components/ui/Button";
import { Card, DataItem, EmptyState, ErrorState, Notice, PageHeader, ProgressBar, Skeleton, StatusBadge, errorMessage } from "@/components/ui/Display";
import { ConfirmDialog, Dialog } from "@/components/ui/Overlay";
import { useToast } from "@/components/ui/Toast";
import { cn } from "@/lib/cn";
import { formatBytes, formatCount, formatDateTime } from "@/lib/format";

const BATCH = 50; // server limit per request (AI_MAX_FILES_PER_UPLOAD)
const ACCEPT = "image/jpeg,image/png,image/webp,image/heic,image/heif,.heic,.heif";

interface UploadState {
  total: number;
  progress: number;
  done: boolean;
  uploaded: number;
  rejected: UploadResult["rejected"];
  failure: string | null;
}

export function ClassImagesPage() {
  const { classId = "" } = useParams();
  const images = useClassImages(classId);
  const upload = useUploadClassImages(classId);
  const remove = useDeleteClassImage(classId);
  const toast = useToast();
  const input = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [state, setState] = useState<UploadState | null>(null);
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [preview, setPreview] = useState<DatasetImage | null>(null);
  const [confirm, setConfirm] = useState<DatasetImage[] | null>(null);
  const [deleting, setDeleting] = useState(false);

  const base = `/api/v1/admin/classifiers/${classId}/images`;
  const items = images.data?.items ?? [];
  const className = images.data?.className ?? "";

  async function send(files: File[]) {
    if (files.length === 0) return;
    const next: UploadState = { total: files.length, progress: 0, done: false, uploaded: 0, rejected: [], failure: null };
    setState(next);
    for (let start = 0; start < files.length; start += BATCH) {
      const batch = files.slice(start, start + BATCH);
      try {
        const result = await upload.mutateAsync({
          files: batch,
          onProgress: (f) => setState((s) => s && { ...s, progress: (start + f * batch.length) / files.length }),
        });
        next.uploaded += result.uploaded.length;
        next.rejected = [...next.rejected, ...result.rejected];
      } catch (error) {
        // The whole batch was refused (e.g. every file a duplicate): keep the per-file reasons.
        const details = (error as { details?: { rejected?: UploadResult["rejected"] } }).details;
        if (details?.rejected) next.rejected = [...next.rejected, ...details.rejected];
        else next.failure = errorMessage(error);
      }
      setState({ ...next, progress: Math.min(1, (start + batch.length) / files.length) });
    }
    setState({ ...next, progress: 1, done: true });
    if (next.uploaded > 0) toast.success(`${next.uploaded} image${next.uploaded === 1 ? "" : "s"} added to ${className}`, "A model rebuild is queued automatically.");
    if (input.current) input.current.value = "";
  }

  function onDrop(event: DragEvent) {
    event.preventDefault();
    setDragging(false);
    send(Array.from(event.dataTransfer.files).filter((f) => f.type.startsWith("image/") || /\.(heic|heif)$/i.test(f.name)));
  }

  async function deleteImages(targets: DatasetImage[]) {
    setDeleting(true);
    let failed = 0;
    for (const image of targets) {
      try {
        await remove.mutateAsync(image.id);
      } catch {
        failed += 1;
      }
    }
    setDeleting(false);
    setConfirm(null);
    setPreview(null);
    setSelected(new Set());
    setSelecting(false);
    if (failed) toast.error(`${failed} image(s) could not be deleted`, "Try again.");
    else toast.success(`Deleted ${targets.length} image${targets.length === 1 ? "" : "s"}`, "Their cached features were removed and a rebuild is queued.");
  }

  function toggle(id: string) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  if (images.error) return <ErrorState error={images.error} onRetry={() => images.refetch()} />;

  return (
    <div className="space-y-6">
      <Link to="/classifiers" className="inline-flex items-center gap-1.5 text-[13px] font-medium text-ink-muted hover:text-ink">
        <ArrowLeft className="h-4 w-4" aria-hidden /> All classifiers
      </Link>
      <PageHeader
        eyebrow="Training images"
        title={className || "Loading…"}
        description={images.data ? `${formatCount(items.length)} images in this class's training dataset. Exact duplicates are rejected; field scans are never added here automatically.` : undefined}
        actions={
          selecting ? (
            <>
              <Button icon={selected.size === items.length ? Square : CheckSquare} onClick={() => setSelected(selected.size === items.length ? new Set() : new Set(items.map((i) => i.id)))}>
                {selected.size === items.length ? "Select none" : "Select all"}
              </Button>
              <Button variant="danger" icon={Trash2} disabled={selected.size === 0} onClick={() => setConfirm(items.filter((i) => selected.has(i.id)))}>
                Delete {selected.size || ""}
              </Button>
              <Button variant="ghost" icon={X} onClick={() => { setSelecting(false); setSelected(new Set()); }}>Done</Button>
            </>
          ) : (
            <>
              {items.length > 0 ? <Button icon={CheckSquare} onClick={() => setSelecting(true)}>Select</Button> : null}
              <Button variant="primary" icon={ImagePlus} onClick={() => input.current?.click()} loading={Boolean(state && !state.done)}>Add Images</Button>
            </>
          )
        }
      />
      <input ref={input} type="file" multiple accept={ACCEPT} className="sr-only" tabIndex={-1} aria-hidden onChange={(e) => send(Array.from(e.target.files ?? []))} />

      {state ? (
        <Card className="space-y-3 p-4" aria-live="polite">
          <div className="flex items-center justify-between gap-3 text-sm">
            <span className="font-medium">{state.done ? "Upload finished" : `Uploading ${state.total} image${state.total === 1 ? "" : "s"}…`}</span>
            {state.done ? <Button size="sm" variant="ghost" onClick={() => setState(null)}>Dismiss</Button> : <span className="num text-ink-muted">{Math.round(state.progress * 100)}%</span>}
          </div>
          <ProgressBar value={state.progress} label="Upload progress" />
          {state.done ? (
            <p className="text-[13px] text-ink-muted">
              {state.uploaded} added · {state.rejected.length} rejected. Features are computed once per new image and the classifier head is rebuilt automatically.
            </p>
          ) : null}
          {state.failure ? <Notice tone="red" title="Upload failed">{state.failure}</Notice> : null}
          {state.rejected.length > 0 ? (
            <Notice tone="amber" title={`${state.rejected.length} file(s) not added`}>
              <ul className="max-h-32 space-y-0.5 overflow-y-auto">
                {state.rejected.map((r, i) => <li key={`${r.filename}-${i}`}><span className="font-medium text-ink">{r.filename}</span>: {r.message}</li>)}
              </ul>
            </Notice>
          ) : null}
        </Card>
      ) : null}

      <Card
        onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        className={cn("relative transition-colors", dragging && "border-moss bg-moss-soft/40")}
      >
        {dragging ? (
          <div className="pointer-events-none absolute inset-0 z-10 flex flex-col items-center justify-center rounded-xl border-2 border-dashed border-moss bg-moss-soft/80 text-forest">
            <UploadCloud className="h-8 w-8" aria-hidden />
            <p className="mt-2 font-medium">Drop images to add them to {className}</p>
          </div>
        ) : null}
        {images.isLoading ? (
          <div className="grid grid-cols-2 gap-3 p-4 sm:grid-cols-4 lg:grid-cols-6">{Array.from({ length: 12 }, (_, i) => <Skeleton key={i} className="aspect-square" />)}</div>
        ) : items.length === 0 ? (
          <EmptyState
            className="m-4"
            icon={Images}
            title={`No images in ${className || "this class"} yet`}
            description="Drag photos here or choose files. JPEG, PNG, WebP and iPhone HEIC are accepted, up to 50 per batch."
            action={<Button variant="primary" icon={ImagePlus} onClick={() => input.current?.click()}>Add Images</Button>}
          />
        ) : (
          <ul className="grid grid-cols-2 gap-3 p-4 sm:grid-cols-4 lg:grid-cols-6">
            {items.map((image) => {
              const isSelected = selected.has(image.id);
              return (
                <li key={image.id}>
                  <button
                    type="button"
                    onClick={() => (selecting ? toggle(image.id) : setPreview(image))}
                    aria-pressed={selecting ? isSelected : undefined}
                    aria-label={selecting ? `${isSelected ? "Deselect" : "Select"} ${image.originalFilename}` : `Preview ${image.originalFilename}`}
                    className={cn(
                      "group relative block w-full overflow-hidden rounded-lg border bg-surface text-left shadow-card transition",
                      isSelected ? "border-forest ring-2 ring-forest" : "border-line hover:shadow-raised",
                    )}
                  >
                    <AuthImage src={`${base}/${image.id}/thumbnail`} alt="" className="aspect-square w-full" />
                    {selecting ? (
                      <span className={cn("absolute left-2 top-2 flex h-6 w-6 items-center justify-center rounded-md border shadow-card", isSelected ? "border-forest bg-forest text-cream" : "border-line-strong bg-surface/90")}>
                        {isSelected ? <CheckSquare className="h-4 w-4" aria-hidden /> : null}
                      </span>
                    ) : null}
                    {image.embeddingStatus !== "READY" ? <span className="absolute right-2 top-2"><StatusBadge status={image.embeddingStatus} label="Features pending" /></span> : null}
                    <span className="block truncate px-2 py-1.5 text-xs text-ink-muted">{image.originalFilename}</span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </Card>

      <Dialog
        open={Boolean(preview)}
        onClose={() => setPreview(null)}
        size="lg"
        title={preview?.originalFilename ?? ""}
        footer={
          <>
            <Button variant="ghost" icon={Trash2} className="text-danger hover:bg-danger-soft hover:text-danger sm:mr-auto" onClick={() => preview && setConfirm([preview])}>Delete image</Button>
            <Button onClick={() => setPreview(null)}>Close</Button>
          </>
        }
      >
        {preview ? (
          <div className="space-y-4">
            <AuthImage src={`${base}/${preview.id}/file`} alt={preview.originalFilename} fit="contain" className="max-h-[60vh] w-full rounded-lg bg-sand" />
            <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <DataItem label="Uploaded">{formatDateTime(preview.createdAt)}</DataItem>
              <DataItem label="Size" mono>{formatBytes(preview.fileSize)}</DataItem>
              <DataItem label="Dimensions" mono>{preview.width && preview.height ? `${preview.width}×${preview.height}` : "—"}</DataItem>
              <DataItem label="DINOv2 feature"><StatusBadge status={preview.embeddingStatus} /></DataItem>
            </dl>
          </div>
        ) : null}
      </Dialog>

      <ConfirmDialog
        open={Boolean(confirm)}
        onCancel={() => setConfirm(null)}
        busy={deleting}
        title={confirm && confirm.length === 1 ? "Delete this training image?" : `Delete ${confirm?.length ?? 0} training images?`}
        confirmLabel="Delete"
        onConfirm={() => confirm && deleteImages(confirm)}
      >
        <p>The image{confirm && confirm.length > 1 ? "s" : ""} and cached DINOv2 feature{confirm && confirm.length > 1 ? "s are" : " is"} removed from the training dataset. The classifier is rebuilt automatically; the current model stays active until then.</p>
      </ConfirmDialog>
    </div>
  );
}
