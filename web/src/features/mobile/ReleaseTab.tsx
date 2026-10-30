import { CircleCheck, CircleMinus, CircleX, Copy, Download, ExternalLink, History, QrCode, Rocket, ShieldCheck, Smartphone } from "lucide-react";
import { useState } from "react";

import { useDatasetProfile, useMobileOverview, useRegisterRelease, useReleases } from "@/api/hooks";
import type { AppRelease, MobileOverview, ReleaseCheck } from "@/api/types";
import { useAuth } from "@/auth/AuthContext";
import { Button } from "@/components/ui/Button";
import { Badge, Card, CardHeader, DataItem, ErrorState, Notice, Skeleton, StatusBadge, errorMessage } from "@/components/ui/Display";
import { Field, Select, Textarea } from "@/components/ui/Form";
import { Pagination, Table, Td, Th } from "@/components/ui/Navigation";
import { Dialog } from "@/components/ui/Overlay";
import { useToast } from "@/components/ui/Toast";
import { formatBytes, formatDateTime, humanize } from "@/lib/format";

const HEALTH: Record<MobileOverview["health"], { tone: "green" | "amber" | "red" | "sky"; title: string }> = {
  HEALTHY: { tone: "green", title: "Download is healthy" },
  UNREGISTERED: { tone: "amber", title: "Served APK is not registered" },
  NOT_PUBLISHED: { tone: "amber", title: "No APK is published" },
  UNHEALTHY: { tone: "red", title: "Download does not match the release record" },
};

const RELEASE_TONE: Record<string, "green" | "neutral" | "clay"> = { PUBLISHED: "green", SUPERSEDED: "neutral", ROLLED_BACK: "clay" };

export function shortHash(value: string | null | undefined, length = 12): string {
  return value ? `${value.slice(0, length)}…` : "—";
}

function CheckRow({ check }: { check: ReleaseCheck }) {
  const Icon = check.ok === null ? CircleMinus : check.ok ? CircleCheck : CircleX;
  const color = check.ok === null ? "text-ink-faint" : check.ok ? "text-tamarind" : "text-danger";
  return (
    <li className="flex items-start gap-2 text-sm">
      <Icon className={`mt-0.5 h-4 w-4 shrink-0 ${color}`} aria-hidden />
      <span>
        {check.label}
        <span className="sr-only">: {check.ok === null ? "not configured" : check.ok ? "passed" : "failed"}</span>
        {check.ok === null ? <span className="ml-1 text-xs text-ink-faint">(not configured on this server)</span> : null}
      </span>
    </li>
  );
}

