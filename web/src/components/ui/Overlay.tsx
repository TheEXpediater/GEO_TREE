import { AlertTriangle, X } from "lucide-react";
import { useEffect, useId, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";

import { cn } from "@/lib/cn";

import { Button, IconButton } from "./Button";

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** Traps Tab inside the panel, closes on Escape, restores focus to the opener. */
function useModalBehaviour(open: boolean, onClose: () => void, panel: React.RefObject<HTMLElement>, dismissible: boolean) {
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    if (!open) return;
    const opener = document.activeElement as HTMLElement | null;
    const node = panel.current;
    const first = node?.querySelector<HTMLElement>("[data-autofocus]") ?? node?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? node)?.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape" && dismissible) {
        event.stopPropagation();
        closeRef.current();
      } else if (event.key === "Tab" && node) {
        const items = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => el.offsetParent !== null);
        if (items.length === 0) return;
        const [head, tail] = [items[0], items[items.length - 1]];
        if (event.shiftKey && document.activeElement === head) {
          event.preventDefault();
          tail.focus();
        } else if (!event.shiftKey && document.activeElement === tail) {
          event.preventDefault();
          head.focus();
        }
      }
    }
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      document.body.style.overflow = previousOverflow;
      opener?.focus?.();
    };
  }, [open, panel, dismissible]);
}

export function Dialog({ open, onClose, title, description, children, footer, size = "md", dismissible = true }: {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: ReactNode;
  children?: ReactNode;
  footer?: ReactNode;
  size?: "sm" | "md" | "lg" | "xl";
  dismissible?: boolean;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  useModalBehaviour(open, onClose, panel, dismissible);
  if (!open) return null;
  const width = { sm: "max-w-md", md: "max-w-lg", lg: "max-w-2xl", xl: "max-w-5xl" }[size];
  return createPortal(
    <div className="fixed inset-0 z-50 flex items-end justify-center p-0 sm:items-center sm:p-6">
      <div className="absolute inset-0 animate-fade-in bg-forest-deep/40" onClick={dismissible ? onClose : undefined} aria-hidden />
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
        className={cn("relative flex max-h-[92vh] w-full animate-rise-in flex-col rounded-t-2xl bg-surface shadow-raised focus:outline-none sm:rounded-2xl", width)}
      >
        <div className="flex items-start justify-between gap-4 px-6 pb-2 pt-5">
          <div className="min-w-0">
            <h2 id={titleId} className="text-lg font-semibold">{title}</h2>
            {description ? <div id={descriptionId} className="mt-1 text-sm text-ink-muted">{description}</div> : null}
          </div>
          {dismissible ? <IconButton icon={X} label="Close" size="sm" onClick={onClose} className="-mr-2 -mt-1" /> : null}
        </div>
        {children ? <div className="min-h-0 flex-1 overflow-y-auto px-6 py-3">{children}</div> : null}
        {footer ? <div className="flex flex-col-reverse gap-2 border-t border-line px-6 py-4 sm:flex-row sm:justify-end">{footer}</div> : null}
      </div>
    </div>,
    document.body,
  );
}

export function ConfirmDialog({ open, onCancel, onConfirm, title, children, confirmLabel, tone = "danger", busy = false }: {
  open: boolean;
  onCancel: () => void;
  onConfirm: () => void;
  title: string;
  children: ReactNode;
  confirmLabel: string;
  tone?: "danger" | "primary";
  busy?: boolean;
}) {
  return (
    <Dialog
      open={open}
      onClose={busy ? () => undefined : onCancel}
      dismissible={!busy}
      size="sm"
      title={title}
      footer={
        <>
          <Button onClick={onCancel} disabled={busy} data-autofocus>
            Cancel
          </Button>
          <Button variant={tone === "danger" ? "danger" : "primary"} onClick={onConfirm} loading={busy}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="flex gap-3">
        {tone === "danger" ? (
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-danger-soft text-danger">
            <AlertTriangle className="h-4 w-4" aria-hidden />
          </span>
        ) : null}
        <div className="space-y-2 text-sm text-ink-muted">{children}</div>
      </div>
    </Dialog>
  );
}

export function Drawer({ open, onClose, title, subtitle, children, footer }: {
  open: boolean;
  onClose: () => void;
  title: string;
  subtitle?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const titleId = useId();
  useModalBehaviour(open, onClose, panel, true);
  if (!open) return null;
  return createPortal(
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 animate-fade-in bg-forest-deep/30" onClick={onClose} aria-hidden />
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className="relative flex h-full w-full max-w-xl animate-slide-in flex-col bg-surface shadow-raised focus:outline-none"
      >
        <div className="flex items-start justify-between gap-4 border-b border-line px-6 py-4">
          <div className="min-w-0">
            <h2 id={titleId} className="truncate text-lg font-semibold">{title}</h2>
            {subtitle ? <div className="mt-0.5 text-sm text-ink-muted">{subtitle}</div> : null}
          </div>
          <IconButton icon={X} label="Close panel" size="sm" onClick={onClose} className="-mr-2" />
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">{children}</div>
        {footer ? <div className="flex flex-wrap justify-end gap-2 border-t border-line px-6 py-4">{footer}</div> : null}
      </div>
    </div>,
    document.body,
  );
}
