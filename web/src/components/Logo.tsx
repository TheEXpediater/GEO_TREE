import { cn } from "@/lib/cn";

/** GEO Tree mark: location pin + tamarind leaf + a compass tick. Same artwork as the favicon. */
export function LogoMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 64 64" className={cn("h-9 w-9", className)} aria-hidden>
      <rect width="64" height="64" rx="14" fill="#1F3D2B" />
      <path d="M32 10c-9.4 0-17 7.4-17 16.6C15 39 32 54 32 54s17-15 17-27.4C49 17.4 41.4 10 32 10z" fill="#F4EEDF" />
      <path d="M32 18c5 3.2 7.6 7.4 7.6 12.4 0 3.6-1.6 6.6-4.2 8.6L32 41.6 28.6 39c-2.6-2-4.2-5-4.2-8.6C24.4 25.4 27 21.2 32 18z" fill="#5E7A3A" />
      <path d="M32 22v17" stroke="#F4EEDF" strokeWidth="1.6" strokeLinecap="round" />
      <path d="M32 5v3M32 56v3" stroke="#A65A32" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}

export function Wordmark({ inverse = false, subtitle = "Field Data Admin" }: { inverse?: boolean; subtitle?: string }) {
  return (
    <span className="flex items-center gap-2.5">
      <LogoMark />
      <span className="leading-tight">
        <span className={cn("block text-[15px] font-semibold tracking-tight", inverse ? "text-cream" : "text-forest")}>GEO Tree</span>
        <span className={cn("block text-2xs font-medium uppercase tracking-[0.16em]", inverse ? "text-cream/60" : "text-clay")}>{subtitle}</span>
      </span>
    </span>
  );
}
