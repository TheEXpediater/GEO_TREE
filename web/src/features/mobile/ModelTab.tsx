import { BarChart3, BrainCircuit, Info, Server, Smartphone } from "lucide-react";

import { useModelProfile } from "@/api/hooks";
import type { EvaluationRecord, MetricRow } from "@/api/types";
import { ConfusionMatrix } from "@/components/charts/Charts";
import { Badge, Card, CardHeader, DataItem, EmptyState, ErrorState, Notice, Skeleton, StatusBadge } from "@/components/ui/Display";
import { Table, Td, Th } from "@/components/ui/Navigation";
import { formatCount, formatDateTime, formatPercent, humanize } from "@/lib/format";

import { shortHash } from "./ReleaseTab";

function PerClassTable({ rows, label }: { rows: MetricRow[]; label: string }) {
  if (!rows.length) return null;
  return (
    <Table label={label}>
      <thead>
        <tr>
          <Th>Class</Th>
          <Th align="right">Precision</Th>
          <Th align="right">Recall</Th>
          <Th align="right">F1</Th>
          <Th align="right">Images</Th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.name}>
            <Td className="font-medium">{r.name}</Td>
            <Td align="right" className="num font-mono text-[13px]">{formatPercent(r.precision)}</Td>
            <Td align="right" className="num font-mono text-[13px]">{formatPercent(r.recall)}</Td>
            <Td align="right" className="num font-mono text-[13px]">{formatPercent(r.f1)}</Td>
            <Td align="right" className="num">{r.support ?? "—"}</Td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function Evaluation({ evaluation }: { evaluation: EvaluationRecord }) {
  const cross = evaluation.type === "leave_one_source_out";
  const correct = evaluation.details?.correct;
  return (
    <Card>
      <CardHeader
        icon={BarChart3}
        title={`${evaluation.label} · model v${evaluation.modelVersion}`}
        description={`${formatCount(evaluation.samples)} images · ${evaluation.evaluatedAt ? `run ${formatDateTime(evaluation.evaluatedAt)}` : "run date not recorded"} · source ${evaluation.sourceFile ?? "—"} (${shortHash(evaluation.sourceSha256, 8)})`}
      />
      <div className="space-y-4 px-5 pb-5">
        {cross ? (
          <>
            <p className="text-sm">
              Accuracy ranged <strong>{formatPercent(evaluation.details.accuracyMin)}</strong> to <strong>{formatPercent(evaluation.details.accuracyMax)}</strong> across held-out source folders.
            </p>
            <Table label="Accuracy per held-out source">
              <thead><tr><Th>Held-out source</Th><Th align="right">Images</Th><Th align="right">Accuracy</Th><Th align="right">Macro F1</Th></tr></thead>
              <tbody>
                {Object.entries(evaluation.details.bySource ?? {}).map(([source, v]: [string, any]) => (
                  <tr key={source}>
                    <Td>{source}</Td>
                    <Td align="right" className="num">{v.samples}</Td>
                    <Td align="right" className="num font-mono text-[13px]">{formatPercent(v.accuracy)}</Td>
                    <Td align="right" className="num font-mono text-[13px]">{formatPercent(v.macroF1)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </>
        ) : (
          <>
            <dl className="grid gap-4 sm:grid-cols-4">
              <DataItem label="Accuracy">{formatPercent(evaluation.accuracy)}{correct !== undefined ? ` (${correct}/${evaluation.samples})` : ""}</DataItem>
              <DataItem label="Balanced accuracy">{formatPercent(evaluation.balancedAccuracy)}</DataItem>
              <DataItem label="Macro F1">{formatPercent(evaluation.macroF1)}</DataItem>
              <DataItem label="Images">{formatCount(evaluation.samples)}</DataItem>
            </dl>
            <div className="grid gap-4 xl:grid-cols-2">
              <PerClassTable rows={evaluation.perClass} label="Per-class metrics" />
              {evaluation.confusionMatrix?.matrix ? (
                <div>
                  <ConfusionMatrix labels={evaluation.confusionMatrix.labels} matrix={evaluation.confusionMatrix.matrix} />
                  <p className="mt-2 text-xs text-ink-faint">Rows are the true class; columns the predicted class.</p>
                </div>
              ) : null}
            </div>
          </>
        )}
        {evaluation.limitations ? <Notice tone="amber" title="Limitations">{evaluation.limitations}</Notice> : null}
      </div>
    </Card>
  );
}

export function ModelTab() {
  const profile = useModelProfile();
  if (profile.isLoading) return <Skeleton className="h-96" />;
  if (profile.error || !profile.data) return <ErrorState error={profile.error} onRetry={() => profile.refetch()} />;
  const { server, bundled, comparison, evaluations, latestJob } = profile.data;

  return (
    <div className="space-y-6">
      {profile.data.pipeline ? (
        <Notice tone="sky" icon={Info} title="One classifier, not one model per class">
          <p>{profile.data.pipeline}</p>
          <p className="mt-1">{profile.data.updateNote}</p>
        </Notice>
      ) : null}

      {comparison ? (
        <div className="flex flex-wrap gap-2" aria-label="Server and bundled model comparison">
          <Badge tone={comparison.sameVersion ? "green" : "amber"} dot>{comparison.sameVersion ? "Server and APK use the same model version" : "Server model differs from the APK's model"}</Badge>
          <Badge tone={comparison.sameClassOrder ? "green" : "red"} dot>{comparison.sameClassOrder ? "Same class order" : "Class order differs"}</Badge>
          <Badge tone={comparison.sameFeatureExtractor ? "green" : "red"} dot>{comparison.sameFeatureExtractor ? "Same DINOv2 preprocessing" : "Feature extractor differs"}</Badge>
        </div>
      ) : null}

      <div className="grid gap-6 xl:grid-cols-2">
        <Card>
          <CardHeader icon={Smartphone} title="Bundled in the served APK" description="What phones run offline (ONNX Runtime, no network needed)" />
          {bundled ? (
            <dl className="grid gap-4 px-5 pb-5 sm:grid-cols-2">
              <DataItem label="Backbone">{bundled.backboneDisplayName ?? bundled.backbone} · {bundled.embeddingDimension}-d features</DataItem>
              <DataItem label="Classifier head">v{bundled.modelVersion} · in APK {bundled.apkVersionName} ({bundled.apkVersionCode})</DataItem>
              <DataItem label={`Classes (${bundled.classes.length}, in output order)`}>{bundled.classes.join(", ")}</DataItem>
              <DataItem label="Confidence threshold">{bundled.confidenceThreshold ?? "—"}</DataItem>
              <DataItem label="Inference">{bundled.inference}</DataItem>
              <DataItem label="Bundle integrity">{bundled.integrityOk ? "Checksums match the export manifest" : "Checksums do NOT match the export manifest"}</DataItem>
              <DataItem label="Head SHA-256" mono>{bundled.headSha256}</DataItem>
              <DataItem label="ONNX SHA-256" mono>{bundled.onnxSha256}</DataItem>
              <div className="sm:col-span-2"><DataItem label="Preprocessing / cache key" mono>{bundled.embeddingModel}</DataItem></div>
            </dl>
          ) : (
            <p className="px-5 pb-5 text-sm text-ink-muted">{profile.data.bundledError ?? "No APK is served, or it has no bundled model."}</p>
          )}
        </Card>

        <Card>
          <CardHeader icon={Server} title="Active on the server" description={profile.data.serverAiRuntime ? "Trained from the current training dataset" : "Record only: this server does not run DINOv2 (AI disabled)"} />
          {server ? (
            <dl className="grid gap-4 px-5 pb-5 sm:grid-cols-2">
              <DataItem label="Backbone">{server.backboneDisplayName ?? server.backbone} · {server.embeddingDimension}-d features</DataItem>
              <DataItem label="Classifier head">v{server.modelVersion} · {humanize(server.head)}</DataItem>
              <DataItem label={`Classes (${server.classes.length})`}>{server.classes.join(", ")}</DataItem>
              <DataItem label="State"><StatusBadge status={server.status} /></DataItem>
              <DataItem label="Trained">{formatDateTime(server.trainedAt)}</DataItem>
              <DataItem label="Training / validation images">{formatCount(server.trainCount)} / {formatCount(server.validationCount)}</DataItem>
              <div className="sm:col-span-2"><DataItem label="Preprocessing / cache key" mono>{server.embeddingModel}</DataItem></div>
              {latestJob ? (
                <div className="sm:col-span-2">
                  <DataItem label="Latest training job">
                    {humanize(latestJob.status)} · {formatDateTime(latestJob.createdAt)}{latestJob.modelVersion ? ` · produced v${latestJob.modelVersion}` : ""}{latestJob.errorMessage ? ` · ${latestJob.errorMessage}` : ""}
                  </DataItem>
                </div>
              ) : null}
            </dl>
          ) : (
            <p className="px-5 pb-5 text-sm text-ink-muted">No active model is recorded in this server's database.</p>
          )}
        </Card>
      </div>

      {server?.validation ? (
        <Card>
          <CardHeader
            icon={BrainCircuit}
            title={`${server.validation.label} · model v${server.modelVersion}`}
            description={`${formatCount(server.validation.samples)} images · accuracy ${formatPercent(server.validation.accuracy)} · macro F1 ${formatPercent(server.validation.macroF1)}. Chosen from the same photo sessions as training, so it describes this dataset, not field accuracy.`}
          />
          <div className="grid gap-4 px-5 pb-5 xl:grid-cols-2">
            <PerClassTable rows={server.validation.perClass} label="Validation per-class metrics" />
            {server.validation.confusionMatrix?.matrix ? <ConfusionMatrix labels={server.validation.confusionMatrix.labels} matrix={server.validation.confusionMatrix.matrix} /> : null}
          </div>
        </Card>
      ) : null}

      {evaluations.length ? evaluations.map((e) => <Evaluation key={e.id} evaluation={e} />) : (
        <EmptyState icon={BarChart3} title="No held-out evaluation recorded" description="Locked-test and cross-source results appear here after they are recorded with scripts/record_model_evaluation.py." />
      )}
    </div>
  );
}
