import { Camera, ChevronRight, Info, MapPin, ScanSearch, X } from "lucide-react";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";

import { useEvidenceMap } from "@/api/hooks";
import type { Assessment, EvidenceItem } from "@/api/types";
import { AuthImage } from "@/components/AuthImage";
import { LeafletMap, type MapMarker, type MarkerVariant } from "@/components/map/LeafletMap";
import { Badge, ErrorState, Notice, PageHeader, Spinner, StatusBadge } from "@/components/ui/Display";
import { IconButton } from "@/components/ui/Button";
import { Input, SearchInput, Select } from "@/components/ui/Form";
import { useSearchBox, useUrlState } from "@/lib/hooks";
import { formatCoordinate, formatCount, formatDateTime, formatMeters, formatPercent } from "@/lib/format";

import { AssessmentDrawer, resultLabel } from "../assessments/AssessmentsPage";
import { BackLink, MapLayout, MapLegend, MapOverlay, NoGpsNote } from "./MapParts";

/** All evidence recorded for one tree, shown as one marker at the tree's GPS position. */
export interface EvidenceGroup {
  treeId: string;
  treeCode: string | null;
  latitude: number;
  longitude: number;
  accuracyMeters: number | null;
  treeArchived: boolean;
  items: EvidenceItem[]; // newest first (API order)
}

export function groupEvidence(items: EvidenceItem[]): EvidenceGroup[] {
  const groups = new Map<string, EvidenceGroup>();
  for (const item of items) {
    let group = groups.get(item.treeId);
    if (!group) {
      group = { treeId: item.treeId, treeCode: item.treeCode, latitude: item.latitude, longitude: item.longitude,
        accuracyMeters: item.accuracyMeters, treeArchived: item.treeArchived, items: [] };
      groups.set(item.treeId, group);
    }
    group.items.push(item);
  }
  return Array.from(groups.values());
}

function variantFor(group: EvidenceGroup): MarkerVariant {
  // The ring reflects the newest leaf scan at this tree (completed / uncertain / failed).
  const latestScan = group.items.find((i) => i.type === "LEAF_ASSESSMENT")?.assessment;
  if (latestScan?.status === "FAILED") return "evidence-failed";
  if (latestScan?.status === "UNCERTAIN") return "evidence-uncertain";
  return "evidence";
}

export function evidenceMarkers(groups: EvidenceGroup[]): MapMarker[] {
  return groups.map((group) => ({
    id: group.treeId,
    lat: group.latitude,
    lng: group.longitude,
    variant: variantFor(group),
    title: `${group.treeCode ?? "Tree"}: ${group.items.length} evidence item${group.items.length === 1 ? "" : "s"}`,
    label: group.items.length > 99 ? "99+" : String(group.items.length),
  }));
}

function EvidenceRow({ item, onOpenAssessment }: { item: EvidenceItem; onOpenAssessment: (a: Assessment) => void }) {
  const isScan = item.type === "LEAF_ASSESSMENT" && item.assessment;
  const body = (
    <>
      <AuthImage src={item.imageUrl} alt="" className="h-12 w-12 shrink-0 rounded-md" />
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-1.5 text-[13px] font-medium text-ink">
          {isScan ? <ScanSearch className="h-3.5 w-3.5 text-moss" aria-hidden /> : <Camera className="h-3.5 w-3.5 text-forest" aria-hidden />}
          {isScan ? "Leaf assessment" : "Registration photo"}
        </span>
        <span className="block truncate text-[13px] text-ink-muted">
          {isScan ? `${resultLabel(item.assessment!)}${item.assessment!.confidence !== null ? ` · ${formatPercent(item.assessment!.confidence, 0)}` : ""}` : "Taken when the tree was geotagged"}
        </span>
        <span className="block text-xs text-ink-faint">{formatDateTime(item.at)}</span>
      </span>
      {isScan ? <StatusBadge status={item.assessment!.status} /> : null}
    </>
  );
  return isScan ? (
    <button type="button" onClick={() => onOpenAssessment(item.assessment!)} className="flex w-full items-center gap-3 px-5 py-2.5 text-left hover:bg-cream focus:bg-cream focus:outline-none">
      {body}
    </button>
  ) : (
    <div className="flex items-center gap-3 px-5 py-2.5">{body}</div>
  );
}

