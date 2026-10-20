import { Activity, Brain, KeyRound, Layers, Tags, TreePine, Users, type LucideIcon } from "lucide-react";

import { useActivity } from "@/api/hooks";
import { Badge, Card, EmptyState, ErrorState, PageHeader, Skeleton } from "@/components/ui/Display";
import { SearchInput, Select } from "@/components/ui/Form";
import { Pagination } from "@/components/ui/Navigation";
import { useSearchBox, useUrlState } from "@/lib/hooks";
import { formatDateTime, formatRelative, humanize } from "@/lib/format";

const AREAS: Array<{ id: string; label: string; icon: LucideIcon }> = [
  { id: "auth", label: "Sign-ins", icon: KeyRound },
  { id: "user", label: "Accounts", icon: Users },
  { id: "tree", label: "Tree records", icon: TreePine },
  { id: "tag", label: "Tags", icon: Tags },
  { id: "classifier", label: "Classifier dataset", icon: Layers },
  { id: "model", label: "Model training", icon: Brain },
];

function iconFor(action: string): LucideIcon {
  return AREAS.find((a) => action.startsWith(a.id))?.icon ?? Activity;
}

export function ActivityPage() {
  const [state, update] = useUrlState({ action: "", actor: "", page: "1" });
  const [actor, setActor] = useSearchBox(state.actor, (value) => update({ actor: value }));
  const activity = useActivity({ action: state.action, actor: state.actor, page: Number(state.page), pageSize: 30 });

  return (
    <div className="space-y-6">
      <PageHeader eyebrow="Administration" title="Activity log" description="Significant actions by people and by the training system. Entries never contain passwords or tokens." />
      <Card>
        <div className="flex flex-col gap-3 border-b border-line p-4 sm:flex-row">
          <Select aria-label="Filter by area" value={state.action} onChange={(e) => update({ action: e.target.value })} className="sm:w-56">
            <option value="">All areas</option>
            {AREAS.map((a) => <option key={a.id} value={a.id}>{a.label}</option>)}
          </Select>
          <SearchInput className="sm:w-72" label="Filter by person" placeholder="Filter by email…" value={actor} onChange={setActor} />
        </div>
        {activity.isLoading ? (
          <div className="space-y-3 p-4">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-12" />)}</div>
        ) : activity.error ? (
          <ErrorState className="m-4" error={activity.error} onRetry={() => activity.refetch()} />
        ) : !activity.data || activity.data.items.length === 0 ? (
          <EmptyState className="m-4" icon={Activity} title="No activity recorded" description="Actions appear here as people use GEO Tree." />
        ) : (
          <>
            <ol className="divide-y divide-line">
              {activity.data.items.map((entry) => {
                const Icon = iconFor(entry.action);
                const failed = entry.action.includes("failed") || entry.action.includes("suspended");
                return (
                  <li key={entry.id} className="flex gap-4 px-5 py-3.5">
                    <span className={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${failed ? "bg-danger-soft text-danger" : "bg-moss-soft text-forest"}`}>
                      <Icon className="h-4 w-4" aria-hidden />
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="text-sm text-ink">{entry.summary}</p>
                      <p className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-ink-faint">
                        <span>{entry.actorEmail ?? "unknown"}</span>
                        {entry.actorRole ? <Badge>{humanize(entry.actorRole)}</Badge> : null}
                        <span className="font-mono">{entry.action}</span>
                      </p>
                    </div>
                    <time dateTime={entry.at} title={formatDateTime(entry.at)} className="shrink-0 text-xs text-ink-muted">{formatRelative(entry.at)}</time>
                  </li>
                );
              })}
            </ol>
            <div className="border-t border-line px-4 py-3">
              <Pagination page={activity.data.page} pageSize={activity.data.pageSize} total={activity.data.total} onPage={(page) => update({ page: String(page) }, false)} noun="entries" />
            </div>
          </>
        )}
      </Card>
    </div>
  );
}
