import { CheckCircle2, ChevronRight, Circle, Map as MapIcon, TreePine } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";

import { useTags, useTrees } from "@/api/hooks";
import type { Tree } from "@/api/types";
import { AuthImage } from "@/components/AuthImage";
import { Button } from "@/components/ui/Button";
import { Badge, Card, EmptyState, ErrorState, PageHeader, Skeleton, StatusBadge } from "@/components/ui/Display";
import { SearchInput, Select } from "@/components/ui/Form";
import { Pagination, Segmented, Table, Td, Th } from "@/components/ui/Navigation";
import { useSearchBox, useUrlState } from "@/lib/hooks";
import { formatCoordinate, formatDate, formatMeters, formatPercent } from "@/lib/format";

import { TagChip } from "../tags/TagChip";

/** Path plus only the non-empty parameters (no trailing "?"). */
export function withQuery(path: string, params: Record<string, string>): string {
  const query = new URLSearchParams(Object.entries(params).filter(([, v]) => v)).toString();
  return query ? `${path}?${query}` : path;
}

const ACCURACY_LABELS = { GOOD: "Good", ACCEPTABLE: "Acceptable", LOW: "Low" };

export function AccuracyBadge({ tree }: { tree: Pick<Tree, "accuracyLevel" | "accuracyMeters"> }) {
  return <StatusBadge status={tree.accuracyLevel} label={`${ACCURACY_LABELS[tree.accuracyLevel]} · ${formatMeters(tree.accuracyMeters)}`} />;
}

function Completeness({ tree }: { tree: Tree }) {
  const checks: Array<[string, boolean]> = [
    ["Photo", tree.completeness.photo],
    ["GPS ≤ 10 m", tree.completeness.accuracy],
    ["Details", tree.completeness.descriptive],
  ];
  return (
    <span className="flex items-center gap-1" aria-label={`Completeness: ${checks.filter(([, ok]) => ok).map(([l]) => l).join(", ") || "none"}`}>
      {checks.map(([label, ok]) =>
        ok ? <CheckCircle2 key={label} className="h-4 w-4 text-tamarind" aria-hidden /> : <Circle key={label} className="h-4 w-4 text-line-strong" aria-hidden />,
      )}
    </span>
  );
}

function LatestAssessment({ tree }: { tree: Tree }) {
  const latest = tree.latestAssessment;
  if (!latest) return <span className="text-ink-faint">No scans</span>;
  return (
    <span className="flex flex-col">
      <span className="text-ink">
        {latest.className ?? "Failed attempt"}
        {latest.confidence !== null ? <span className="num ml-1 text-ink-muted">{formatPercent(latest.confidence, 0)}</span> : null}
      </span>
      <span className="text-xs text-ink-faint">{tree.assessmentCount} scan{tree.assessmentCount === 1 ? "" : "s"} · {formatDate(latest.createdAt)}</span>
    </span>
  );
}

