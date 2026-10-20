import { Lock, Map as MapIcon, ScanSearch, X } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { useAssessmentFacets, useAssessments } from "@/api/hooks";
import type { Assessment } from "@/api/types";
import { AuthImage } from "@/components/AuthImage";
import { Button } from "@/components/ui/Button";
import { Card, DataItem, EmptyState, ErrorState, Notice, PageHeader, Skeleton, StatusBadge } from "@/components/ui/Display";
import { Input, SearchInput, Select } from "@/components/ui/Form";
import { Pagination, Table, Td, Th } from "@/components/ui/Navigation";
import { Drawer } from "@/components/ui/Overlay";
import { useSearchBox, useUrlState } from "@/lib/hooks";
import { formatDateTime, formatPercent, humanize } from "@/lib/format";

export function resultLabel(item: Pick<Assessment, "className" | "errorCode" | "status">): string {
  if (item.className) return item.className;
  if (item.errorCode === "AI_MODEL_NOT_READY") return "Model not ready";
  if (item.errorCode) return humanize(item.errorCode);
  return humanize(item.status);
}

export function AssessmentDrawer({ item, onClose }: { item: Assessment | null; onClose: () => void }) {
  return (
    <Drawer
      open={Boolean(item)}
      onClose={onClose}
      title={item ? resultLabel(item) : ""}
      subtitle={item ? <>Tree <Link to={`/trees/${item.treeId}`} className="font-mono font-medium text-forest hover:underline">{item.treeCode ?? item.treeId}</Link> · {formatDateTime(item.createdAt)}</> : null}
    >
      {item ? (
        <div className="space-y-5">
          <AuthImage src={item.imageUrl} alt={`Leaf assessed ${formatDateTime(item.createdAt)}`} fit="contain" className="aspect-[4/3] w-full rounded-xl border border-line bg-sand" />
          <Notice tone="sky" icon={Lock}>This scan is a historical record. It is never edited, and later model versions do not change its result.</Notice>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-4">
            <DataItem label="Status"><StatusBadge status={item.status} /></DataItem>
            <DataItem label="Confidence" mono>{formatPercent(item.confidence)}</DataItem>
            <DataItem label="Predicted condition">{item.className ?? "—"}</DataItem>
            <DataItem label="Model">{item.modelDisplayName ?? item.modelName} · v{item.modelVersion}</DataItem>
            <DataItem label="Scanned">{formatDateTime(item.createdAt)}</DataItem>
            <DataItem label="Completed">{formatDateTime(item.completedAt)}</DataItem>
            <DataItem label="Recorded by">{item.createdBy ?? "—"}</DataItem>
            <DataItem label="Server version" mono>{item.serverVersion ?? "—"}</DataItem>
          </dl>
          {item.errorCode ? (
            <Notice tone="amber" title={humanize(item.errorCode)}>{item.errorMessage ?? "The assessment did not produce a result."}</Notice>
          ) : null}
          <p className="font-mono text-2xs text-ink-faint">Assessment ID {item.assessmentId}</p>
        </div>
      ) : null}
    </Drawer>
  );
}

