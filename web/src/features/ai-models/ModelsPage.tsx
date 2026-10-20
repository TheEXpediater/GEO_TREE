import { BarChart3, GitBranch, History, Info } from "lucide-react";

import { useJobs, useModelStatus, useModelVersions, useRetrain } from "@/api/hooks";
import type { ModelView } from "@/api/types";
import { ConfusionMatrix } from "@/components/charts/Charts";
import { Badge, Card, CardHeader, EmptyState, ErrorState, Notice, PageHeader, Skeleton, StatusBadge, errorMessage } from "@/components/ui/Display";
import { Table, Td, Th } from "@/components/ui/Navigation";
import { useToast } from "@/components/ui/Toast";
import { formatCount, formatDateTime, formatPercent, humanize } from "@/lib/format";

import { ModelSummary } from "../classifiers/ClassifiersPage";

function Metrics({ model }: { model: ModelView }) {
  return (
    <div className="grid gap-4 xl:grid-cols-2">
      <Card>
        <CardHeader icon={BarChart3} title={`Per-class validation metrics · v${model.modelVersion}`} description={`${formatCount(model.trainCount)} training / ${formatCount(model.validationCount)} validation images`} />
        <Table label="Per-class metrics">
          <thead>
            <tr>
              <Th>Class</Th>
              <Th align="right">Precision</Th>
              <Th align="right">Recall</Th>
              <Th align="right">F1</Th>
              <Th align="right">Val. images</Th>
              <Th align="right">Train images</Th>
            </tr>
          </thead>
          <tbody>
            {model.metrics.perClass.map((row) => {
              const mapping = model.classes.find((c) => c.classId === row.classId);
              return (
                <tr key={row.classId}>
                  <Td className="font-medium">{row.className}</Td>
                  <Td align="right" className="num font-mono text-[13px]">{formatPercent(row.precision)}</Td>
                  <Td align="right" className="num font-mono text-[13px]">{formatPercent(row.recall)}</Td>
                  <Td align="right" className="num font-mono text-[13px]">{formatPercent(row.f1)}</Td>
                  <Td align="right" className="num">{row.support}</Td>
                  <Td align="right" className="num text-ink-muted">{mapping?.trainCount ?? "—"}</Td>
                </tr>
              );
            })}
            <tr className="bg-cream/60">
              <Td className="font-semibold">Macro average</Td>
              <Td align="right" className="num font-mono text-[13px] font-semibold">{formatPercent(model.metrics.macroPrecision)}</Td>
              <Td align="right" className="num font-mono text-[13px] font-semibold">{formatPercent(model.metrics.macroRecall)}</Td>
              <Td align="right" className="num font-mono text-[13px] font-semibold">{formatPercent(model.metrics.macroF1)}</Td>
              <Td align="right" className="num font-semibold">{model.validationCount}</Td>
              <Td align="right" className="num text-ink-muted">{model.trainCount}</Td>
            </tr>
          </tbody>
        </Table>
      </Card>
      <Card>
        <CardHeader icon={BarChart3} title="Confusion matrix" description={`Validation split · overall accuracy ${formatPercent(model.metrics.accuracy)}`} />
        <div className="p-5">
          <ConfusionMatrix labels={model.metrics.confusionMatrix.labels} matrix={model.metrics.confusionMatrix.matrix} />
          <p className="mt-3 text-xs text-ink-faint">Rows are the true class; columns the predicted class. The diagonal (outlined) counts correct predictions.</p>
        </div>
      </Card>
    </div>
  );
}

