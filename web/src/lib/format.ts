const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });
const dateOnly = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });
const shortDay = new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" });
const relative = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
const integer = new Intl.NumberFormat();

function toDate(value: string | number | Date | null | undefined): Date | null {
  if (value === null || value === undefined || value === "") return null;
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function formatDateTime(value: string | null | undefined, empty = "—"): string {
  const date = toDate(value);
  return date ? dateTime.format(date) : empty;
}

export function formatDate(value: string | null | undefined, empty = "—"): string {
  const date = toDate(value);
  return date ? dateOnly.format(date) : empty;
}

export function formatShortDay(value: string): string {
  const date = toDate(value);
  return date ? shortDay.format(date) : value;
}

export function formatRelative(value: string | null | undefined, empty = "never"): string {
  const date = toDate(value);
  if (!date) return empty;
  const seconds = Math.round((date.getTime() - Date.now()) / 1000);
  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ["year", 31536000], ["month", 2592000], ["week", 604800], ["day", 86400], ["hour", 3600], ["minute", 60],
  ];
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) return relative.format(Math.round(seconds / size), unit);
  }
  return "just now";
}

export function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : integer.format(value);
}

/** 0.9375 → "93.8%" */
export function formatPercent(fraction: number | null | undefined, digits = 1): string {
  if (fraction === null || fraction === undefined || Number.isNaN(fraction)) return "—";
  return `${(fraction * 100).toFixed(digits)}%`;
}

export function formatCoordinate(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : value.toFixed(6);
}

export function formatMeters(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : `${value.toFixed(1)} m`;
}

export function formatBytes(bytes: number | null | undefined): string {
  if (!bytes && bytes !== 0) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** SUPER_ADMIN → "Super Admin" */
export function humanize(value: string | null | undefined): string {
  if (!value) return "—";
  return value.toLowerCase().split(/[_\s.]+/).map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
}
