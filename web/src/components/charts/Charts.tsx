/**
 * Small, dependency-free SVG/HTML charts. Each has a hover tooltip, a screen-reader table and
 * text in ink tokens (never the series colour). Status colours (GPS accuracy) were checked with
 * the palette validator: lightness, chroma, colour-vision separation and 3:1 contrast all pass.
 */
import { useId, useState } from "react";

import { cn } from "@/lib/cn";
import { formatCount, formatShortDay } from "@/lib/format";

export const SERIES_COLOR = "#4F6B2F"; // tamarind: single-series magnitude
export const STATUS_COLORS = { good: "#3E7D3A", acceptable: "#B8860B", low: "#A8432A" } as const;

// Weekly columns ---------------------------------------------------------------------------------
export function WeeklyColumns({ data, label, unit }: { data: Array<{ weekStart: string; count: number }>; label: string; unit: string }) {
  const [hover, setHover] = useState<number | null>(null);
  const max = Math.max(1, ...data.map((d) => d.count));
  const ticks = niceTicks(max);
  const top = ticks[ticks.length - 1];
  const height = 140;
  const tableId = useId();
  return (
    <figure className="relative" aria-describedby={tableId}>
      <div className="flex gap-2">
        <div className="num flex h-[140px] flex-col-reverse justify-between pb-0 text-right text-2xs text-ink-faint" aria-hidden>
          {ticks.map((t) => (
            <span key={t} className="-my-1.5 leading-3">{formatCount(t)}</span>
          ))}
        </div>
        <div className="relative flex-1">
          <div className="absolute inset-x-0 top-0 flex h-[140px] flex-col-reverse justify-between" aria-hidden>
            {ticks.map((t) => (
              <div key={t} className="h-px bg-line" />
            ))}
          </div>
          <div className="relative flex h-[140px] items-end gap-[2px]" onMouseLeave={() => setHover(null)}>
            {data.map((d, i) => (
              <div
                key={d.weekStart}
                className="group flex h-full flex-1 cursor-default items-end justify-center"
                onMouseEnter={() => setHover(i)}
              >
                <div
                  className={cn("w-full max-w-[24px] rounded-t transition-opacity", hover !== null && hover !== i && "opacity-60")}
                  style={{ height: d.count ? Math.max(3, (d.count / top) * height) : 0, background: SERIES_COLOR }}
                />
              </div>
            ))}
          </div>
          {hover !== null ? (
            <div
              className="pointer-events-none absolute -top-2 z-10 -translate-x-1/2 -translate-y-full whitespace-nowrap rounded-md border border-line bg-surface px-2.5 py-1.5 text-xs shadow-raised"
              style={{ left: `${((hover + 0.5) / data.length) * 100}%` }}
              aria-hidden
            >
              <span className="text-ink-muted">Week of {formatShortDay(data[hover].weekStart)}</span>
              <span className="num ml-2 font-semibold text-ink">{formatCount(data[hover].count)} {unit}</span>
            </div>
          ) : null}
          <div className="num mt-1.5 flex justify-between text-2xs text-ink-faint" aria-hidden>
            <span>{formatShortDay(data[0]?.weekStart ?? "")}</span>
            <span>{formatShortDay(data[data.length - 1]?.weekStart ?? "")}</span>
          </div>
        </div>
      </div>
      <table id={tableId} className="sr-only">
        <caption>{label}</caption>
        <thead><tr><th>Week starting</th><th>{unit}</th></tr></thead>
        <tbody>{data.map((d) => <tr key={d.weekStart}><td>{d.weekStart}</td><td>{d.count}</td></tr>)}</tbody>
      </table>
    </figure>
  );
}

function niceTicks(max: number): number[] {
  const step = max <= 4 ? 1 : max <= 10 ? 2 : Math.pow(10, Math.floor(Math.log10(max))) * (max / Math.pow(10, Math.floor(Math.log10(max))) > 5 ? 2 : 1);
  const top = Math.ceil(max / step) * step;
  const ticks: number[] = [];
  for (let v = 0; v <= top; v += step) ticks.push(v);
  return ticks;
}

// Horizontal bars (one series, labelled) ---------------------------------------------------------
export function BarList({ items, label, emptyText }: { items: Array<{ label: string; value: number; hint?: string }>; label: string; emptyText: string }) {
  const max = Math.max(1, ...items.map((i) => i.value));
  if (items.length === 0) return <p className="py-6 text-center text-sm text-ink-faint">{emptyText}</p>;
  return (
    <ul className="space-y-3" aria-label={label}>
      {items.map((item) => (
        <li key={item.label} className="group" title={`${item.label}: ${formatCount(item.value)}${item.hint ? ` · ${item.hint}` : ""}`}>
          <div className="flex items-baseline justify-between gap-3 text-[13px]">
            <span className="truncate text-ink">{item.label}</span>
            <span className="num shrink-0 font-medium text-ink">
              {formatCount(item.value)}
              {item.hint ? <span className="ml-1.5 font-normal text-ink-faint">{item.hint}</span> : null}
            </span>
          </div>
          <div className="mt-1.5 h-2 rounded-full bg-sand">
            <div className="h-full rounded-full transition-[width] duration-500 group-hover:opacity-90" style={{ width: `${(item.value / max) * 100}%`, background: SERIES_COLOR }} />
          </div>
        </li>
      ))}
    </ul>
  );
}

