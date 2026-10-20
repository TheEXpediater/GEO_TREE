import {
  Activity,
  BrainCircuit,
  ChevronRight,
  Images,
  Layers,
  LayoutDashboard,
  LogOut,
  Map as MapIcon,
  Menu,
  ScanSearch,
  Tags,
  TreePine,
  Users,
  X,
  type LucideIcon,
} from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router-dom";

import { useAuth } from "@/auth/AuthContext";
import { Wordmark } from "@/components/Logo";
import { cn } from "@/lib/cn";
import { humanize } from "@/lib/format";

interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  /** Overrides the default prefix match (e.g. Tree Records must not light up on the Tree map). */
  match?: (pathname: string) => boolean;
}

export const NAV_GROUPS: Array<{ label: string; items: NavItem[] }> = [
  { label: "Overview", items: [{ to: "/", label: "Dashboard", icon: LayoutDashboard }] },
  {
    label: "Field data",
    items: [
      { to: "/trees", label: "Tree Records", icon: TreePine, match: (p) => p.startsWith("/trees") && !p.startsWith("/trees/map") },
      { to: "/trees/map", label: "Field Map", icon: MapIcon, match: (p) => p.startsWith("/trees/map") || p.startsWith("/field-evidence/map") },
      { to: "/assessments", label: "Leaf Assessments", icon: ScanSearch },
      { to: "/images", label: "Images", icon: Images },
      { to: "/tags", label: "Tags", icon: Tags },
    ],
  },
  {
    label: "Leaf classifier",
    items: [
      { to: "/classifiers", label: "Classifiers", icon: Layers },
      { to: "/models", label: "Models & Training", icon: BrainCircuit },
    ],
  },
  {
    label: "Administration",
    items: [
      { to: "/users", label: "Users", icon: Users },
      { to: "/activity", label: "Activity Log", icon: Activity },
    ],
  },
];

const TITLES: Array<[RegExp, string, string?]> = [
  [/^\/$/, "Dashboard"],
  [/^\/trees\/map/, "Tree Map", "/trees"],
  [/^\/field-evidence\/map/, "Field Evidence Map", "/assessments"],
  [/^\/trees\/[^/]+/, "Tree Record", "/trees"],
  [/^\/trees/, "Tree Records"],
  [/^\/assessments/, "Leaf Assessments"],
  [/^\/images/, "Images"],
  [/^\/tags/, "Tags"],
  [/^\/classifiers\/[^/]+/, "Training Images", "/classifiers"],
  [/^\/classifiers/, "Classifiers"],
  [/^\/models/, "Models & Training"],
  [/^\/users/, "Users"],
  [/^\/activity/, "Activity Log"],
];

function useCrumbs(pathname: string): Array<{ label: string; to?: string }> {
  const match = TITLES.find(([pattern]) => pattern.test(pathname));
  if (!match) return [{ label: "GEO Tree" }];
  const [, title, parent] = match;
  if (!parent) return [{ label: title }];
  const parentTitle = TITLES.find(([pattern]) => pattern.test(parent))?.[1] ?? "";
  return [{ label: parentTitle, to: parent }, { label: title }];
}