function GroupDetail({ group, onClose, onOpenAssessment }: { group: EvidenceGroup; onClose: () => void; onOpenAssessment: (a: Assessment) => void }) {
  return (
    <div className="flex min-h-0 flex-1 flex-col" aria-live="polite">
      <div className="flex items-start justify-between gap-2 border-b border-line px-5 py-4">
        <div className="min-w-0">
          <p className="text-2xs font-semibold uppercase tracking-wider text-clay">Field evidence at</p>
          <h2 className="mt-0.5 font-mono text-lg font-semibold text-forest">{group.treeCode ?? "Unknown tree"}</h2>
          <p className="num mt-0.5 font-mono text-xs text-ink-muted">
            {formatCoordinate(group.latitude)}, {formatCoordinate(group.longitude)}{group.accuracyMeters !== null ? ` · ±${formatMeters(group.accuracyMeters)}` : ""}
          </p>
          {group.treeArchived ? <Badge className="mt-1">Archived tree</Badge> : null}
        </div>
        <IconButton icon={X} label="Close evidence details" size="sm" onClick={onClose} />
      </div>
      <ol className="min-h-0 flex-1 divide-y divide-line overflow-y-auto" aria-label={`Evidence for ${group.treeCode ?? "tree"}`}>
        {group.items.map((item) => (
          <li key={item.id}><EvidenceRow item={item} onOpenAssessment={onOpenAssessment} /></li>
        ))}
      </ol>
      <div className="border-t border-line px-5 py-3">
        <Link to={`/trees/${group.treeId}`} className="flex h-10 w-full items-center justify-center gap-2 rounded-lg bg-forest text-sm font-medium text-cream shadow-card hover:bg-forest-deep">
          Open tree record <ChevronRight className="h-4 w-4" aria-hidden />
        </Link>
      </div>
    </div>
  );
}

