import { BrainCircuit, Images, Layers, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";

import { ApiError } from "@/api/client";
import { useClassifiers, useDeleteClass, useModelStatus, useRetrain, useSaveClass } from "@/api/hooks";
import type { ClassifierClass, ModelStatus } from "@/api/types";
import { Button } from "@/components/ui/Button";
import { Badge, Card, EmptyState, ErrorState, Notice, PageHeader, ProgressBar, Skeleton, StatusBadge, errorMessage } from "@/components/ui/Display";
import { Field, Input } from "@/components/ui/Form";
import { OverflowMenu } from "@/components/ui/Navigation";
import { ConfirmDialog, Dialog } from "@/components/ui/Overlay";
import { useToast } from "@/components/ui/Toast";
import { formatCount, formatDateTime, formatPercent, formatRelative, humanize } from "@/lib/format";

export function ModelSummary({ status, onRetrain, retraining }: { status: ModelStatus; onRetrain: () => void; retraining: boolean }) {
  const active = status.activeModel;
  const busy = status.status === "TRAINING" || status.status === "QUEUED";
  return (
    <Card className="overflow-hidden">
      <div className="flex flex-col gap-5 p-5 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex items-start gap-4">
          <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-forest text-cream">
            <BrainCircuit className="h-5 w-5" aria-hidden />
          </span>
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="text-lg font-semibold">Tamarind Leaf Classifier</h2>
              <StatusBadge status={status.status} />
              {status.stale && status.status === "READY" ? <Badge tone="amber">Dataset changed</Badge> : null}
            </div>
            <p className="mt-0.5 text-[13px] text-ink-muted">
              {status.backbone.displayName} (frozen, {status.backbone.embeddingDimension}-d features, {status.backbone.imageSize}px) + lightweight classifier head
            </p>
          </div>
        </div>
        <Button variant="primary" icon={RefreshCw} onClick={onRetrain} loading={retraining} disabled={busy || !status.readiness.canTrain}>
          {busy ? humanize(status.status) : "Train / Rebuild Model"}
        </Button>
      </div>
      <dl className="grid grid-cols-2 border-t border-line sm:grid-cols-3 lg:grid-cols-6">
        {[
          ["Model version", active ? `v${active.modelVersion}` : "—"],
          ["Dataset images", formatCount(status.datasetImageCount)],
          ["Last trained", active ? formatRelative(active.trainedAt) : "never"],
          ["Validation accuracy", formatPercent(active?.metrics.accuracy)],
          ["Macro F1", formatPercent(active?.metrics.macroF1)],
          ["Validation images", active ? formatCount(active.validationCount) : "—"],
        ].map(([label, value], index) => (
          <div key={label} className={`px-5 py-3 ${index > 0 ? "border-l border-line" : ""} max-sm:[&:nth-child(odd)]:border-l-0`}>
            <dt className="text-2xs uppercase tracking-wider text-ink-faint">{label}</dt>
            <dd className="num mt-0.5 font-semibold">{value}</dd>
          </div>
        ))}
      </dl>
      {busy && status.currentJob ? (
        <div className="space-y-2 border-t border-line bg-sky-soft/40 px-5 py-3 text-[13px]">
          <p className="text-ink-muted">
            {status.status === "QUEUED" ? `Queued (${humanize(status.currentJob.trigger)}); changes made within the debounce window are combined into this one job.` : "Training the classifier head from cached DINOv2 features. The previous model stays active until this finishes."}
          </p>
          {status.status === "TRAINING" ? <div className="h-1.5 overflow-hidden rounded-full bg-sky/15"><div className="h-full w-1/3 animate-pulse rounded-full bg-sky" /></div> : null}
        </div>
      ) : null}
      {status.resourceGuard.deferred && status.resourceGuard.message ? (
        <div className="border-t border-line px-5 py-3"><Notice tone="amber" title="Training postponed">{status.resourceGuard.message}</Notice></div>
      ) : null}
      {status.lastError ? (
        <div className="border-t border-line px-5 py-3"><Notice tone="red" title="The last training job failed">{status.lastError} The previous model stays active.</Notice></div>
      ) : null}
      {!status.readiness.canTrain && status.readiness.problems.length > 0 ? (
        <div className="border-t border-line px-5 py-3">
          <Notice tone="amber" title="Not ready to train">
            <ul className="list-disc space-y-0.5 pl-4">{status.readiness.problems.map((p) => <li key={p}>{p}</li>)}</ul>
          </Notice>
        </div>
      ) : null}
    </Card>
  );
}

function ClassCard({ item, minImages, onRename, onDelete }: { item: ClassifierClass; minImages: number; onRename: () => void; onDelete: () => void }) {
  const navigate = useNavigate();
  const progress = Math.min(1, item.imageCount / Math.max(1, minImages));
  return (
    <Card className="flex flex-col p-5 transition-shadow hover:shadow-raised">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <Link to={`/classifiers/${item.id}`} className="block truncate text-[15px] font-semibold hover:text-forest">{item.name}</Link>
          <p className="mt-0.5 text-xs text-ink-faint">{item.inActiveModel ? "In the active model" : "Not in the active model yet"}</p>
        </div>
        <OverflowMenu
          label={`Actions for ${item.name}`}
          items={[
            { label: "Rename Classifier", icon: Pencil, onSelect: onRename },
            { label: "Manage Images", icon: Images, onSelect: () => navigate(`/classifiers/${item.id}`) },
            { label: "Delete Classifier", icon: Trash2, danger: true, onSelect: onDelete },
          ]}
        />
      </div>
      <p className="num mt-4 text-3xl font-semibold leading-none">{formatCount(item.imageCount)}</p>
      <p className="mt-1 text-[13px] text-ink-muted">training images</p>
      <div className="mt-4 space-y-2">
        {item.imageCount < minImages ? (
          <>
            <ProgressBar value={progress} label={`${item.name}: ${item.imageCount} of ${minImages} required images`} tone="clay" />
            <p className="text-xs text-clay">{minImages - item.imageCount} more needed (minimum {minImages})</p>
          </>
        ) : item.embeddedCount < item.imageCount ? (
          <p className="text-xs text-amber">{item.imageCount - item.embeddedCount} image(s) waiting for DINOv2 features</p>
        ) : null}
        <StatusBadge status={item.status} />
      </div>
    </Card>
  );
}

function ClassNameDialog({ open, item, onClose }: { open: boolean; item: ClassifierClass | null; onClose: () => void }) {
  const save = useSaveClass();
  const toast = useToast();
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (open) {
      setName(item?.name ?? "");
      setError(null);
    }
  }, [open, item]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!name.trim()) return setError("Enter a class name.");
    try {
      await save.mutateAsync({ id: item?.id, name: name.trim() });
      toast.success(item ? "Classifier renamed" : "Classifier added", item ? "The active model maps class IDs, so it stays valid." : "Add training images, then rebuild the model to include it.");
      onClose();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) setError(err.message);
      else toast.error("Couldn't save the classifier", errorMessage(err));
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      size="sm"
      title={item ? "Rename Classifier" : "Add Classifier"}
      description={item ? `Current name: ${item.name}` : "Adds a classification label (e.g. Healthy, Spotting, Powdery Mildew) to the one tamarind leaf classifier. It is not a new model."}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" form="class-form" variant="primary" loading={save.isPending}>Save</Button>
        </>
      }
    >
      <form id="class-form" onSubmit={submit} noValidate>
        <Field label="Class name" required error={error}>
          {(p) => <Input {...p} value={name} onChange={(e) => { setName(e.target.value); setError(null); }} maxLength={80} placeholder="e.g. Leaf Spot" data-autofocus />}
        </Field>
      </form>
    </Dialog>
  );
}