function SidebarNav({ onNavigate }: { onNavigate?: () => void }) {
  const { pathname } = useLocation();
  return (
    <nav aria-label="Main" className="flex-1 space-y-6 overflow-y-auto px-3 py-5">
      {NAV_GROUPS.map((group) => (
        <div key={group.label}>
          <p className="px-3 text-2xs font-semibold uppercase tracking-[0.14em] text-cream/45">{group.label}</p>
          <ul className="mt-2 space-y-0.5">
            {group.items.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  end={item.to === "/"}
                  onClick={onNavigate}
                  className={({ isActive: prefixActive }) => {
                    const isActive = item.match ? item.match(pathname) : prefixActive;
                    return cn(
                      "group relative flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors",
                      isActive ? "bg-cream/[0.12] text-cream" : "text-cream/70 hover:bg-cream/[0.06] hover:text-cream",
                    );
                  }}
                  aria-current={item.match ? (item.match(pathname) ? "page" : undefined) : undefined}
                >
                  {({ isActive: prefixActive }) => {
                    const isActive = item.match ? item.match(pathname) : prefixActive;
                    return (
                    <>
                      {isActive ? <span className="absolute inset-y-1.5 left-0 w-[3px] rounded-r bg-[#C9A35B]" aria-hidden /> : null}
                      <item.icon className={cn("h-[18px] w-[18px]", isActive ? "text-[#D8C08A]" : "text-cream/55 group-hover:text-cream/80")} aria-hidden />
                      {item.label}
                    </>
                    );
                  }}
                </NavLink>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  );
}

function AccountFooter() {
  const { user, signOut } = useAuth();
  if (!user) return null;
  const initials = (user.displayName || user.email).split(/[\s@.]+/).filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase()).join("");
  return (
    <div className="border-t border-cream/10 p-3">
      <div className="flex items-center gap-3 rounded-lg px-2 py-2">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-[#C9A35B]/20 text-[13px] font-semibold text-[#E6D3A6]" aria-hidden>
          {initials}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-cream">{user.displayName}</p>
          <p className="truncate text-xs text-cream/55">{humanize(user.role)}</p>
        </div>
        <button
          type="button"
          onClick={() => signOut()}
          className="rounded-lg p-2 text-cream/60 transition-colors hover:bg-cream/10 hover:text-cream"
          aria-label="Sign out"
          title="Sign out"
        >
          <LogOut className="h-4 w-4" aria-hidden />
        </button>
      </div>
    </div>
  );
}

function Sidebar({ onNavigate, footer = true }: { onNavigate?: () => void; footer?: boolean }) {
  return (
    <div className="flex h-full flex-col bg-forest">
      <div className="flex h-16 items-center px-5">
        <Link to="/" onClick={onNavigate} className="rounded-lg focus-visible:outline-cream" aria-label="GEO Tree dashboard">
          <Wordmark inverse />
        </Link>
      </div>
      <SidebarNav onNavigate={onNavigate} />
      {footer ? <AccountFooter /> : null}
    </div>
  );
}

export function AppShell() {
  const location = useLocation();
  const [mobileOpen, setMobileOpen] = useState(false);
  const crumbs = useCrumbs(location.pathname);
  const { user } = useAuth();

  useEffect(() => setMobileOpen(false), [location.pathname]);
  useEffect(() => {
    document.title = `${crumbs[crumbs.length - 1].label} · GEO Tree Admin`;
  }, [crumbs]);

  return (
    <div className="min-h-screen bg-cream">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[70] focus:rounded-lg focus:bg-surface focus:px-4 focus:py-2 focus:shadow-raised">
        Skip to content
      </a>

      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 lg:block">
        <Sidebar />
      </aside>

      {mobileOpen ? (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="Navigation">
          <div className="absolute inset-0 animate-fade-in bg-forest-deep/50" onClick={() => setMobileOpen(false)} aria-hidden />
          <div className="relative h-full w-72 max-w-[85vw] animate-rise-in shadow-raised">
            <Sidebar onNavigate={() => setMobileOpen(false)} />
            <button
              type="button"
              onClick={() => setMobileOpen(false)}
              className="absolute right-3 top-4 rounded-lg p-2 text-cream/70 hover:bg-cream/10 hover:text-cream"
              aria-label="Close navigation"
            >
              <X className="h-5 w-5" aria-hidden />
            </button>
          </div>
        </div>
      ) : null}

      <div className="lg:pl-64">
        <header className="sticky top-0 z-20 border-b border-line bg-cream/90 backdrop-blur">
          <div className="flex h-16 items-center gap-3 px-4 sm:px-6 lg:px-8">
            <button
              type="button"
              onClick={() => setMobileOpen(true)}
              className="-ml-1 rounded-lg p-2 text-ink-muted hover:bg-sand hover:text-ink lg:hidden"
              aria-label="Open navigation"
            >
              <Menu className="h-5 w-5" aria-hidden />
            </button>
            <nav aria-label="Breadcrumb" className="min-w-0 flex-1">
              <ol className="flex items-center gap-1.5 text-sm">
                <li className="hidden text-ink-faint sm:block">GEO Tree</li>
                {crumbs.map((crumb, index) => (
                  <li key={crumb.label} className="flex min-w-0 items-center gap-1.5">
                    <ChevronRight className={cn("h-3.5 w-3.5 shrink-0 text-ink-faint", index === 0 && "hidden sm:block")} aria-hidden />
                    {crumb.to ? (
                      <Link to={crumb.to} className="truncate text-ink-muted hover:text-ink">{crumb.label}</Link>
                    ) : (
                      <span className="truncate font-medium text-ink" aria-current="page">{crumb.label}</span>
                    )}
                  </li>
                ))}
              </ol>
            </nav>
            {user ? (
              <div className="hidden items-center gap-2 text-right sm:flex">
                <div className="leading-tight">
                  <p className="text-[13px] font-medium text-ink">{user.email}</p>
                  <p className="text-2xs uppercase tracking-wider text-ink-faint">{humanize(user.role)}</p>
                </div>
              </div>
            ) : null}
          </div>
        </header>
        <main id="main" tabIndex={-1} className="mx-auto w-full max-w-[1400px] px-4 py-6 focus:outline-none sm:px-6 lg:px-8 lg:py-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

export function PageStack({ children }: { children: ReactNode }) {
  return <div className="space-y-6">{children}</div>;
}