function GroupList({ groups, onSelect }: { groups: EvidenceGroup[]; onSelect: (id: string) => void }) {
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="border-b border-line px-5 py-3">
        <h2 className="text-[15px] font-semibold">Trees with field evidence</h2>
        <p className="text-[13px] text-ink-muted">Select a marker or a tree below.</p>
      </div>
      <ul className="min-h-0 flex-1 divide-y divide-line overflow-y-auto" aria-label="Trees with field evidence">
        {groups.map((group) => {
          const scans = group.items.filter((i) => i.type === "LEAF_ASSESSMENT").length;
          const photos = group.items.length - scans;
          return (
            <li key={group.treeId}>
              <button type="button" onClick={() => onSelect(group.treeId)} className="flex w-full items-center gap-3 px-5 py-2.5 text-left hover:bg-cream focus:bg-cream focus:outline-none">
                <MapPin className="h-4 w-4 text-moss" aria-hidden />
                <span className="min-w-0 flex-1">
                  <span className="block font-mono text-[13px] font-semibold">{group.treeCode ?? "Unknown tree"}</span>
                  <span className="block text-xs text-ink-faint">
                    {scans} leaf scan{scans === 1 ? "" : "s"} · {photos} photo{photos === 1 ? "" : "s"} · latest {formatDateTime(group.items[0]?.at)}
                  </span>
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export function EvidenceMapPage() {
  const [state, update] = useUrlState({ type: "all", status: "", from: "", to: "", q: "", archived: "", focus: "", back: "" });
  const [search, setSearch] = useSearchBox(state.q, (q) => update({ q, focus: "" }, false));
  const [drawer, setDrawer] = useState<Assessment | null>(null);
  const query = {
    type: state.type,
    status: state.type === "registration" ? undefined : state.status,
    from: state.from,
    to: state.to,
    q: state.q,
    includeArchived: state.archived === "1" ? true : undefined,
  };
  const evidence = useEvidenceMap(query);
  const groups = useMemo(() => groupEvidence(evidence.data?.items ?? []), [evidence.data]);
  const markers = useMemo(() => evidenceMarkers(groups), [groups]);
  const selected = groups.find((g) => g.treeId === state.focus) ?? null;
  const select = (id: string | null) => update({ focus: id ?? "" }, false);
  const back = state.back === "images" ? { to: "/images?tab=evidence", label: "Images" } : { to: "/assessments", label: "Leaf assessments" };
  const filterKey = JSON.stringify(query) + (evidence.data?.placed ?? -1);
  const filtered = Boolean(state.status || state.from || state.to || state.q || state.type !== "all");

  return (
    <div className="space-y-4">
      <BackLink to={back.to} label={back.label} />
      <PageHeader
        eyebrow="Field data"
        title="Field evidence map"
        description="Registration photos and leaf-assessment scans placed at the GPS position recorded for their tree. Select a marker to see that tree's evidence."
      />
      <div className="grid gap-3 rounded-xl border border-line bg-surface p-3 shadow-card sm:grid-cols-2 xl:grid-cols-[minmax(0,1.3fr)_repeat(4,minmax(0,1fr))_auto]">
        <SearchInput label="Search by Tree Code" placeholder="Tree Code…" value={search} onChange={setSearch} />
        <Select aria-label="Evidence type" value={state.type} onChange={(e) => update({ type: e.target.value, focus: "" }, false)}>
          <option value="all">All evidence</option>
          <option value="assessment">Leaf assessments</option>
          <option value="registration">Registration photos</option>
        </Select>
        <Select aria-label="Assessment status" value={state.status} disabled={state.type === "registration"} onChange={(e) => update({ status: e.target.value, focus: "" }, false)}>
          <option value="">Any result</option>
          <option value="COMPLETED">Completed</option>
          <option value="UNCERTAIN">Uncertain</option>
          <option value="FAILED">Failed</option>
        </Select>
        <Input type="date" aria-label="From date" value={state.from} max={state.to || undefined} onChange={(e) => update({ from: e.target.value, focus: "" }, false)} />
        <Input type="date" aria-label="To date" value={state.to} min={state.from || undefined} onChange={(e) => update({ to: e.target.value, focus: "" }, false)} />
        <label className="flex h-10 items-center gap-2 whitespace-nowrap text-[13px] text-ink-muted">
          <input type="checkbox" className="h-4 w-4 accent-[rgb(var(--forest))]" checked={state.archived === "1"} onChange={(e) => update({ archived: e.target.checked ? "1" : "", focus: "" }, false)} />
          Include archived trees
        </label>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <MapLegend items={[
          { variant: "evidence", label: "Evidence (number = items)" },
          { variant: "evidence-uncertain", label: "Latest scan uncertain" },
          { variant: "evidence-failed", label: "Latest scan failed" },
          { variant: "selected", label: "Selected" },
        ]} />
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1" aria-live="polite">
          <span className="num text-[13px] font-medium" data-testid="map-count">
            {evidence.data ? `${formatCount(evidence.data.placed)} item${evidence.data.placed === 1 ? "" : "s"} at ${formatCount(evidence.data.treeCount)} tree${evidence.data.treeCount === 1 ? "" : "s"}` : "Loading…"}
          </span>
          {evidence.data ? <NoGpsNote count={evidence.data.excludedCount} noun={evidence.data.excludedCount === 1 ? "item" : "items"} /> : null}
        </div>
      </div>

      {evidence.error && !evidence.data ? (
        <ErrorState error={evidence.error} onRetry={() => evidence.refetch()} />
      ) : (
        <MapLayout
          map={
            <>
              <LeafletMap markers={markers} selectedId={state.focus || null} onSelect={select} fitKey={filterKey} label="Field evidence map" />
              {evidence.isLoading ? <MapOverlay><Spinner label="Loading field evidence" /></MapOverlay> : null}
              {evidence.data && evidence.data.placed === 0 ? (
                <MapOverlay>
                  <p className="font-medium">{filtered ? "No geotagged evidence matches these filters" : "No geotagged field evidence yet"}</p>
                  <p className="mt-0.5 text-[13px] text-ink-muted">
                    {evidence.data.excludedCount > 0
                      ? `${evidence.data.excludedCount} item(s) belong to trees without valid GPS.`
                      : "Registration photos and leaf scans appear here after field devices sync them."}
                  </p>
                </MapOverlay>
              ) : null}
            </>
          }
          panel={
            selected ? (
              <GroupDetail group={selected} onClose={() => select(null)} onOpenAssessment={setDrawer} />
            ) : evidence.data ? (
              <>
                <GroupList groups={groups} onSelect={select} />
                <div className="border-t border-line p-3">
                  <Notice tone="sky" icon={Info}>Scans have no GPS of their own: each one is shown at its tree's recorded position.</Notice>
                </div>
              </>
            ) : (
              <div className="p-5"><Spinner /></div>
            )
          }
        />
      )}
      <AssessmentDrawer item={drawer} onClose={() => setDrawer(null)} />
    </div>
  );
}
