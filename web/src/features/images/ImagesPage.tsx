import { Camera, Layers, Lock, Map as MapIcon, ScanSearch } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { useAssessments, useClassifiers, useDashboard, useTrees } from "@/api/hooks";
import type { Assessment } from "@/api/types";
import { AuthImage } from "@/components/AuthImage";
import { Button } from "@/components/ui/Button";
import { Card, EmptyState, ErrorState, Notice, PageHeader, Skeleton, StatusBadge } from "@/components/ui/Display";
import { Pagination, Tabs } from "@/components/ui/Navigation";
import { useUrlState } from "@/lib/hooks";
import { formatCount, formatDate, formatDateTime } from "@/lib/format";

import { AssessmentDrawer, resultLabel } from "../assessments/AssessmentsPage";

type Tab = "registration" | "training" | "evidence";

function GridSkeleton() {
  return (
    <div className="grid grid-cols-2 gap-3 p-4 sm:grid-cols-3 lg:grid-cols-5">
      {Array.from({ length: 10 }, (_, i) => <Skeleton key={i} className="aspect-square" />)}
    </div>
  );
}

function RegistrationPhotos({ page, onPage }: { page: number; onPage: (p: number) => void }) {
  const trees = useTrees({ hasImage: true, status: "all", sort: "-updatedAt", page, pageSize: 20 });
  if (trees.isLoading) return <GridSkeleton />;
  if (trees.error) return <ErrorState className="m-4" error={trees.error} onRetry={() => trees.refetch()} />;
  if (!trees.data || trees.data.items.length === 0) {
    return <EmptyState className="m-4" icon={Camera} title="No registration photos yet" description="Photos taken while tagging trees appear here after the field app syncs them." />;
  }
  return (
    <>
      <ul className="grid grid-cols-2 gap-3 p-4 sm:grid-cols-3 lg:grid-cols-5">
        {trees.data.items.map((tree) => (
          <li key={tree.id}>
            <Link to={`/trees/${tree.id}`} className="group block overflow-hidden rounded-xl border border-line bg-surface shadow-card transition-shadow hover:shadow-raised">
              <AuthImage src={tree.imageUrl} alt={`Registration photo of tree ${tree.treeCode}`} className="aspect-square w-full transition-transform duration-300 group-hover:scale-[1.02]" />
              <div className="px-3 py-2">
                <p className="truncate font-mono text-[13px] font-semibold text-forest">{tree.treeCode}</p>
                <p className="text-xs text-ink-faint">{formatDate(tree.updatedAt)}{tree.archived ? " · archived" : ""}</p>
              </div>
            </Link>
          </li>
        ))}
      </ul>
      <div className="border-t border-line px-4 py-3">
        <Pagination page={trees.data.page} pageSize={trees.data.pageSize} total={trees.data.total} onPage={onPage} noun="photos" />
      </div>
    </>
  );
}

function TrainingImages() {
  const classes = useClassifiers();
  if (classes.isLoading) return <GridSkeleton />;
  if (classes.error) return <ErrorState className="m-4" error={classes.error} onRetry={() => classes.refetch()} />;
  const items = classes.data?.items ?? [];
  if (items.length === 0) return <EmptyState className="m-4" icon={Layers} title="No classifier classes yet" action={<Link to="/classifiers" className="text-sm font-medium text-forest hover:underline">Create a class</Link>} />;
  return (
    <div className="space-y-4 p-4">
      <Notice tone="green" icon={Layers}>Training images are curated by administrators per class. Field scans never become training data automatically.</Notice>
      <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {items.map((c) => (
          <li key={c.id}>
            <Link to={`/classifiers/${c.id}`} className="flex items-center justify-between gap-3 rounded-xl border border-line bg-surface px-4 py-3 shadow-card transition-shadow hover:shadow-raised">
              <div className="min-w-0">
                <p className="truncate font-medium">{c.name}</p>
                <p className="num text-[13px] text-ink-muted">{formatCount(c.imageCount)} images</p>
              </div>
              <StatusBadge status={c.status} />
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

function EvidencePhotos({ page, onPage }: { page: number; onPage: (p: number) => void }) {
  const scans = useAssessments({ hasImage: true, page, pageSize: 20 });
  const [selected, setSelected] = useState<Assessment | null>(null);
  if (scans.isLoading) return <GridSkeleton />;
  if (scans.error) return <ErrorState className="m-4" error={scans.error} onRetry={() => scans.refetch()} />;
  if (!scans.data || scans.data.items.length === 0) {
    return <EmptyState className="m-4" icon={ScanSearch} title="No assessment photos on the server yet" description="Leaf photos are uploaded with their assessment when a field device syncs." />;
  }
  return (
    <>
      <div className="p-4 pb-0">
        <Notice tone="sky" icon={Lock}>Assessment photos are read-only evidence for historical scans. They cannot be edited or deleted here.</Notice>
      </div>
      <ul className="grid grid-cols-2 gap-3 p-4 sm:grid-cols-3 lg:grid-cols-5">
        {scans.data.items.map((item) => (
          <li key={item.assessmentId}>
            <button type="button" onClick={() => setSelected(item)} className="group block w-full overflow-hidden rounded-xl border border-line bg-surface text-left shadow-card transition-shadow hover:shadow-raised">
              <AuthImage src={item.imageUrl} alt={`Leaf scan of ${item.treeCode ?? "tree"}, ${formatDateTime(item.createdAt)}`} className="aspect-square w-full" />
              <div className="px-3 py-2">
                <p className="truncate text-[13px] font-medium">{resultLabel(item)}</p>
                <p className="truncate text-xs text-ink-faint"><span className="font-mono">{item.treeCode}</span> · {formatDate(item.createdAt)}</p>
              </div>
            </button>
          </li>
        ))}
      </ul>
      <div className="border-t border-line px-4 py-3">
        <Pagination page={scans.data.page} pageSize={scans.data.pageSize} total={scans.data.total} onPage={onPage} noun="photos" />
      </div>
      <AssessmentDrawer item={selected} onClose={() => setSelected(null)} />
    </>
  );
}

export function ImagesPage() {
  const navigate = useNavigate();
  const [state, update] = useUrlState({ tab: "registration", page: "1" });
  const dashboard = useDashboard();
  const tab = state.tab as Tab;
  const page = Number(state.page);
  const onPage = (p: number) => update({ page: String(p) }, false);
  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Field data"
        title="Images"
        description="Three separate collections with different rules: tree registration photos, classifier training images, and leaf-assessment evidence."
        actions={
          <Button variant="primary" icon={MapIcon} onClick={() => navigate(`/field-evidence/map?back=images${tab === "registration" ? "&type=registration" : tab === "evidence" ? "&type=assessment" : ""}`)}>
            View Map
          </Button>
        }
      />
      <Card>
        <div className="px-4 pt-2">
          <Tabs<Tab>
            label="Image collections"
            value={tab}
            onChange={(next) => update({ tab: next })}
            items={[
              { id: "registration", label: "Tree registration", icon: Camera, count: dashboard.data?.images.treeRegistration },
              { id: "training", label: "Classifier training", icon: Layers, count: dashboard.data?.images.training.total },
              { id: "evidence", label: "Assessment evidence", icon: ScanSearch, count: dashboard.data?.images.assessmentEvidence },
            ]}
          />
        </div>
        <div role="tabpanel">
          {tab === "registration" ? <RegistrationPhotos page={page} onPage={onPage} /> : tab === "training" ? <TrainingImages /> : <EvidencePhotos page={page} onPage={onPage} />}
        </div>
      </Card>
    </div>
  );
}
