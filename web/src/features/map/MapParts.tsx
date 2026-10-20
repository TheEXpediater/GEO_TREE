import { ArrowLeft, MapPinOff } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "react-router-dom";

import { cn } from "@/lib/cn";

import type { MarkerVariant } from "@/components/map/LeafletMap";

const SWATCH: Record<MarkerVariant | "selected", string> = {
  tree: "bg-forest border-white",
  archived: "bg-ink-faint border-white",
  evidence: "bg-moss border-white",
  "evidence-uncertain": "bg-moss border-[#B8860B]",
  "evidence-failed": "bg-moss border-[#A8432A]",
  selected: "bg-forest border-clay",
};

export function MapLegend({ items }: { items: Array<{ variant: MarkerVariant | "selected"; label: string }> }) {
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1.5 text-[13px] text-ink-muted" aria-label="Map legend">
      {items.map((item) => (
        <li key={item.label} className="flex items-center gap-1.5">
          <span className={cn("h-3.5 w-3.5 shrink-0 rounded-full border-2 shadow-card", SWATCH[item.variant])} aria-hidden />
          {item.label}
        </li>
      ))}
    </ul>
  );
}

export function BackLink({ to, label }: { to: string; label: string }) {
  return (
    <Link to={to} className="inline-flex items-center gap-1.5 text-[13px] font-medium text-ink-muted hover:text-ink">
      <ArrowLeft className="h-4 w-4" aria-hidden /> {label}
    </Link>
  );
}

/** Map + side panel. The map keeps a usable height on phones and fills the viewport on desktops. */
export function MapLayout({ map, panel }: { map: ReactNode; panel: ReactNode }) {
  return (
    <div className="flex flex-col gap-4 lg:h-[calc(100vh-22rem)] lg:min-h-[480px] lg:flex-row">
      <div className="relative h-[58vh] min-h-[340px] overflow-hidden rounded-xl border border-line shadow-card lg:h-auto lg:flex-1">{map}</div>
      <aside className="flex min-h-0 flex-col overflow-hidden rounded-xl border border-line bg-surface shadow-card lg:w-[380px]">{panel}</aside>
    </div>
  );
}

/** Message laid over the map (loading, empty) without hiding the attribution. */
export function MapOverlay({ children }: { children: ReactNode }) {
  return (
    <div className="pointer-events-none absolute inset-x-0 top-0 z-[500] flex justify-center p-4">
      <div className="pointer-events-auto max-w-md rounded-xl border border-line bg-surface/95 px-4 py-3 text-center shadow-raised backdrop-blur">{children}</div>
    </div>
  );
}

export function NoGpsNote({ count, noun }: { count: number; noun: string }) {
  if (count === 0) return null;
  return (
    <span className="inline-flex items-center gap-1.5 text-[13px] text-amber">
      <MapPinOff className="h-4 w-4" aria-hidden />
      {count} {noun} without valid GPS (not shown)
    </span>
  );
}
