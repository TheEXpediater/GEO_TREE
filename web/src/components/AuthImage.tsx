import { ImageOff } from "lucide-react";
import { useEffect, useState } from "react";

import { fetchImageBlobUrl } from "@/api/client";
import { cn } from "@/lib/cn";

/**
 * Protected images (tree photos, assessment evidence, training images) need the bearer token,
 * which an <img src> cannot send. Fetch the bytes, show them through an object URL, free it after.
 */
export function AuthImage({ src, alt, className, fit = "cover" }: { src: string | null | undefined; alt: string; className?: string; fit?: "cover" | "contain" }) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    setUrl(null);
    setFailed(false);
    if (!src) return;
    const controller = new AbortController();
    let objectUrl: string | null = null;
    fetchImageBlobUrl(src, controller.signal)
      .then((value) => {
        objectUrl = value;
        setUrl(value);
      })
      .catch((error) => {
        if ((error as Error).name !== "AbortError") setFailed(true);
      });
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [src]);

  if (!src || failed) {
    return (
      <div className={cn("flex items-center justify-center bg-sand text-ink-faint", className)} role="img" aria-label={src ? `${alt} (could not load)` : `${alt} (no photo)`}>
        <ImageOff className="h-5 w-5" aria-hidden />
      </div>
    );
  }
  if (!url) return <div className={cn("skeleton !rounded-none", className)} role="img" aria-label={`${alt} (loading)`} />;
  return <img src={url} alt={alt} className={cn(fit === "cover" ? "object-cover" : "object-contain", "animate-fade-in", className)} draggable={false} />;
}