// Stacked status bar (GPS accuracy) --------------------------------------------------------------
export function AccuracyBar({ good, acceptable, low }: { good: number; acceptable: number; low: number }) {
  const total = good + acceptable + low;
  const segments = [
    { key: "good", label: "Good ≤ 5 m", value: good, color: STATUS_COLORS.good },
    { key: "acceptable", label: "Acceptable ≤ 10 m", value: acceptable, color: STATUS_COLORS.acceptable },
    { key: "low", label: "Low", range: "> 10 m", value: low, color: STATUS_COLORS.low },
  ];
  return (
    <div>
      <div className="flex h-3 gap-[2px] overflow-hidden rounded-full bg-sand" role="img" aria-label={`GPS accuracy: ${good} good, ${acceptable} acceptable, ${low} low`}>
        {total > 0
          ? segments.filter((s) => s.value > 0).map((s) => (
              <div key={s.key} title={`${s.label} (${s.range}): ${s.value}`} style={{ width: `${(s.value / total) * 100}%`, background: s.color }} />
            ))
          : null}
      </div>
      <ul className="mt-3 grid grid-cols-3 gap-2 text-[13px]">
        {segments.map((s) => (
          <li key={s.key} className="min-w-0">
            <span className="flex items-center gap-1.5 text-ink-muted">
              <span className="h-2 w-2 shrink-0 rounded-full" style={{ background: s.color }} aria-hidden />
              <span>{s.label}</span>
            </span>
            <span className="mt-0.5 block pl-3.5 text-xs text-ink-faint">{s.range}</span>
            <span className="num mt-0.5 block pl-3.5 text-base font-semibold text-ink">{formatCount(s.value)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

// Confusion matrix (sequential, one hue) ---------------------------------------------------------
export function ConfusionMatrix({ labels, matrix }: { labels: string[]; matrix: number[][] }) {
  const max = Math.max(1, ...matrix.flat());
  return (
    <div className="overflow-x-auto">
      <table className="border-separate border-spacing-[2px] text-[13px]">
        <caption className="sr-only">Confusion matrix: rows are the true class, columns the predicted class</caption>
        <thead>
          <tr>
            <th scope="col" className="px-2 py-1 text-left text-2xs font-medium uppercase tracking-wider text-ink-faint">True ↓ / Predicted →</th>
            {labels.map((l) => (
              <th key={l} scope="col" className="max-w-[7rem] truncate px-2 py-1 text-center text-xs font-medium text-ink-muted">{l}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {matrix.map((row, r) => (
            <tr key={labels[r]}>
              <th scope="row" className="max-w-[8rem] truncate px-2 py-1 text-left text-xs font-medium text-ink-muted">{labels[r]}</th>
              {row.map((value, c) => {
                const strength = value / max;
                return (
                  <td
                    key={c}
                    title={`True ${labels[r]}, predicted ${labels[c]}: ${value}`}
                    className={cn("num h-11 min-w-[3.25rem] rounded-md text-center font-semibold", strength > 0.55 ? "text-cream" : "text-ink", r === c && "ring-1 ring-inset ring-forest/30")}
                    style={{ background: value ? `rgb(31 61 43 / ${0.08 + strength * 0.82})` : "rgb(var(--sand))" }}
                  >
                    {value}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// Ring meter for a single percentage (headline figure) -------------------------------------------
export function RingMeter({ percent, label }: { percent: number | null; label: string }) {
  const radius = 26;
  const circumference = 2 * Math.PI * radius;
  const value = percent ?? 0;
  return (
    <svg viewBox="0 0 64 64" className="h-16 w-16 -rotate-90" role="img" aria-label={percent === null ? `${label}: no data` : `${label}: ${value}%`}>
      <circle cx="32" cy="32" r={radius} fill="none" stroke="rgb(var(--sand))" strokeWidth="7" />
      {percent !== null ? (
        <circle cx="32" cy="32" r={radius} fill="none" stroke={SERIES_COLOR} strokeWidth="7" strokeLinecap="round" strokeDasharray={`${(value / 100) * circumference} ${circumference}`} />
      ) : null}
    </svg>
  );
}
