import { AlertTriangle, Inbox, Lock, RefreshCw, type LucideIcon } from "lucide-react";
import type { HTMLAttributes, ReactNode } from "react";

import { ApiError } from "@/api/client";
import { cn } from "@/lib/cn";

import { Button } from "./Button";

// Card -------------------------------------------------------------------------------------------
export function Card({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("min-w-0 rounded-xl border border-line bg-surface shadow-card", className)} {...rest} />;
}

export function CardHeader({ title, description, action, icon: Icon, className }: {
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  icon?: LucideIcon;
  className?: string;
}) {
  return (
    <div className={cn("flex items-start justify-between gap-3 border-b border-line px-5 py-4", className)}>
      <div className="flex min-w-0 items-start gap-3">
        {Icon ? (
          <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-moss-soft text-forest">
            <Icon className="h-4 w-4" aria-hidden />
          </span>
        ) : null}
        <div className="min-w-0">
          <h2 className="text-[15px] font-semibold">{title}</h2>
          {description ? <p className="mt-0.5 text-[13px] text-ink-muted">{description}</p> : null}
        </div>
      </div>
      {action ? <div className="shrink-0">{action}</div> : null}
    </div>
  );
}

// Badge ------------------------------------------------------------------------------------------
export type Tone = "neutral" | "green" | "amber" | "red" | "clay" | "sky" | "forest";

const TONES: Record<Tone, string> = {
  neutral: "bg-sand text-ink-muted ring-line-strong/60",
  green: "bg-moss-soft text-tamarind ring-moss/25",
  forest: "bg-forest text-cream ring-forest",
  amber: "bg-amber-soft text-amber ring-amber/25",
  red: "bg-danger-soft text-danger ring-danger/20",
  clay: "bg-clay-soft text-clay ring-clay/25",
  sky: "bg-sky-soft text-sky ring-sky/20",
};

export function Badge({ tone = "neutral", dot = false, className, children }: { tone?: Tone; dot?: boolean; className?: string; children: ReactNode }) {
  return (
    <span className={cn("inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset", TONES[tone], className)}>
      {dot ? <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden /> : null}
      {children}
    </span>
  );
}

const STATUS_TONES: Record<string, Tone> = {
  READY: "green",
  COMPLETE: "green",
  COMPLETED: "green",
  ACTIVE: "green",
  GOOD: "green",
  SYNCED: "green",
  TRAINING: "sky",
  QUEUED: "amber",
  PROCESSING: "sky",
  PENDING: "amber",
  PENDING_FEATURES: "amber",
  ACCEPTABLE: "amber",
  UNCERTAIN: "amber",
  UNTRAINED: "neutral",
  DISABLED: "neutral",
  INSUFFICIENT: "clay",
  LOW: "clay",
  ERROR: "red",
  FAILED: "red",
  INVALIDATED: "red",
  SUSPENDED: "red",
  RETIRED: "neutral",
};

const STATUS_LABELS: Record<string, string> = {
  PENDING_FEATURES: "Computing features",
  INSUFFICIENT: "Too few images",
  COMPLETE: "Complete",
};

export function StatusBadge({ status, label }: { status: string | null | undefined; label?: string }) {
  const key = (status ?? "UNKNOWN").toUpperCase();
  const text = label ?? STATUS_LABELS[key] ?? key.charAt(0) + key.slice(1).toLowerCase().replace(/_/g, " ");
  return (
    <Badge tone={STATUS_TONES[key] ?? "neutral"} dot>
      {text}
    </Badge>
  );
}

// Loading / empty / error ------------------------------------------------------------------------
export function Skeleton({ className }: { className?: string }) {
  return <div className={cn("skeleton", className)} aria-hidden />;
}

export function Spinner({ label = "Loading" }: { label?: string }) {
  return (
    <span role="status" className="inline-flex items-center gap-2 text-sm text-ink-muted">
      <RefreshCw className="h-4 w-4 animate-spin" aria-hidden />
      {label}
    </span>
  );
}

