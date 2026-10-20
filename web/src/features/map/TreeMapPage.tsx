import { ChevronRight, MapPin, MapPinOff, ScanSearch, X } from "lucide-react";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";

import { useMapTrees, useTags } from "@/api/hooks";
import type { MapTree, MapTreesResponse } from "@/api/types";
import { AuthImage } from "@/components/AuthImage";
import { LeafletMap, type MapMarker } from "@/components/map/LeafletMap";
import { Badge, DataItem, ErrorState, PageHeader, Spinner } from "@/components/ui/Display";
import { IconButton } from "@/components/ui/Button";
import { SearchInput, Select } from "@/components/ui/Form";
import { Segmented } from "@/components/ui/Navigation";
import { useSearchBox, useUrlState } from "@/lib/hooks";
import { formatCoordinate, formatCount, formatDateTime, formatPercent } from "@/lib/format";

import { TagChip } from "../tags/TagChip";
import { AccuracyBadge } from "../trees/TreesPage";
import { BackLink, MapLayout, MapLegend, MapOverlay, NoGpsNote } from "./MapParts";

export function treeMarkers(trees: MapTree[]): MapMarker[] {
  return trees.map((tree) => ({
    id: tree.id,
    lat: tree.latitude,
    lng: tree.longitude,
    variant: tree.archived ? "archived" : "tree",
    title: tree.archived ? `${tree.treeCode} (archived)` : tree.treeCode,
  }));
}

function TreeDetail({ tree, onClose }: { tree: MapTree; onClose: () => void }) {
  const latest = tree.latestAssessment;
  return (
    <div className="flex min-h-0 flex-1 flex-col" aria-live="polite">
      <div className="flex items-start justify-between gap-2 border-b border-line px-5 py-4">
        <div className="min-w-0">
          <p className="text-2xs font-semibold uppercase tracking-wider text-clay">Selected tree</p>
          <h2 className="mt-0.5 font-mono text-lg font-semibold text-forest">{tree.treeCode}</h2>
          <div className="mt-1 flex flex-wrap gap-1">
            {tree.archived ? <Badge tone="neutral">Archived</Badge> : <Badge tone="green" dot>Active</Badge>}
            {tree.tags.map((tag) => <TagChip key={tag.id} tag={tag} />)}
          </div>
        </div>
        <IconButton icon={X} label="Close tree details" size="sm" onClick={onClose} />
      </div>
      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-5 py-4">
        {tree.hasPhoto ? <AuthImage src={tree.imageUrl} alt={`Registration photo of tree ${tree.treeCode}`} className="aspect-[4/3] w-full rounded-lg" /> : null}
        <dl className="grid grid-cols-2 gap-x-4 gap-y-3">
          <DataItem label="Latitude" mono>{formatCoordinate(tree.latitude)}</DataItem>
          <DataItem label="Longitude" mono>{formatCoordinate(tree.longitude)}</DataItem>
          <DataItem label="GPS accuracy">{tree.accuracyMeters === null ? "—" : <AccuracyBadge tree={{ accuracyLevel: tree.accuracyLevel, accuracyMeters: tree.accuracyMeters }} />}</DataItem>
          <DataItem label="Location captured">{formatDateTime(tree.locationCapturedAt)}</DataItem>
        </dl>
        <div className="rounded-lg bg-cream px-3 py-2.5 text-[13px]">
          <p className="flex items-center gap-1.5 font-medium text-ink"><ScanSearch className="h-4 w-4 text-moss" aria-hidden /> Leaf condition</p>
          <p className="mt-0.5 text-ink-muted">
            {latest
              ? <>{latest.className ?? "Failed attempt"}{latest.confidence !== null ? ` · ${formatPercent(latest.confidence, 0)}` : ""} · {formatDateTime(latest.createdAt)} · {tree.assessmentCount} scan{tree.assessmentCount === 1 ? "" : "s"}</>
              : "No leaf scans yet"}
          </p>
        </div>
      </div>
      <div className="border-t border-line px-5 py-3">
        <Link to={`/trees/${tree.id}`} className="flex h-10 w-full items-center justify-center gap-2 rounded-lg bg-forest text-sm font-medium text-cream shadow-card hover:bg-forest-deep">
          View Tree Details <ChevronRight className="h-4 w-4" aria-hidden />
        </Link>
      </div>
    </div>
  );
}