function ServedApkCard({ data }: { data: MobileOverview }) {
  const apk = data.servedApk;
  const release = data.servedRelease;
  if (!apk) {
    return (
      <Card>
        <CardHeader icon={Smartphone} title="Served APK" description="What /download hands out right now" />
        <p className="px-5 pb-5 text-sm text-ink-muted">
          {data.servedApkError ?? (data.download.distributionConfigured ? "The distribution folder has no GEO_Tree.apk." : "This server has no distribution folder configured (DISTRIBUTION_DIR).")}
        </p>
      </Card>
    );
  }
  const model = apk.bundledModel;
  return (
    <Card>
      <CardHeader
        icon={Smartphone}
        title={`GEO Tree ${apk.versionName ?? "?"} (versionCode ${apk.versionCode ?? "?"})`}
        description="Read from the exact file /download serves"
        action={release ? <StatusBadge status={release.status} label={release.baseline ? "Published (baseline)" : humanize(release.status)} /> : <Badge tone="amber">Not registered</Badge>}
      />
      <dl className="grid gap-x-6 gap-y-4 px-5 pb-5 sm:grid-cols-2 xl:grid-cols-3">
        <DataItem label="Package">{apk.packageName ?? "—"}</DataItem>
        <DataItem label="File">{apk.fileName} · {formatBytes(apk.bytes)}</DataItem>
        <DataItem label="Android">min SDK {apk.minSdk ?? "—"} · target SDK {apk.targetSdk ?? "—"}</DataItem>
        <DataItem label="APK SHA-256" mono>{apk.sha256}</DataItem>
        <DataItem label={`Signing certificate SHA-256 (${apk.signing.schemes.join(", ") || "none"})`} mono>
          {apk.signing.certificateSha256.join(", ") || apk.signing.note || "—"}
        </DataItem>
        <DataItem label="Build">{apk.debuggable ? "Debuggable (not a release build)" : "Release (not debuggable)"}</DataItem>
        <DataItem label="Bundled leaf model">
          {model ? `${model.backboneDisplayName ?? model.backbone} · head v${model.modelVersion} · ${model.classes.join(", ")}` : "No on-device model in this APK"}
        </DataItem>
        <DataItem label="Model head SHA-256" mono>{model?.headSha256 ?? "—"}</DataItem>
        <DataItem label="ONNX backbone SHA-256" mono>{model?.onnxSha256 ?? "—"}</DataItem>
        <DataItem label="Release recorded">{release ? formatDateTime(release.registeredAt) : "—"}</DataItem>
        <DataItem label="Originally published">
          {release ? (release.originalPublishedAt ? formatDateTime(release.originalPublishedAt) : "Unknown (baseline: already served before tracking began)") : "—"}
        </DataItem>
        <DataItem label="Dataset">{release?.dataset ? `${release.dataset.fileName} · ${shortHash(release.dataset.sha256)}` : "Not recorded"}</DataItem>
        {release?.notes ? <div className="sm:col-span-2 xl:col-span-3"><DataItem label="Release notes"><span className="whitespace-pre-line">{release.notes}</span></DataItem></div> : null}
      </dl>
      <div className="border-t border-line px-5 py-4">
        <p className="text-2xs font-medium uppercase tracking-wider text-ink-faint">Release checks on these bytes</p>
        <ul className="mt-2 grid gap-1.5 sm:grid-cols-2">{data.checks.map((c) => <CheckRow key={c.id} check={c} />)}</ul>
        <p className="mt-3 text-xs text-ink-faint">
          The certificate is read from the APK signing block; the server does not re-verify the signature. Run <code>apksigner verify</code> when building a release.
        </p>
      </div>
    </Card>
  );
}

function DownloadCard({ data }: { data: MobileOverview }) {
  const toast = useToast();
  const { download, qr } = data;
  async function copy() {
    if (!download.pageUrl) return;
    try {
      await navigator.clipboard.writeText(download.pageUrl);
      toast.success("Download link copied");
    } catch {
      toast.error("Couldn't copy", "Select the link and copy it manually.");
    }
  }
  return (
    <Card>
      <CardHeader icon={QrCode} title="Download link & QR code" description="Stable URL: new releases replace the file behind it" />
      {!download.configured ? (
        <p className="px-5 pb-5 text-sm text-ink-muted">The public download URL is not configured on this server (DOWNLOAD_PUBLIC_URL), so no QR code is shown.</p>
      ) : (
        <div className="flex flex-col gap-5 px-5 pb-5 sm:flex-row">
          {qr ? <img src={qr.pngDataUri} alt={`QR code for ${qr.url}`} className="h-44 w-44 shrink-0 rounded-lg border border-line bg-white p-1" /> : null}
          <div className="min-w-0 space-y-3">
            <DataItem label="Encoded URL" mono>{download.pageUrl}</DataItem>
            {!download.https ? <Notice tone="red" title="Not HTTPS">Phones should only download releases over HTTPS.</Notice> : null}
            <div className="flex flex-wrap gap-2">
              <Button size="sm" icon={Copy} onClick={copy}>Copy link</Button>
              <a className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-line-strong bg-surface px-3 text-[13px] font-medium shadow-card hover:bg-sand/70" href={download.pageUrl ?? undefined} target="_blank" rel="noreferrer">
                <ExternalLink className="h-3.5 w-3.5" aria-hidden /> Open download page
              </a>
              <a className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-line-strong bg-surface px-3 text-[13px] font-medium shadow-card hover:bg-sand/70" href={download.apkUrl ?? undefined}>
                <Download className="h-3.5 w-3.5" aria-hidden /> Test APK download
              </a>
            </div>
            <p className="text-xs text-ink-faint">
              This code encodes exactly the URL above. Printed QR codes keep working as long as they decode to this same URL; after a release, compare the downloaded file's SHA-256 with the record.
            </p>
          </div>
        </div>
      )}
    </Card>
  );
}

