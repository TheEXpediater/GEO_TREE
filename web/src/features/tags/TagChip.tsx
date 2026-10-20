import type { Tag, TagColor } from "@/api/types";
import { cn } from "@/lib/cn";

export const TAG_SWATCHES: Record<TagColor, { dot: string; chip: string; label: string }> = {
  forest: { dot: "bg-forest", chip: "bg-forest-soft text-forest ring-forest/20", label: "Forest" },
  moss: { dot: "bg-moss", chip: "bg-moss-soft text-tamarind ring-moss/25", label: "Moss" },
  clay: { dot: "bg-clay", chip: "bg-clay-soft text-clay ring-clay/25", label: "Clay" },
  amber: { dot: "bg-amber", chip: "bg-amber-soft text-amber ring-amber/25", label: "Amber" },
  sky: { dot: "bg-sky", chip: "bg-sky-soft text-sky ring-sky/20", label: "Sky" },
  stone: { dot: "bg-ink-faint", chip: "bg-sand text-ink-muted ring-line-strong", label: "Stone" },
  berry: { dot: "bg-[#8A3B5C]", chip: "bg-[#F5E4EB] text-[#7A3352] ring-[#8A3B5C]/20", label: "Berry" },
};

export function TagChip({ tag, className }: { tag: Pick<Tag, "name" | "color">; className?: string }) {
  const swatch = TAG_SWATCHES[tag.color] ?? TAG_SWATCHES.moss;
  return (
    <span className={cn("inline-flex max-w-[10rem] items-center gap-1 rounded-md px-1.5 py-0.5 text-xs font-medium ring-1 ring-inset", swatch.chip, className)}>
      <span className={cn("h-1.5 w-1.5 shrink-0 rounded-full", swatch.dot)} aria-hidden />
      <span className="truncate">{tag.name}</span>
    </span>
  );
}