function TreeList({ data, onSelect }: { data: MapTreesResponse; onSelect: (id: string) => void }) {
  const [showExcluded, setShowExcluded] = useState(false);
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="border-b border-line px-5 py-3">
        <h2 className="text-[15px] font-semibold">Trees on the map</h2>
        <p className="text-[13px] text-ink-muted">Select a marker or a tree below.</p>
      </div>
      <ul className="min-h-0 flex-1 divide-y divide-line overflow-y-auto" aria-label="Trees on the map">
        {data.items.map((tree) => (
          <li key={tree.id}>
            <button type="button" onClick={() => onSelect(tree.id)} className="flex w-full items-center gap-3 px-5 py-2.5 text-left hover:bg-cream focus:bg-cream focus:outline-none">
              <MapPin className={tree.archived ? "h-4 w-4 text-ink-faint" : "h-4 w-4 text-forest"} aria-hidden />
              <span className="min-w-0 flex-1">
                <span className="block font-mono text-[13px] font-semibold text-ink">{tree.treeCode}</span>
                <span className="num block font-mono text-xs text-ink-faint">{formatCoordinate(tree.latitude)}, {formatCoordinate(tree.longitude)}</span>
              </span>
              {tree.archived ? <Badge>Archived</Badge> : null}
            </button>
          </li>
        ))}
      </ul>
      {data.excludedCount > 0 ? (
        <div className="border-t border-line px-5 py-3 text-[13px]">
          <button type="button" className="flex items-center gap-1.5 font-medium text-amber hover:underline" aria-expanded={showExcluded} onClick={() => setShowExcluded((v) => !v)}>
            <MapPinOff className="h-4 w-4" aria-hidden />
            {data.excludedCount} record{data.excludedCount === 1 ? "" : "s"} without valid GPS
          </button>
          {showExcluded ? (
            <ul className="mt-2 max-h-36 space-y-1 overflow-y-auto">
              {data.excluded.map((item) => (
                <li key={item.id ?? item.treeCode} className="flex justify-between gap-2">
                  {item.id ? <Link to={`/trees/${item.id}`} className="font-mono text-forest hover:underline">{item.treeCode}</Link> : <span className="font-mono">{item.treeCode}</span>}
                  <span className="text-ink-faint">{item.reason === "MISSING_COORDINATES" ? "No coordinates" : "Invalid coordinates"}</span>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

export function TreeMapPage() {
  const [state, update] = useUrlState({ status: "active", tag: "", q: "", focus: "" });
  const [search, setSearch] = useSearchBox(state.q, (q) => update({ q }, false));
  const tags = useTags();
  const map = useMapTrees({ status: state.status, tagId: state.tag, q: state.q });
  const markers = useMemo(() => treeMarkers(map.data?.items ?? []), [map.data]);
  const selected = map.data?.items.find((t) => t.id === state.focus) ?? null;
  const select = (id: string | null) => update({ focus: id ?? "" }, false);
  const filterKey = `${state.status}|${state.tag}|${state.q}|${map.data?.placed ?? -1}`;

  return (
    <div className="space-y-4">
      <BackLink to="/trees" label="Tree records" />
      <PageHeader
        eyebrow="Field data"
        title="Tree map"
        description="Every tree with valid GPS from the field app, at the position it was geotagged. Select a marker for details."
      />
      <div className="flex flex-col gap-3 rounded-xl border border-line bg-surface p-3 shadow-card lg:flex-row lg:items-center">
        <SearchInput className="lg:w-64" label="Search by Tree Code" placeholder="Search Tree Code…" value={search} onChange={setSearch} />
        <Segmented
          label="Record status"
          value={state.status as "active" | "archived" | "all"}
          onChange={(status) => update({ status, focus: "" }, false)}
          options={[{ id: "active", label: "Active" }, { id: "archived", label: "Archived" }, { id: "all", label: "All" }]}
        />
        <Select aria-label="Filter by tag" value={state.tag} onChange={(e) => update({ tag: e.target.value, focus: "" }, false)} className="lg:w-44">
          <option value="">All tags</option>
          {(tags.data?.items ?? []).map((tag) => <option key={tag.id} value={tag.id}>{tag.name}</option>)}
        </Select>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 lg:ml-auto" aria-live="polite">
          <span className="num text-[13px] font-medium text-ink" data-testid="map-count">
            {map.data ? `${formatCount(map.data.placed)} tree${map.data.placed === 1 ? "" : "s"} on the map` : "Loading…"}
          </span>
          {map.data ? <NoGpsNote count={map.data.excludedCount} noun={map.data.excludedCount === 1 ? "record" : "records"} /> : null}
        </div>
      </div>
      <MapLegend items={[{ variant: "tree", label: "Active tree" }, { variant: "archived", label: "Archived tree" }, { variant: "selected", label: "Selected" }]} />

      {map.error && !map.data ? (
        <ErrorState error={map.error} onRetry={() => map.refetch()} />
      ) : (
        <MapLayout
          map={
            <>
              <LeafletMap markers={markers} selectedId={state.focus || null} onSelect={select} fitKey={filterKey} label="Tree map" />
              {map.isLoading ? <MapOverlay><Spinner label="Loading tree positions" /></MapOverlay> : null}
              {map.data && map.data.placed === 0 ? (
                <MapOverlay>
                  <p className="font-medium">{map.data.total === 0 ? "No trees match these filters" : "No matching tree has valid GPS"}</p>
                  <p className="mt-0.5 text-[13px] text-ink-muted">
                    {map.data.total === 0 ? "Trees appear here after the field app syncs them." : `${map.data.excludedCount} record(s) have missing or invalid coordinates.`}
                  </p>
                </MapOverlay>
              ) : null}
              {map.data?.truncated ? <MapOverlay>Showing the first {formatCount(map.data.placed)} trees; narrow the filters to see the rest.</MapOverlay> : null}
            </>
          }
          panel={
            selected ? <TreeDetail tree={selected} onClose={() => select(null)} /> : map.data ? <TreeList data={map.data} onSelect={select} /> : <div className="p-5"><Spinner /></div>
          }
        />
      )}
    </div>
  );
}
