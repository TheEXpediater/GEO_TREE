import {
  Activity,
  ArrowRight,
  BrainCircuit,
  Camera,
  CheckCircle2,
  Crosshair,
  Images,
  Layers,
  ScanSearch,
  TreePine,
  Users,
  type LucideIcon,
} from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "react-router-dom";

import { useDashboard } from "@/api/hooks";
import type { Dashboard } from "@/api/types";
import { AccuracyBar, BarList, RingMeter, WeeklyColumns } from "@/components/charts/Charts";
import { Card, CardHeader, ErrorState, Notice, PageHeader, Skeleton, StatusBadge } from "@/components/ui/Display";
import { formatCount, formatDateTime, formatPercent, formatRelative, humanize } from "@/lib/format";

function Metric({ icon: Icon, label, value, detail, to }: { icon: LucideIcon; label: string; value: string; detail?: ReactNode; to?: string }) {
  const body = (
    <Card className="group h-full p-5 transition-shadow hover:shadow-raised">
      <div className="flex items-start justify-between gap-3">
        <p className="text-[13px] font-medium text-ink-muted">{label}</p>
        <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-moss-soft text-forest">
          <Icon className="h-4 w-4" aria-hidden />
        </span>
      </div>
      <p className="num mt-2 text-[28px] font-semibold leading-none tracking-tight text-ink">{value}</p>
      {detail ? <p className="mt-2 text-[13px] text-ink-muted">{detail}</p> : null}
    </Card>
  );
  return to ? (
    <Link to={to} className="block rounded-xl focus-visible:outline-offset-4">
      {body}
    </Link>
  ) : (
    body
  );
}

function DashboardSkeleton() {
  return (
    <div className="space-y-6" aria-busy="true" aria-label="Loading dashboard">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {Array.from({ length: 4 }, (_, i) => (
          <Card key={i} className="p-5">
            <Skeleton className="h-4 w-28" />
            <Skeleton className="mt-4 h-7 w-16" />
            <Skeleton className="mt-3 h-3 w-36" />
          </Card>
        ))}
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="h-72 p-5 lg:col-span-2"><Skeleton className="h-full w-full" /></Card>
        <Card className="h-72 p-5"><Skeleton className="h-full w-full" /></Card>
      </div>
    </div>
  );
}