function RegisterDialog({ open, onClose, data }: { open: boolean; onClose: () => void; data: MobileOverview }) {
  const register = useRegisterRelease();
  const datasets = useDatasetProfile();
  const toast = useToast();
  const [notes, setNotes] = useState("");
  const [archiveId, setArchiveId] = useState("");
  const firstRecord = !data.currentRelease && !data.servedRelease;
  const [baseline, setBaseline] = useState(firstRecord);
  const apk = data.servedApk;
  const rollback = Boolean(data.servedRelease && data.servedRelease.status !== "PUBLISHED");

  async function submit() {
    try {
      const release = await register.mutateAsync({ notes, datasetArchiveId: archiveId || null, baseline: firstRecord && baseline });
      toast.success(rollback ? "Earlier release re-published" : "Release registered", `${release.versionName} (${release.versionCode})`);
      onClose();
    } catch (error) {
      toast.error("Couldn't register the release", errorMessage(error));
    }
  }

  return (
    <Dialog
      open={open}
      onClose={register.isPending ? () => undefined : onClose}
      title={rollback ? "Re-publish an earlier release" : "Register the served APK"}
      description="Every recorded value is read from the APK that /download serves; nothing here edits the file."
      footer={
        <>
          <Button onClick={onClose} disabled={register.isPending}>Cancel</Button>
          <Button variant="primary" icon={Rocket} loading={register.isPending} onClick={submit}>{rollback ? "Re-publish" : "Register release"}</Button>
        </>
      }
    >
      <div className="space-y-4">
        {apk ? (
          <dl className="grid gap-3 rounded-lg bg-cream/70 p-3 sm:grid-cols-2">
            <DataItem label="Version">{apk.versionName} ({apk.versionCode})</DataItem>
            <DataItem label="SHA-256" mono>{shortHash(apk.sha256, 16)}</DataItem>
          </dl>
        ) : null}
        {rollback ? (
          <Notice tone="amber" title="Rollback">The served file is an earlier recorded release. The current release will be marked rolled back.</Notice>
        ) : (
          <>
            <Field label="Release notes" hint="What changed, compatibility notes. Shown in the release history.">
              {(p) => <Textarea {...p} rows={4} maxLength={2000} value={notes} onChange={(e) => setNotes(e.target.value)} />}
            </Field>
            <Field label="Training dataset archive" hint="The archive the bundled model was trained from, if registered.">
              {(p) => (
              <Select {...p} value={archiveId} onChange={(e) => setArchiveId(e.target.value)}>
                <option value="">Not recorded</option>
                {(datasets.data?.archives ?? []).filter((a) => a.status !== "DISCARDED").map((a) => (
                  <option key={a.id} value={a.id}>{a.fileName} · {shortHash(a.sha256, 8)} · {humanize(a.status)}</option>
                ))}
              </Select>
              )}
            </Field>
            {firstRecord ? (
              <label className="flex items-start gap-2 text-sm">
                <input type="checkbox" className="mt-1" checked={baseline} onChange={(e) => setBaseline(e.target.checked)} />
                <span>
                  Baseline: this APK was already being served before tracking started.
                  <span className="block text-xs text-ink-faint">Its original publication date is then stored as unknown instead of today.</span>
                </span>
              </label>
            ) : null}
          </>
        )}
      </div>
    </Dialog>
  );
}