export function EmptyState({ icon: Icon = Inbox, title, description, action, className }: {
  icon?: LucideIcon;
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("survey-grid flex flex-col items-center justify-center rounded-xl px-6 py-12 text-center", className)}>
      <span className="flex h-12 w-12 items-center justify-center rounded-full border border-line bg-surface text-moss shadow-card">
        <Icon className="h-5 w-5" aria-hidden />
      </span>
      <h3 className="mt-4 text-[15px] font-semibold">{title}</h3>
      {description ? <p className="mt-1 max-w-md text-sm text-ink-muted">{description}</p> : null}
      {action ? <div className="mt-5">{action}</div> : null}
    </div>
  );
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return "Something went wrong.";
}

export function ErrorState({ error, onRetry, className }: { error: unknown; onRetry?: () => void; className?: string }) {
  const apiError = error instanceof ApiError ? error : null;
  if (apiError?.status === 403) {
    return (
      <EmptyState icon={Lock} title="You don't have access to this" description={apiError.message} className={className} />
    );
  }
  if (apiError?.code === "AI_DISABLED") {
    return (
      <EmptyState
        icon={AlertTriangle}
        title="The AI subsystem is off on this server"
        description="Classifier classes, training images and model training need a GEO Tree server with AI enabled."
        className={className}
      />
    );
  }
  return (
    <div role="alert" className={cn("flex flex-col items-center justify-center rounded-xl border border-danger/20 bg-danger-soft/40 px-6 py-10 text-center", className)}>
      <AlertTriangle className="h-6 w-6 text-danger" aria-hidden />
      <h3 className="mt-3 text-[15px] font-semibold">Couldn't load this</h3>
      <p className="mt-1 max-w-md text-sm text-ink-muted">{errorMessage(error)}</p>
      {onRetry ? (
        <Button className="mt-4" size="sm" icon={RefreshCw} onClick={onRetry}>
          Try again
        </Button>
      ) : null}
    </div>
  );
}

export function Notice({ tone = "amber", icon: Icon = AlertTriangle, title, children, className }: {
  tone?: "amber" | "red" | "green" | "sky";
  icon?: LucideIcon;
  title?: string;
  children: ReactNode;
  className?: string;
}) {
  const styles = {
    amber: "border-amber/25 bg-amber-soft/60 text-ink [&_svg]:text-amber",
    red: "border-danger/25 bg-danger-soft/60 text-ink [&_svg]:text-danger",
    green: "border-moss/25 bg-moss-soft/70 text-ink [&_svg]:text-tamarind",
    sky: "border-sky/20 bg-sky-soft/70 text-ink [&_svg]:text-sky",
  }[tone];
  return (
    <div className={cn("flex gap-3 rounded-lg border px-4 py-3 text-sm", styles, className)}>
      <Icon className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      <div className="min-w-0">
        {title ? <p className="font-medium">{title}</p> : null}
        <div className={cn("text-ink-muted", title && "mt-0.5")}>{children}</div>
      </div>
    </div>
  );
}

export function ProgressBar({ value, label, tone = "moss" }: { value: number; label: string; tone?: "moss" | "amber" | "clay" }) {
  const percent = Math.max(0, Math.min(100, Math.round(value * 100)));
  const color = { moss: "bg-moss", amber: "bg-amber", clay: "bg-clay" }[tone];
  return (
    <div role="progressbar" aria-label={label} aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100} className="h-2 w-full overflow-hidden rounded-full bg-sand">
      <div className={cn("h-full rounded-full transition-[width] duration-300", color)} style={{ width: `${percent}%` }} />
    </div>
  );
}

// Page header ------------------------------------------------------------------------------------
export function PageHeader({ title, description, actions, eyebrow }: { title: string; description?: ReactNode; actions?: ReactNode; eyebrow?: string }) {
  return (
    <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div className="min-w-0">
        {eyebrow ? <p className="text-2xs font-semibold uppercase tracking-[0.14em] text-clay">{eyebrow}</p> : null}
        <h1 className="mt-1 text-2xl font-semibold sm:text-[26px]">{title}</h1>
        {description ? <p className="mt-1 max-w-2xl text-sm text-ink-muted">{description}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  );
}

/** A labelled value inside detail panels. */
export function DataItem({ label, children, mono = false }: { label: string; children: ReactNode; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-2xs font-medium uppercase tracking-wider text-ink-faint">{label}</dt>
      <dd className={cn("mt-0.5 break-words text-sm text-ink", mono && "num font-mono text-[13px]")}>{children}</dd>
    </div>
  );
}
