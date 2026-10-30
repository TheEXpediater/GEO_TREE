import { Database, Download, FileArchive, FileText, Info, Trash2, Upload } from "lucide-react";
import { useRef, useState } from "react";

import { downloadProtectedFile } from "@/api/client";
import { useDatasetProfile, useDiscardArchive, useUploadArchive } from "@/api/hooks";
import type { DatasetArchive } from "@/api/types";
import { Button } from "@/components/ui/Button";
import { Badge, Card, CardHeader, DataItem, ErrorState, Notice, ProgressBar, Skeleton, StatusBadge, errorMessage } from "@/components/ui/Display";
import { Table, Td, Th } from "@/components/ui/Navigation";
import { ConfirmDialog, Drawer } from "@/components/ui/Overlay";
import { useToast } from "@/components/ui/Toast";
import { formatBytes, formatCount, formatDateTime, humanize } from "@/lib/format";

import { shortHash } from "./ReleaseTab";

const CLASSES = ["Healthy", "Spotting", "Powdery Mildew"];
const EXCLUDED_STATUSES = ["duplicate", "conflict", "unreadable", "unsupported", "unknown_label", "unsafe_path", "unsafe_compression", "too_large", "ignored"];

function CountTable({ label, rows, columns }: { label: string; rows: Array<[string, Record<string, number>]>; columns: string[] }) {
  return (
    <Table label={label}>
      <thead>
        <tr>
          <Th>{label}</Th>
          {columns.map((c) => <Th key={c} align="right">{c}</Th>)}
          <Th align="right">Total</Th>
        </tr>
      </thead>
      <tbody>
        {rows.map(([name, counts]) => (
          <tr key={name}>
            <Td className="font-medium">{name}</Td>
            {columns.map((c) => <Td key={c} align="right" className="num">{formatCount(counts[c] ?? 0)}</Td>)}
            <Td align="right" className="num font-semibold">{formatCount(Object.values(counts).reduce((a, b) => a + b, 0))}</Td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function ArchiveReport({ archive, onClose }: { archive: DatasetArchive; onClose: () => void }) {
  const toast = useToast();
  const s = archive.summary;
  return (
    <Drawer
      open
      onClose={onClose}
      title={archive.fileName}
      subtitle={`${formatBytes(archive.bytes)} · SHA-256 ${archive.sha256}`}
      footer={
        s ? (
          <Button icon={Download} onClick={() => downloadProtectedFile(`/api/v1/admin/mobile/dataset/archives/${archive.id}/manifest.csv`, `dataset-archive-${archive.id.slice(0, 8)}-manifest.csv`).catch((e) => toast.error("Couldn't download", errorMessage(e)))}>
            Download manifest (CSV)
          </Button>
        ) : undefined
      }
    >
      <div className="space-y-5">
        <dl className="grid gap-4 sm:grid-cols-2">
          <DataItem label="Status"><StatusBadge status={archive.status} /></DataItem>
          <DataItem label="Uploaded">{formatDateTime(archive.uploadedAt)} · {archive.uploadedBy?.email ?? "—"}</DataItem>
          <DataItem label="Validated">{formatDateTime(archive.validatedAt)}</DataItem>
          <DataItem label="Origin">{archive.origin === "upload" ? "Web upload" : "Registered server file"}</DataItem>
        </dl>
        {archive.error ? <Notice tone="red" title="Not accepted">{archive.error}</Notice> : null}
        {s ? (
          <>
            <dl className="grid gap-4 sm:grid-cols-3">
              <DataItem label="Files in archive">{formatCount(s.entries)}</DataItem>
              <DataItem label="Image files">{formatCount(s.imageFiles)} ({Object.entries(s.formats).map(([f, n]) => `${n} ${f.toUpperCase()}`).join(", ") || "—"})</DataItem>
              <DataItem label="Accepted">{formatCount(s.accepted)}</DataItem>
              <DataItem label="Expanded size">{formatBytes(s.uncompressedBytes)}</DataItem>
              <DataItem label="Already in active dataset">{formatCount(s.alreadyInActiveDataset)}</DataItem>
              <DataItem label="Image decoding">{s.decodeChecked ? (s.heicDecodeChecked ? "Every image fully decoded" : "Decoded (HEIC: signature only)") : "File signatures only (no decoder on this server)"}</DataItem>
            </dl>
            <CountTable label="Class" columns={["accepted", "duplicate", "conflict", "unreadable"]} rows={CLASSES.map((c) => [c, s.byClass[c] ?? {}])} />
            <div>
              <p className="mb-2 text-2xs font-medium uppercase tracking-wider text-ink-faint">Excluded or flagged files</p>
              <div className="flex flex-wrap gap-2">
                {EXCLUDED_STATUSES.filter((k) => s.statusCounts[k]).map((k) => <Badge key={k} tone={k === "conflict" || k.startsWith("unsafe") ? "red" : "amber"}>{humanize(k)}: {s.statusCounts[k]}</Badge>)}
                {!EXCLUDED_STATUSES.some((k) => s.statusCounts[k]) ? <span className="text-sm text-ink-muted">None</span> : null}
              </div>
              {s.conflictFamilies ? <p className="mt-2 text-xs text-ink-muted">{s.conflictFamilies} identical file(s) appear under different labels. They are excluded until someone decides the correct label; the manifest lists them.</p> : null}
            </div>
            {s.remappedLabels.length ? (
              <div>
                <p className="mb-1 text-2xs font-medium uppercase tracking-wider text-ink-faint">Label mapping applied</p>
                <ul className="text-sm">{s.remappedLabels.map((r) => <li key={r.from}>{r.from} → {r.to}: {r.count}</li>)}</ul>
              </div>
            ) : null}
            {s.unknownLabels.length ? <Notice tone="amber" title="Unknown folder labels (not mapped)">{s.unknownLabels.join(", ")}</Notice> : null}
            <CountTable label="Source folder" columns={CLASSES} rows={Object.entries(s.acceptedBySource)} />
            <p className="text-xs text-ink-faint">{s.labelPolicy}</p>
          </>
        ) : null}
        {archive.split ? (
          <CountTable label={`Split (${archive.split.manifestFile})`} columns={["train_val", "test", "excluded"]} rows={Object.entries(archive.split.perClass)} />
        ) : null}
      </div>
    </Drawer>
  );
}

function UploadCard({ maxBytes }: { maxBytes: number }) {
  const upload = useUploadArchive();
  const toast = useToast();
  const input = useRef<HTMLInputElement>(null);
  const [progress, setProgress] = useState<number | null>(null);

  async function send(file: File) {
    if (file.size > maxBytes) {
      toast.error("Archive too large", `The limit is ${formatBytes(maxBytes)}.`);
      return;
    }
    setProgress(0);
    try {
      await upload.mutateAsync({ file, onProgress: setProgress });
      toast.success("Archive uploaded", "Validation is running; the result appears below.");
    } catch (error) {
      toast.error("Upload failed", errorMessage(error));
    } finally {
      setProgress(null);
      if (input.current) input.current.value = "";
    }
  }

  return (
    <Card>
      <CardHeader icon={Upload} title="Upload a dataset archive" description={`ZIP with one folder per class (Healthy, Spotting, Powdery Mildew; Mold maps to Powdery Mildew). Up to ${formatBytes(maxBytes)}.`} />
      <div className="space-y-3 px-5 pb-5">
        <input
          ref={input}
          type="file"
          accept=".zip,application/zip"
          aria-label="Dataset archive (ZIP)"
          disabled={upload.isPending}
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) void send(file);
          }}
          className="block w-full text-sm file:mr-3 file:rounded-lg file:border file:border-line-strong file:bg-surface file:px-3 file:py-1.5 file:text-sm file:font-medium"
        />
        {progress !== null ? <ProgressBar value={progress} label="Upload progress" /> : null}
        <p className="text-xs text-ink-faint">
          Uploading only validates and stages the archive in private storage. It never changes the training dataset, never starts training and never changes installed apps.
        </p>
      </div>
    </Card>
  );
}

export function DatasetTab() {
  const profile = useDatasetProfile();
  const discard = useDiscardArchive();
  const toast = useToast();
  const [viewing, setViewing] = useState<DatasetArchive | null>(null);
  const [discarding, setDiscarding] = useState<DatasetArchive | null>(null);
  if (profile.isLoading) return <Skeleton className="h-96" />;
  if (profile.error || !profile.data) return <ErrorState error={profile.error} onRetry={() => profile.refetch()} />;
  const data = profile.data;
  const active = data.activeArchive;
  const training = data.activeTrainingImages;

  return (
    <div className="space-y-6">
      <div className="grid gap-6 xl:grid-cols-2">
        <Card>
          <CardHeader icon={Database} title="Active training dataset" description={training.note} />
          <div className="space-y-4 px-5 pb-5">
            <dl className="grid gap-4 sm:grid-cols-3">
              <DataItem label="Training images (train + validation)">{formatCount(training.total)}</DataItem>
              <DataItem label="Used by active model">{data.activeModel ? `v${data.activeModel.modelVersion}: ${formatCount(data.activeModel.trainCount)} train / ${formatCount(data.activeModel.validationCount)} validation` : "No active model"}</DataItem>
              <DataItem label="Leaf-assessment photos (separate)">{formatCount(data.leafAssessmentPhotos)}</DataItem>
            </dl>
            <CountTable label="Class" columns={["images"]} rows={Object.entries(training.byClass).map(([c, n]) => [c, { images: n }])} />
            <p className="text-xs text-ink-faint">{data.leafAssessmentNote}</p>
          </div>
        </Card>

        <Card>
          <CardHeader icon={FileArchive} title="Source archive of the active dataset" description="Provenance of the images above" />
          {active ? (
            <div className="space-y-4 px-5 pb-5">
              <dl className="grid gap-4 sm:grid-cols-2">
                <DataItem label="Archive">{active.fileName} · {formatBytes(active.bytes)}</DataItem>
                <DataItem label="SHA-256" mono>{active.sha256}</DataItem>
                <DataItem label="Activated">{active.activation?.activatedAt ? formatDateTime(active.activation.activatedAt) : "Date not recorded"}</DataItem>
                <DataItem label="Archive images accepted">{formatCount(active.summary?.accepted)} of {formatCount(active.summary?.imageFiles)}</DataItem>
                {data.consistency ? (
                  <div className="sm:col-span-2">
                    <DataItem label="Consistency">
                      {data.consistency.notInActiveArchive === 0
                        ? `All ${data.consistency.activeImages} training images come from this archive.`
                        : `${data.consistency.notInActiveArchive} of ${data.consistency.activeImages} training images are NOT in this archive.`}
                    </DataItem>
                  </div>
                ) : null}
              </dl>
              {active.split ? <CountTable label="Split" columns={["train_val", "test", "excluded"]} rows={Object.entries(active.split.perClass)} /> : null}
              <Button size="sm" icon={FileText} onClick={() => setViewing(active)}>Validation report</Button>
            </div>
          ) : (
            <p className="px-5 pb-5 text-sm text-ink-muted">No archive is recorded as the source of the active dataset on this server.</p>
          )}
        </Card>
      </div>

      <Notice tone="sky" icon={Info} title="Activating a new dataset is a separate, controlled step">
        A validated archive does not replace the training data by itself. Activation (backup, replacement, grouped train/validation/test split, retraining, evaluation)
        runs with <code>scripts/replace_classifier_dataset.py</code> on the AI backend, one job at a time. A retrained model then needs ONNX export, parity checks and a new APK release before phones use it.
      </Notice>

      {data.uploadsEnabled ? <UploadCard maxBytes={data.maxUploadBytes} /> : (
        <Notice tone="amber" title="Archive uploads are disabled on this server">Set DATASET_ARCHIVE_DIR to a private, persistent GEO Tree folder to enable them.</Notice>
      )}

      <Card>
        <CardHeader icon={FileArchive} title="Dataset archives" description="Newest first; staged archives can be discarded, active and retired ones are kept for rollback" />
        {!data.archives.length ? <p className="px-5 py-6 text-sm text-ink-faint">No archives yet.</p> : (
          <Table label="Dataset archives">
            <thead>
              <tr>
                <Th>Archive</Th>
                <Th>Status</Th>
                <Th>Uploaded</Th>
                <Th align="right">Accepted / images</Th>
                <Th align="right">Duplicates / conflicts</Th>
                <Th><span className="sr-only">Actions</span></Th>
              </tr>
            </thead>
            <tbody>
              {data.archives.map((a) => (
                <tr key={a.id}>
                  <Td>
                    <p className="font-medium">{a.fileName}</p>
                    <p className="num font-mono text-[12px] text-ink-faint">{shortHash(a.sha256)} · {formatBytes(a.bytes)}</p>
                  </Td>
                  <Td><StatusBadge status={a.status} /></Td>
                  <Td className="whitespace-nowrap text-[13px]">{formatDateTime(a.uploadedAt)}</Td>
                  <Td align="right" className="num">{a.summary ? `${a.summary.accepted} / ${a.summary.imageFiles}` : "—"}</Td>
                  <Td align="right" className="num">{a.summary ? `${a.summary.statusCounts.duplicate ?? 0} / ${a.summary.statusCounts.conflict ?? 0}` : "—"}</Td>
                  <Td align="right">
                    <div className="flex justify-end gap-1">
                      {a.status !== "VALIDATING" && a.status !== "DISCARDED" ? <Button size="sm" variant="ghost" icon={FileText} onClick={() => setViewing(a)}>Report</Button> : null}
                      {["READY", "REJECTED", "FAILED"].includes(a.status) ? (
                        <Button size="sm" variant="ghost" icon={Trash2} onClick={() => setDiscarding(a)} aria-label={`Discard ${a.fileName}`}>Discard</Button>
                      ) : null}
                    </div>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      {viewing ? <ArchiveReport archive={viewing} onClose={() => setViewing(null)} /> : null}
      <ConfirmDialog
        open={discarding !== null}
        onCancel={() => setDiscarding(null)}
        busy={discard.isPending}
        title="Discard this staged archive?"
        confirmLabel="Discard archive"
        onConfirm={async () => {
          if (!discarding) return;
          try {
            await discard.mutateAsync(discarding.id);
            toast.success("Archive discarded");
            setDiscarding(null);
          } catch (error) {
            toast.error("Couldn't discard", errorMessage(error));
          }
        }}
      >
        <p>The uploaded file and its validation manifest are deleted from server storage. The record stays in the list for the audit trail. The training dataset is not affected.</p>
      </ConfirmDialog>
    </div>
  );
}