export function AssessmentsPage() {
  const navigate = useNavigate();
  const [state, update] = useUrlState({ q: "", tree: "", status: "", className: "", from: "", to: "", page: "1" });
  const [search, setSearch] = useSearchBox(state.q, (q) => update({ q }));
  const facets = useAssessmentFacets();
  const [selected, setSelected] = useState<Assessment | null>(null);
  const list = useAssessments({
    q: state.tree ? undefined : state.q,
    treeId: state.tree,
    status: state.status,
    className: state.className,
    from: state.from,
    to: state.to,
    page: Number(state.page),
    pageSize: 25,
  });
  const filtered = Boolean(state.q || state.tree || state.status || state.className || state.from || state.to);
  const treeFilterCode = state.tree ? list.data?.items[0]?.treeCode : null;

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Field data"
        title="Leaf assessments"
        description="Every leaf scan from the field app as its own historical record, including failed attempts. Assessment photos are evidence, separate from classifier training images."
        actions={
          <Button variant="primary" icon={MapIcon} onClick={() => navigate(`/field-evidence/map?${new URLSearchParams({ type: "assessment", ...(state.status ? { status: state.status } : {}), ...(state.from ? { from: state.from } : {}), ...(state.to ? { to: state.to } : {}), ...(state.q ? { q: state.q } : {}) })}`)}>
            View Map
          </Button>
        }
      />
      <Card>
        <div className="grid gap-3 border-b border-line p-4 sm:grid-cols-2 xl:grid-cols-[minmax(0,1.4fr)_repeat(4,minmax(0,1fr))_auto]">
          {state.tree ? (
            <div className="flex h-10 items-center justify-between gap-2 rounded-lg border border-moss/40 bg-moss-soft px-3 text-sm">
              <span className="truncate">Tree <span className="font-mono font-semibold">{treeFilterCode ?? "selected"}</span></span>
              <button type="button" onClick={() => update({ tree: "" })} aria-label="Clear tree filter" className="rounded p-0.5 hover:bg-moss/20">
                <X className="h-4 w-4" aria-hidden />
              </button>
            </div>
          ) : (
            <SearchInput label="Search by Tree Code" placeholder="Tree Code…" value={search} onChange={setSearch} />
          )}
          <Select aria-label="Status" value={state.status} onChange={(e) => update({ status: e.target.value })}>
            <option value="">All statuses</option>
            {(facets.data?.statuses ?? []).map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
          </Select>
          <Select aria-label="Predicted condition" value={state.className} onChange={(e) => update({ className: e.target.value })}>
            <option value="">All conditions</option>
            {(facets.data?.classNames ?? []).map((c) => <option key={c} value={c}>{c}</option>)}
          </Select>
          <Input type="date" aria-label="From date" value={state.from} max={state.to || undefined} onChange={(e) => update({ from: e.target.value })} />
          <Input type="date" aria-label="To date" value={state.to} min={state.from || undefined} onChange={(e) => update({ to: e.target.value })} />
          {filtered ? <Button variant="ghost" onClick={() => update({ q: "", tree: "", status: "", className: "", from: "", to: "" })}>Clear</Button> : <span className="hidden xl:block" />}
        </div>

        {list.isLoading ? (
          <div className="space-y-3 p-4">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-14" />)}</div>
        ) : list.error ? (
          <ErrorState className="m-4" error={list.error} onRetry={() => list.refetch()} />
        ) : !list.data || list.data.items.length === 0 ? (
          <EmptyState className="m-4" icon={ScanSearch} title={filtered ? "No scans match these filters" : "No leaf assessments yet"}
            description={filtered ? "Widen the date range or clear a filter." : "Scans appear here after field devices sync their assessment history."} />
        ) : (
          <>
            <div className="hidden md:block">
              <Table label="Leaf assessments">
                <thead>
                  <tr>
                    <Th>Scan</Th>
                    <Th>Tree</Th>
                    <Th>Result</Th>
                    <Th align="right">Confidence</Th>
                    <Th>Model</Th>
                    <Th>Scanned</Th>
                  </tr>
                </thead>
                <tbody className={list.isFetching ? "opacity-70" : undefined}>
                  {list.data.items.map((item) => (
                    <tr key={item.assessmentId} className="cursor-pointer hover:bg-cream" onClick={() => setSelected(item)}>
                      <Td>
                        <button type="button" onClick={(e) => { e.stopPropagation(); setSelected(item); }} className="block rounded-md" aria-label={`Open scan of ${item.treeCode ?? "tree"} from ${formatDateTime(item.createdAt)}`}>
                          <AuthImage src={item.imageUrl} alt="" className="h-11 w-11 rounded-md" />
                        </button>
                      </Td>
                      <Td className="font-mono text-[13px] font-semibold text-forest">{item.treeCode ?? "—"}</Td>
                      <Td>
                        <div className="flex flex-col items-start gap-1">
                          <span className="text-sm font-medium">{resultLabel(item)}</span>
                          <StatusBadge status={item.status} />
                        </div>
                      </Td>
                      <Td align="right" className="num font-mono text-[13px]">{formatPercent(item.confidence)}</Td>
                      <Td className="text-[13px] text-ink-muted">{item.modelDisplayName ?? item.modelName} · v{item.modelVersion}</Td>
                      <Td className="whitespace-nowrap text-[13px] text-ink-muted">{formatDateTime(item.createdAt)}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </div>
            <ul className="divide-y divide-line md:hidden">
              {list.data.items.map((item) => (
                <li key={item.assessmentId}>
                  <button type="button" onClick={() => setSelected(item)} className="flex w-full gap-3 p-4 text-left hover:bg-cream">
                    <AuthImage src={item.imageUrl} alt="" className="h-14 w-14 shrink-0 rounded-md" />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-medium">{resultLabel(item)}</span>
                        <span className="num text-[13px] text-ink-muted">{formatPercent(item.confidence)}</span>
                      </div>
                      <p className="font-mono text-xs text-forest">{item.treeCode}</p>
                      <p className="text-xs text-ink-faint">{formatDateTime(item.createdAt)}</p>
                    </div>
                  </button>
                </li>
              ))}
            </ul>
            <div className="border-t border-line px-4 py-3">
              <Pagination page={list.data.page} pageSize={list.data.pageSize} total={list.data.total} onPage={(page) => update({ page: String(page) }, false)} noun="scans" />
            </div>
          </>
        )}
      </Card>
      <AssessmentDrawer item={selected} onClose={() => setSelected(null)} />
    </div>
  );
}
