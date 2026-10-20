import { lazy, Suspense, type ReactNode } from "react";
import { Navigate, Route, Routes, useLocation } from "react-router-dom";

import { isAdminRole, useAuth } from "./auth/AuthContext";
import { EmptyState, Skeleton } from "./components/ui/Display";
import { ActivityPage } from "./features/activity/ActivityPage";
import { ModelsPage } from "./features/ai-models/ModelsPage";
import { AssessmentsPage } from "./features/assessments/AssessmentsPage";
import { LoginPage } from "./features/auth/LoginPage";
import { ClassImagesPage } from "./features/classifiers/ClassImagesPage";
import { ClassifiersPage } from "./features/classifiers/ClassifiersPage";
import { DashboardPage } from "./features/dashboard/DashboardPage";
import { ImagesPage } from "./features/images/ImagesPage";
import { TagsPage } from "./features/tags/TagsPage";
import { TreesPage } from "./features/trees/TreesPage";
import { UsersPage } from "./features/users/UsersPage";
import { AppShell } from "./layouts/AppShell";

// Pages with a map load Leaflet on demand, so the rest of the admin stays light.
const TreeMapPage = lazy(() => import("./features/map/TreeMapPage").then((m) => ({ default: m.TreeMapPage })));
const EvidenceMapPage = lazy(() => import("./features/map/EvidenceMapPage").then((m) => ({ default: m.EvidenceMapPage })));
const TreeDetailPage = lazy(() => import("./features/trees/TreeDetailPage").then((m) => ({ default: m.TreeDetailPage })));

function Lazy({ children }: { children: ReactNode }) {
  return <Suspense fallback={<Skeleton className="h-[60vh] w-full" />}>{children}</Suspense>;
}

/** Route guard for the UI only; the API enforces every permission on its own. */
function RequireAdmin({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const location = useLocation();
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  if (!isAdminRole(user.role)) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAdmin>
            <AppShell />
          </RequireAdmin>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="trees" element={<TreesPage />} />
        <Route path="trees/map" element={<Lazy><TreeMapPage /></Lazy>} />
        <Route path="trees/:treeId" element={<Lazy><TreeDetailPage /></Lazy>} />
        <Route path="field-evidence/map" element={<Lazy><EvidenceMapPage /></Lazy>} />
        <Route path="assessments" element={<AssessmentsPage />} />
        <Route path="images" element={<ImagesPage />} />
        <Route path="tags" element={<TagsPage />} />
        <Route path="classifiers" element={<ClassifiersPage />} />
        <Route path="classifiers/:classId" element={<ClassImagesPage />} />
        <Route path="models" element={<ModelsPage />} />
        <Route path="users" element={<UsersPage />} />
        <Route path="activity" element={<ActivityPage />} />
        <Route path="*" element={<EmptyState title="Page not found" description="This page does not exist in the GEO Tree administration." />} />
      </Route>
    </Routes>
  );
}