export function TreesPage() {
  const navigate = useNavigate();
  const [state, update] = useUrlState({ q: "", status: "active", photo: "", tag: "", sort: "-createdAt", page: "1" });
  const [search, setSearch] = useSearchBox(state.q, (q) => update({ q }));
  const tags = useTags();
  const trees = useTrees({
    q: state.q,
    status: state.status,
    hasImage: state.photo === "" ? undefined : state.photo === "yes",
    tagId: state.tag,
    sort: state.sort,
    page: Number(state.page),
    pageSize: 25,
  });

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Field data"
        title="Tree records"
        description="Trees registered and geotagged with the Android field app. GPS coordinates are field evidence and cannot be edited here."
        actions={
          <Button variant="primary" icon={MapIcon} onClick={() => navigate(withQuery("/trees/map", { status: state.status === "active" ? "" : state.status, tag: state.tag, q: state.q }))}>
            View Map
          </Button>
        }
      />
      <Card>
        <div className="flex flex-col gap-3 border-b border-line p-4 lg:flex-row lg:items-center">
          <SearchInput className="lg:w-72" label="Search by Tree Code" placeholder="Search Tree Code…" value={search} onChange={setSearch} />
          <Segmented
            label="Record status"
            value={state.status as "active" | "archived" | "all"}
            onChange={(status) => update({ status })}
            options={[{ id: "active", label: "Active" }, { id: "archived", label: "Archived" }, { id: "all", label: "All" }]}
          />
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:ml-auto lg:flex">
            <Select aria-label="Filter by photo" value={state.photo} onChange={(e) => update({ photo: e.target.value })} className="lg:w-40">
              <option value="">Any photo</option>
              <option value="yes">With photo</option>
              <option value="no">Without photo</option>
            </Select>
            <Select aria-label="Filter by tag" value={state.tag} onChange={(e) => update({ tag: e.target.value })} className="lg:w-40">
              <option value="">All tags</option>
              {(tags.data?.items ?? []).map((tag) => (
                <option key={tag.id} value={tag.id}>{tag.name}</option>
              ))}
            </Select>
            <Select aria-label="Sort" value={state.sort} onChange={(e) => update({ sort: e.target.value })} className="col-span-2 sm:col-span-1 lg:w-44">
              <option value="-createdAt">Newest first</option>
              <option value="createdAt">Oldest first</option>
              <option value="-updatedAt">Recently updated</option>
              <option value="treeCode">Tree Code A–Z</option>
              <option value="-treeCode">Tree Code Z–A</option>
            </Select>
          </div>
        </div>

        {trees.isLoading ? (
          <div className="space-y-3 p-4" aria-busy="true" aria-label="Loading trees">
            {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-14 w-full" />)}
          </div>
        ) : trees.error ? (
          <ErrorState className="m-4" error={trees.error} onRetry={() => trees.refetch()} />
        ) : trees.data && trees.data.items.length === 0 ? (
          <EmptyState
            className="m-4"
            icon={TreePine}
            title={state.q || state.photo || state.tag ? "No trees match these filters" : state.status === "archived" ? "No archived trees" : "No trees registered yet"}
            description={state.q || state.photo || state.tag ? "Clear a filter or search for another Tree Code." : "Trees appear here after the field app syncs them to the server."}
          />
        ) : trees.data ? (
          <>
            <div className="hidden md:block">
              <Table label="Tree records">
                <thead>
                  <tr>
                    <Th>Tree</Th>
                    <Th>Location</Th>
                    <Th>GPS accuracy</Th>
                    <Th>Leaf condition</Th>
                    <Th>Complete</Th>
                    <Th>Registered</Th>
                    <Th><span className="sr-only">Open</span></Th>
                  </tr>
                </thead>
                <tbody className={trees.isFetching ? "opacity-70 transition-opacity" : undefined}>
                  {trees.data.items.map((tree) => (
                    <tr key={tree.id} className="cursor-pointer transition-colors hover:bg-cream" onClick={() => navigate(`/trees/${tree.id}`)}>
                      <Td>
                        <div className="flex items-center gap-3">
                          <AuthImage src={tree.imageUrl} alt={`Tree ${tree.treeCode}`} className="h-11 w-11 shrink-0 rounded-lg" />
                          <div className="min-w-0">
                            <Link to={`/trees/${tree.id}`} onClick={(e) => e.stopPropagation()} className="font-mono text-[13px] font-semibold text-forest hover:underline">
                              {tree.treeCode}
                            </Link>
                            <div className="mt-1 flex flex-wrap gap-1">
                              {tree.archived ? <Badge tone="neutral">Archived</Badge> : null}
                              {tree.tags.slice(0, 3).map((tag) => <TagChip key={tag.id} tag={tag} />)}
                              {tree.tags.length > 3 ? <Badge>+{tree.tags.length - 3}</Badge> : null}
                            </div>
                          </div>
                        </div>
                      </Td>
                      <Td className="num font-mono text-[13px] text-ink-muted">
                        {formatCoordinate(tree.latitude)}
                        <br />
                        {formatCoordinate(tree.longitude)}
                      </Td>
                      <Td><AccuracyBadge tree={tree} /></Td>
                      <Td className="text-[13px]"><LatestAssessment tree={tree} /></Td>
                      <Td><Completeness tree={tree} /></Td>
                      <Td className="whitespace-nowrap text-[13px] text-ink-muted">{formatDate(tree.createdAt)}</Td>
                      <Td align="right"><ChevronRight className="ml-auto h-4 w-4 text-ink-faint" aria-hidden /></Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </div>
            <ul className="divide-y divide-line md:hidden">
              {trees.data.items.map((tree) => (
                <li key={tree.id}>
                  <Link to={`/trees/${tree.id}`} className="flex gap-3 p-4 transition-colors hover:bg-cream">
                    <AuthImage src={tree.imageUrl} alt={`Tree ${tree.treeCode}`} className="h-16 w-16 shrink-0 rounded-lg" />
                    <div className="min-w-0 flex-1 space-y-1">
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-mono text-[13px] font-semibold text-forest">{tree.treeCode}</span>
                        <Completeness tree={tree} />
                      </div>
                      <AccuracyBadge tree={tree} />
                      <div className="text-[13px]"><LatestAssessment tree={tree} /></div>
                    </div>
                  </Link>
                </li>
              ))}
            </ul>
            <div className="border-t border-line px-4 py-3">
              <Pagination page={trees.data.page} pageSize={trees.data.pageSize} total={trees.data.total} onPage={(page) => update({ page: String(page) }, false)} noun="trees" />
            </div>
          </>
        ) : null}
      </Card>
    </div>
  );
}