export function ClassifiersPage() {
  const classes = useClassifiers();
  const status = useModelStatus();
  const retrain = useRetrain();
  const remove = useDeleteClass();
  const toast = useToast();
  const [naming, setNaming] = useState<{ open: boolean; item: ClassifierClass | null }>({ open: false, item: null });
  const [deleting, setDeleting] = useState<ClassifierClass | null>(null);

  async function startTraining() {
    try {
      await retrain.mutateAsync();
      toast.success("Rebuild queued", "The classifier head is retrained from cached features; the current model stays active meanwhile.");
    } catch (error) {
      toast.error("Couldn't start training", errorMessage(error));
    }
  }

  const error = classes.error ?? status.error;
  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Leaf classifier"
        title="Classifiers"
        description="One DINOv2 tamarind-leaf classifier with editable classes. Upload images per class; the model rebuilds automatically after dataset changes."
        actions={<Button variant="primary" icon={Plus} onClick={() => setNaming({ open: true, item: null })} disabled={Boolean(error)}>Add Classifier</Button>}
      />
      {error ? (
        <ErrorState error={error} onRetry={() => { classes.refetch(); status.refetch(); }} />
      ) : (
        <>
          {status.isLoading || !status.data ? <Skeleton className="h-44" /> : <ModelSummary status={status.data} onRetrain={startTraining} retraining={retrain.isPending} />}
          {classes.isLoading || !classes.data ? (
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-48" />)}</div>
          ) : classes.data.items.length === 0 ? (
            <EmptyState icon={Layers} title="No classes yet" description="Add the leaf conditions to recognise, for example Healthy, Spotting and Powdery Mildew." action={<Button variant="primary" icon={Plus} onClick={() => setNaming({ open: true, item: null })}>Add Classifier</Button>} />
          ) : (
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
              {classes.data.items.map((item) => (
                <ClassCard key={item.id} item={item} minImages={classes.data.minImagesPerClass}
                  onRename={() => setNaming({ open: true, item })} onDelete={() => setDeleting(item)} />
              ))}
            </div>
          )}
          {status.data?.activeModel ? (
            <p className="text-[13px] text-ink-muted">
              Active model v{status.data.activeModel.modelVersion} trained {formatDateTime(status.data.activeModel.trainedAt)}. <Link to="/models" className="font-medium text-forest hover:underline">See metrics and version history</Link>
            </p>
          ) : null}
        </>
      )}
      <ClassNameDialog open={naming.open} item={naming.item} onClose={() => setNaming({ open: false, item: null })} />
      <ConfirmDialog
        open={Boolean(deleting)}
        onCancel={() => setDeleting(null)}
        busy={remove.isPending}
        title={`Delete classifier "${deleting?.name}"?`}
        confirmLabel="Delete Classifier"
        onConfirm={async () => {
          if (!deleting) return;
          try {
            const result = await remove.mutateAsync(deleting.id);
            toast.success("Classifier deleted", `${result.deletedImages} training image(s) removed.${result.modelInvalidated ? " The active model was invalidated; rebuild it." : ""}`);
            setDeleting(null);
          } catch (error) {
            toast.error("Couldn't delete the classifier", errorMessage(error));
          }
        }}
      >
        <p>This permanently deletes the class and its {formatCount(deleting?.imageCount ?? 0)} training images with their cached DINOv2 features.</p>
        <p>The class set changes, so {deleting?.inActiveModel ? "the active model that uses this class is invalidated and " : ""}the model must be rebuilt. Recorded leaf assessments are not changed.</p>
      </ConfirmDialog>
    </div>
  );
}
