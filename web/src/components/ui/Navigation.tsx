import { ChevronLeft, ChevronRight, MoreVertical, type LucideIcon } from "lucide-react";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";

import { cn } from "@/lib/cn";
import { formatCount } from "@/lib/format";

import { IconButton } from "./Button";

// Pagination -------------------------------------------------------------------------------------
export function Pagination({ page, pageSize, total, onPage, noun = "records" }: {
  page: number;
  pageSize: number;
  total: number;
  onPage: (page: number) => void;
  noun?: string;
}) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const first = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const last = Math.min(total, page * pageSize);
  return (
    <nav aria-label="Pagination" className="flex items-center justify-between gap-3 px-1 text-[13px] text-ink-muted">
      <p className="num">
        {total === 0 ? `No ${noun}` : `${formatCount(first)}–${formatCount(last)} of ${formatCount(total)} ${noun}`}
      </p>
      <div className="flex items-center gap-1">
        <IconButton icon={ChevronLeft} label="Previous page" size="sm" disabled={page <= 1} onClick={() => onPage(page - 1)} />
        <span className="num min-w-[4.5rem] text-center">
          Page {page} of {pages}
        </span>
        <IconButton icon={ChevronRight} label="Next page" size="sm" disabled={page >= pages} onClick={() => onPage(page + 1)} />
      </div>
    </nav>
  );
}

// Tabs -------------------------------------------------------------------------------------------
export interface TabItem<T extends string> {
  id: T;
  label: string;
  icon?: LucideIcon;
  count?: number | null;
}

export function Tabs<T extends string>({ items, value, onChange, label }: { items: TabItem<T>[]; value: T; onChange: (value: T) => void; label: string }) {
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});
  function move(offset: number) {
    const index = items.findIndex((item) => item.id === value);
    const next = items[(index + offset + items.length) % items.length];
    onChange(next.id);
    refs.current[next.id]?.focus();
  }
  return (
    <div role="tablist" aria-label={label} className="-mx-1 flex gap-1 overflow-x-auto border-b border-line px-1">
      {items.map((item) => {
        const selected = item.id === value;
        const Icon = item.icon;
        return (
          <button
            key={item.id}
            ref={(node) => {
              refs.current[item.id] = node;
            }}
            role="tab"
            type="button"
            aria-selected={selected}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(item.id)}
            onKeyDown={(event) => {
              if (event.key === "ArrowRight") move(1);
              if (event.key === "ArrowLeft") move(-1);
            }}
            className={cn(
              "relative -mb-px inline-flex items-center gap-2 whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-medium transition-colors",
              selected ? "border-forest text-forest" : "border-transparent text-ink-muted hover:text-ink",
            )}
          >
            {Icon ? <Icon className="h-4 w-4" aria-hidden /> : null}
            {item.label}
            {item.count !== undefined && item.count !== null ? (
              <span className={cn("num rounded-full px-1.5 text-xs", selected ? "bg-moss-soft text-forest" : "bg-sand text-ink-muted")}>{formatCount(item.count)}</span>
            ) : null}
          </button>
        );
      })}
    </div>
  );
}

// Segmented filter -------------------------------------------------------------------------------
export function Segmented<T extends string>({ options, value, onChange, label }: { options: Array<{ id: T; label: string }>; value: T; onChange: (v: T) => void; label: string }) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex rounded-lg border border-line-strong bg-surface p-0.5 shadow-card">
      {options.map((option) => (
        <button
          key={option.id}
          type="button"
          role="radio"
          aria-checked={option.id === value}
          onClick={() => onChange(option.id)}
          className={cn(
            "h-8 rounded-md px-3 text-[13px] font-medium transition-colors",
            option.id === value ? "bg-forest text-cream" : "text-ink-muted hover:bg-sand hover:text-ink",
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

// Overflow menu (⋮) ------------------------------------------------------------------------------
export interface MenuItem {
  label: string;
  icon?: LucideIcon;
  onSelect: () => void;
  danger?: boolean;
  disabled?: boolean;
}

export function OverflowMenu({ items, label }: { items: MenuItem[]; label: string }) {
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const root = useRef<HTMLDivElement>(null);
  const buttons = useRef<Array<HTMLButtonElement | null>>([]);

  useEffect(() => {
    if (!open) return;
    buttons.current.find(Boolean)?.focus();
    function onDown(event: MouseEvent) {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);

  function onKey(event: React.KeyboardEvent) {
    const list = buttons.current.filter((b): b is HTMLButtonElement => Boolean(b) && !b!.disabled);
    const index = list.indexOf(document.activeElement as HTMLButtonElement);
    if (event.key === "Escape") {
      setOpen(false);
      (root.current?.querySelector("[data-menu-trigger]") as HTMLElement | null)?.focus();
    } else if (event.key === "ArrowDown") {
      event.preventDefault();
      list[(index + 1) % list.length]?.focus();
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      list[(index - 1 + list.length) % list.length]?.focus();
    }
  }

  return (
    <div ref={root} className="relative" onKeyDown={onKey}>
      <IconButton
        icon={MoreVertical}
        label={label}
        size="sm"
        data-menu-trigger
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => setOpen((v) => !v)}
      />
      {open ? (
        <div id={menuId} role="menu" className="absolute right-0 z-30 mt-1 min-w-[12rem] animate-rise-in overflow-hidden rounded-lg border border-line bg-surface py-1 shadow-raised">
          {items.map((item, index) => {
            const Icon = item.icon;
            return (
              <button
                key={item.label}
                ref={(node) => {
                  buttons.current[index] = node;
                }}
                role="menuitem"
                type="button"
                disabled={item.disabled}
                onClick={() => {
                  setOpen(false);
                  item.onSelect();
                }}
                className={cn(
                  "flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm transition-colors disabled:opacity-50",
                  item.danger ? "text-danger hover:bg-danger-soft focus:bg-danger-soft" : "text-ink hover:bg-sand focus:bg-sand",
                  "focus:outline-none",
                )}
              >
                {Icon ? <Icon className="h-4 w-4" aria-hidden /> : null}
                {item.label}
              </button>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}

// Table ------------------------------------------------------------------------------------------
export function Table({ children, label }: { children: ReactNode; label: string }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[640px] border-separate border-spacing-0 text-sm" aria-label={label}>
        {children}
      </table>
    </div>
  );
}

export function Th({ children, className, align = "left" }: { children?: ReactNode; className?: string; align?: "left" | "right" }) {
  return (
    <th scope="col" className={cn("sticky top-0 border-b border-line bg-cream/80 px-4 py-2.5 text-2xs font-semibold uppercase tracking-wider text-ink-faint backdrop-blur", align === "right" ? "text-right" : "text-left", className)}>
      {children}
    </th>
  );
}

export function Td({ children, className, align = "left" }: { children?: ReactNode; className?: string; align?: "left" | "right" }) {
  return <td className={cn("border-b border-line px-4 py-3 align-middle", align === "right" && "text-right", className)}>{children}</td>;
}