function ModelCard({ model }: { model: Dashboard["model"] }) {
  return (
    <Card>
      <CardHeader
        icon={BrainCircuit}
        title="Leaf classifier"
        description={model.backbone ? `${model.backbone} · frozen backbone + classifier head` : "DINOv2 ViT-S/14"}
        action={<StatusBadge status={model.status} />}
      />
      <div className="space-y-4 p-5">
        {!model.aiEnabled ? (
          <Notice tone="sky" title="AI is off on this server">Model training and leaf assessment are unavailable here; stored model history is shown.</Notice>
        ) : null}
        <dl className="grid grid-cols-3 gap-3">
          <div>
            <dt className="text-2xs uppercase tracking-wider text-ink-faint">Active version</dt>
            <dd className="num mt-0.5 text-lg font-semibold">{model.activeVersion ? `v${model.activeVersion}` : "—"}</dd>
          </div>
          <div>
            <dt className="text-2xs uppercase tracking-wider text-ink-faint">Val. accuracy</dt>
            <dd className="num mt-0.5 text-lg font-semibold">{formatPercent(model.validationAccuracy)}</dd>
          </div>
          <div>
            <dt className="text-2xs uppercase tracking-wider text-ink-faint">Macro F1</dt>
            <dd className="num mt-0.5 text-lg font-semibold">{formatPercent(model.validationMacroF1)}</dd>
          </div>
        </dl>
        <p className="text-[13px] text-ink-muted">
          {model.trainedAt ? <>Trained {formatRelative(model.trainedAt)} · {formatDateTime(model.trainedAt)}</> : "No model trained yet."}
          {model.stale ? <span className="ml-1 text-amber">The dataset changed since; a rebuild is pending or needed.</span> : null}
        </p>
        {model.currentJob ? (
          <Notice tone="sky" icon={BrainCircuit} title={`Training job ${humanize(model.currentJob.status)}`}>
            Started by {humanize(model.currentJob.trigger)} · created {formatRelative(model.currentJob.createdAt)}
          </Notice>
        ) : null}
        {model.recentFailures.length > 0 ? (
          <div>
            <p className="text-2xs font-medium uppercase tracking-wider text-ink-faint">Recent training failures</p>
            <ul className="mt-2 space-y-1.5">
              {model.recentFailures.slice(0, 3).map((job) => (
                <li key={job.jobId} className="text-[13px] text-ink-muted">
                  <span className="text-danger">●</span> {formatDateTime(job.finishedAt)} — {job.errorMessage ?? "Unknown error"}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
        <Link to="/models" className="inline-flex items-center gap-1 text-sm font-medium text-forest hover:underline">
          Models &amp; training <ArrowRight className="h-3.5 w-3.5" aria-hidden />
        </Link>
      </div>
    </Card>
  );
}

export function DashboardPage() {
  const { data, isLoading, error, refetch } = useDashboard();

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Overview"
        title="Field research dashboard"
        description={data ? `Live figures from the GEO Tree database · updated ${formatRelative(data.generatedAt)}` : "Live figures from the GEO Tree database."}
      />
      {isLoading ? <DashboardSkeleton /> : error || !data ? <ErrorState error={error} onRetry={() => refetch()} /> : <DashboardBody data={data} />}
    </div>
  );
}

function DashboardBody({ data }: { data: Dashboard }) {
  const { trees, images, assessments, users } = data;
  const classified = (assessments.byStatus.COMPLETED ?? 0) + (assessments.byStatus.UNCERTAIN ?? 0);
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Metric icon={TreePine} label="Registered trees" value={formatCount(trees.total)} to="/trees"
          detail={<>{formatCount(trees.registeredLast7Days)} in the last 7 days{trees.archived ? ` · ${formatCount(trees.archived)} archived` : ""}</>} />
        <Metric icon={ScanSearch} label="Leaf assessment records" value={formatCount(assessments.total)} to="/assessments"
          detail={<>{formatCount(classified)} classified · {formatCount(assessments.byStatus.FAILED ?? 0)} failed attempts</>} />
        <Metric icon={Layers} label="Classifier training images" value={formatCount(images.training.total)} to="/classifiers"
          detail={`${images.training.byClass.length} classes`} />
        <Metric icon={Users} label="Active accounts" value={formatCount(users.active)} to="/users"
          detail={<>{formatCount(users.byRole.FIELD_TESTER)} field testers{users.suspended ? ` · ${formatCount(users.suspended)} suspended` : ""}</>} />
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader icon={TreePine} title="Tree registrations per week" description="Last 12 weeks, by the date the tree was registered in the field" />
          <div className="p-5">
            <WeeklyColumns data={trees.weekly} label="Tree registrations per week" unit="trees" />
          </div>
        </Card>
        <Card>
          <CardHeader icon={CheckCircle2} title="Data completeness" description="Share of active tree records that are complete" />
          <div className="space-y-4 p-5">
            <div className="flex items-center gap-4">
              <RingMeter percent={trees.completeness.percent} label="Complete tree records" />
              <div>
                <p className="num text-[28px] font-semibold leading-none">{trees.completeness.percent === null ? "—" : `${trees.completeness.percent}%`}</p>
                <p className="mt-1 text-[13px] text-ink-muted">{formatCount(trees.completeness.complete)} of {formatCount(trees.completeness.total)} records</p>
              </div>
            </div>
            <ul className="space-y-2 text-[13px]">
              {[
                ["Registration photo", trees.completeness.criteria.photo],
                ["GPS accuracy ≤ 10 m", trees.completeness.criteria.accuracy],
                ["Descriptive data", trees.completeness.criteria.descriptive],
              ].map(([label, count]) => (
                <li key={label as string} className="flex justify-between gap-2">
                  <span className="text-ink-muted">{label}</span>
                  <span className="num font-medium">{formatCount(count as number)} / {formatCount(trees.total)}</span>
                </li>
              ))}
            </ul>
            <p className="border-t border-line pt-3 text-xs leading-relaxed text-ink-faint">{trees.completeness.definition}</p>
          </div>
        </Card>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card>
          <CardHeader icon={Crosshair} title="GPS accuracy" description="Active tree records by recorded accuracy" />
          <div className="p-5">
            <AccuracyBar {...trees.accuracy} />
          </div>
        </Card>
        <Card>
          <CardHeader icon={Images} title="Images" description="Three separate collections" />
          <dl className="divide-y divide-line">
            {[
              { icon: Camera, label: "Tree registration photos", value: images.treeRegistration, to: "/images?tab=registration" },
              { icon: Layers, label: "Classifier training images", value: images.training.total, to: "/images?tab=training" },
              { icon: ScanSearch, label: "Assessment evidence photos", value: images.assessmentEvidence, to: "/images?tab=evidence" },
            ].map((row) => (
              <Link key={row.label} to={row.to} className="flex items-center justify-between gap-3 px-5 py-3 transition-colors hover:bg-cream">
                <dt className="flex items-center gap-2.5 text-sm text-ink-muted">
                  <row.icon className="h-4 w-4 text-moss" aria-hidden />
                  {row.label}
                </dt>
                <dd className="num text-sm font-semibold">{formatCount(row.value)}</dd>
              </Link>
            ))}
          </dl>
        </Card>
        <ModelCard model={data.model} />
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card>
          <CardHeader icon={Layers} title="Training images by class" description="Admin-curated dataset (never field scans)" />
          <div className="p-5">
            <BarList
              label="Training images by class"
              emptyText="No classifier classes yet."
              items={images.training.byClass.map((c) => ({ label: c.name, value: c.count, hint: c.embedded < c.count ? `${c.count - c.embedded} pending features` : undefined }))}
            />
          </div>
        </Card>
        <Card>
          <CardHeader icon={ScanSearch} title="Predicted leaf condition" description="Classified scans across all trees" />
          <div className="p-5">
            <BarList label="Predicted leaf condition" emptyText="No classified scans yet." items={assessments.byPredictedClass.map((c) => ({ label: c.className, value: c.count }))} />
          </div>
        </Card>
        <Card>
          <CardHeader icon={Activity} title="Recent activity" action={<Link to="/activity" className="text-[13px] font-medium text-forest hover:underline">View all</Link>} />
          <ul className="divide-y divide-line">
            {data.recentActivity.length === 0 ? <li className="px-5 py-6 text-center text-sm text-ink-faint">No activity recorded yet.</li> : null}
            {data.recentActivity.slice(0, 6).map((entry) => (
              <li key={entry.id} className="px-5 py-3">
                <p className="line-clamp-2 text-[13px] text-ink">{entry.summary}</p>
                <p className="mt-0.5 text-xs text-ink-faint">{entry.actorEmail} · {formatRelative(entry.at)}</p>
              </li>
            ))}
          </ul>
        </Card>
      </div>

      <Card>
        <CardHeader icon={ScanSearch} title="Leaf assessments per week" description="Every scan is a separate historical record, including failed attempts" />
        <div className="p-5">
          <WeeklyColumns data={assessments.weekly} label="Leaf assessments per week" unit="scans" />
        </div>
      </Card>
    </>
  );
}