export function ModelsPage() {
  const status = useModelStatus();
  const versions = useModelVersions();
  const jobs = useJobs();
  const retrain = useRetrain();
  const toast = useToast();

  if (status.error) return (
    <div className="space-y-6">
      <PageHeader eyebrow="Leaf classifier" title="Models & training" />
      <ErrorState error={status.error} onRetry={() => status.refetch()} />
    </div>
  );

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Leaf classifier"
        title="Models & training"
        description="Frozen DINOv2 ViT-S/14 features with a lightweight classifier head. Each training run creates a new version; it is activated only if validation succeeds."
      />
      {status.isLoading || !status.data ? (
        <Skeleton className="h-44" />
      ) : (
        <ModelSummary
          status={status.data}
          retraining={retrain.isPending}
          onRetrain={async () => {
            try {
              await retrain.mutateAsync();
              toast.success("Rebuild queued");
            } catch (error) {
              toast.error("Couldn't start training", errorMessage(error));
            }
          }}
        />
      )}
      <Notice tone="sky" icon={Info} title="How to read these numbers">
        Metrics come from a deterministic validation split of the training dataset (grouped by image hash so exact duplicates never straddle the split).
        They describe this dataset, not guaranteed field accuracy; near-duplicate photos from the same session can inflate them.
      </Notice>
      {status.data?.activeModel ? <Metrics model={status.data.activeModel} /> : status.data ? (
        <EmptyState icon={BarChart3} title="No active model" description="Add at least the minimum number of images to every class, then train the model." />
      ) : null}

      <Card>
        <CardHeader icon={GitBranch} title="Version history" description="Older versions stay listed; only one is active" />
        {versions.isLoading ? <div className="p-4"><Skeleton className="h-24" /></div> : versions.error ? <ErrorState className="m-4" error={versions.error} /> : !versions.data?.items.length ? (
          <p className="px-5 py-6 text-sm text-ink-faint">No model versions yet.</p>
        ) : (
          <Table label="Model versions">
            <thead>
              <tr>
                <Th>Version</Th>
                <Th>State</Th>
                <Th>Classes</Th>
                <Th align="right">Accuracy</Th>
                <Th align="right">Macro F1</Th>
                <Th align="right">Train / Val</Th>
                <Th>Trained</Th>
              </tr>
            </thead>
            <tbody>
              {versions.data.items.map((v) => (
                <tr key={v.modelVersion}>
                  <Td className="num font-mono font-semibold">v{v.modelVersion}</Td>
                  <Td>{v.active ? <Badge tone="forest">Active</Badge> : <StatusBadge status={v.status === "READY" ? "RETIRED" : v.status} label={v.status === "READY" ? "Retired" : undefined} />}
                    {v.invalidatedReason ? <p className="mt-1 max-w-[16rem] text-xs text-ink-faint">{v.invalidatedReason}</p> : null}
                  </Td>
                  <Td className="text-[13px] text-ink-muted">{v.classes.map((c) => c.name).join(", ")}</Td>
                  <Td align="right" className="num font-mono text-[13px]">{formatPercent(v.metrics.accuracy)}</Td>
                  <Td align="right" className="num font-mono text-[13px]">{formatPercent(v.metrics.macroF1)}</Td>
                  <Td align="right" className="num text-[13px]">{v.trainCount} / {v.validationCount}</Td>
                  <Td className="whitespace-nowrap text-[13px] text-ink-muted">{formatDateTime(v.trainedAt)}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <Card>
        <CardHeader icon={History} title="Training jobs" description="One job runs at a time; dataset changes within the debounce window are combined" />
        {jobs.isLoading ? <div className="p-4"><Skeleton className="h-24" /></div> : jobs.error ? <ErrorState className="m-4" error={jobs.error} /> : !jobs.data?.items.length ? (
          <p className="px-5 py-6 text-sm text-ink-faint">No training jobs yet.</p>
        ) : (
          <Table label="Training jobs">
            <thead>
              <tr>
                <Th>Created</Th>
                <Th>Status</Th>
                <Th>Trigger</Th>
                <Th align="right">Changes</Th>
                <Th align="right">Features computed / reused</Th>
                <Th>Result</Th>
              </tr>
            </thead>
            <tbody>
              {jobs.data.items.map((job) => (
                <tr key={job.jobId}>
                  <Td className="whitespace-nowrap text-[13px]">{formatDateTime(job.createdAt)}</Td>
                  <Td><StatusBadge status={job.status} /></Td>
                  <Td className="text-[13px] text-ink-muted">{humanize(job.trigger)}</Td>
                  <Td align="right" className="num">{job.coalescedChanges ?? 1}</Td>
                  <Td align="right" className="num text-[13px]">{job.embeddingsComputed ?? "—"} / {job.embeddingsReused ?? "—"}</Td>
                  <Td className="max-w-sm text-[13px] text-ink-muted">
                    {job.status === "COMPLETE" ? `Model v${job.modelVersion} · ${formatDateTime(job.finishedAt)}` : job.errorMessage ?? (job.status === "QUEUED" ? `Runs after ${formatDateTime(job.runAfter)}` : "—")}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
    </div>
  );
}