function HistoryCard() {
  const [page, setPage] = useState(1);
  const releases = useReleases({ page, pageSize: 10 });
  return (
    <Card>
      <CardHeader icon={History} title="Release history" description="Newest first. Tracking starts with the first registered release; earlier releases are not back-filled." />
      {releases.isLoading ? <div className="p-4"><Skeleton className="h-24" /></div> : releases.error ? <ErrorState className="m-4" error={releases.error} /> : !releases.data?.items.length ? (
        <p className="px-5 py-6 text-sm text-ink-faint">No releases recorded yet.</p>
      ) : (
        <>
          <Table label="Release history">
            <thead>
              <tr>
                <Th>Version</Th>
                <Th>Status</Th>
                <Th>Recorded</Th>
                <Th>Originally published</Th>
                <Th>APK</Th>
                <Th>Model</Th>
                <Th>By</Th>
                <Th>Notes</Th>
              </tr>
            </thead>
            <tbody>
              {releases.data.items.map((r: AppRelease) => (
                <tr key={r.id}>
                  <Td className="whitespace-nowrap font-medium">{r.versionName} <span className="num text-ink-faint">({r.versionCode})</span></Td>
                  <Td>
                    <Badge tone={RELEASE_TONE[r.status] ?? "neutral"} dot>{humanize(r.status)}</Badge>
                    {r.baseline ? <Badge className="ml-1" tone="sky">Baseline</Badge> : null}
                  </Td>
                  <Td className="whitespace-nowrap text-[13px]">{formatDateTime(r.registeredAt)}</Td>
                  <Td className="whitespace-nowrap text-[13px] text-ink-muted">{r.originalPublishedAt ? formatDateTime(r.originalPublishedAt) : "Unknown"}</Td>
                  <Td className="num font-mono text-[12px]" >{shortHash(r.apkSha256)} · {formatBytes(r.apkBytes)}</Td>
                  <Td className="text-[13px]">{r.model?.modelVersion ? `v${r.model.modelVersion}` : "—"}</Td>
                  <Td className="text-[13px] text-ink-muted">{r.registeredBy?.email ?? "—"}</Td>
                  <Td className="max-w-xs truncate text-[13px] text-ink-muted" >{r.notes || "—"}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
          <Pagination page={page} pageSize={10} total={releases.data.total} onPage={setPage} noun="releases" />
        </>
      )}
    </Card>
  );
}

export function ReleaseTab() {
  const overview = useMobileOverview();
  const { user } = useAuth();
  const [registering, setRegistering] = useState(false);
  if (overview.isLoading) return <Skeleton className="h-96" />;
  if (overview.error || !overview.data) return <ErrorState error={overview.error} onRetry={() => overview.refetch()} />;
  const data = overview.data;
  const health = HEALTH[data.health];
  const canRegister = user?.role === "SUPER_ADMIN" && data.servedApk && data.health !== "HEALTHY" && !data.checks.some((c) => c.ok === false);

  return (
    <div className="space-y-6">
      <Notice tone={health.tone} icon={data.health === "HEALTHY" ? ShieldCheck : undefined} title={health.title}>
        <p>{data.healthReason}</p>
        {data.servedApk && data.health !== "HEALTHY" && user?.role !== "SUPER_ADMIN" ? <p className="mt-1">A Super Admin can register the served APK.</p> : null}
        {canRegister ? <Button className="mt-3" size="sm" variant="primary" icon={Rocket} onClick={() => setRegistering(true)}>{data.servedRelease ? "Re-publish this release" : "Register served APK"}</Button> : null}
      </Notice>
      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(0,26rem)]">
        <ServedApkCard data={data} />
        <DownloadCard data={data} />
      </div>
      <HistoryCard />
      {registering ? <RegisterDialog open onClose={() => setRegistering(false)} data={data} /> : null}
    </div>
  );
}
