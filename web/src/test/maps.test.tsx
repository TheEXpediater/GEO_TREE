import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";

import type { MapMarker } from "@/components/map/LeafletMap";
import { ToastProvider } from "@/components/ui/Toast";
import { AssessmentsPage } from "@/features/assessments/AssessmentsPage";
import { EvidenceMapPage, evidenceMarkers, groupEvidence } from "@/features/map/EvidenceMapPage";
import { TreeMapPage, treeMarkers } from "@/features/map/TreeMapPage";
import { TreesPage } from "@/features/trees/TreesPage";

// Leaflet needs real layout; in jsdom the map is replaced by one button per marker (same props).
vi.mock("@/components/map/LeafletMap", () => ({
  DEFAULT_CENTER: [15.219, 120.6815],
  LeafletMap: ({ markers, selectedId, onSelect }: { markers: MapMarker[]; selectedId?: string | null; onSelect?: (id: string | null) => void }) => (
    <div data-testid="leaflet-map">
      {markers.map((m) => (
        <button key={m.id} type="button" data-marker={m.id} data-variant={m.variant} aria-pressed={m.id === selectedId} onClick={() => onSelect?.(m.id)}>
          marker {m.title}
        </button>
      ))}
    </div>
  ),
}));

const TREES = {
  items: [
    { id: "t1", treeCode: "GEO-TAM-001", latitude: 15.2178, longitude: 120.6942, accuracyMeters: 4, accuracyLevel: "GOOD", archived: false, hasPhoto: false,
      imageUrl: null, tags: [{ id: "g1", name: "North", color: "clay" }], assessmentCount: 2,
      latestAssessment: { id: "a1", status: "COMPLETED", className: "Spotting", confidence: 0.94, createdAt: "2026-10-08T07:20:00Z" },
      createdAt: "2026-10-01T00:00:00Z", locationCapturedAt: "2026-10-01T00:00:00Z" },
    { id: "t2", treeCode: "GEO-TAM-002", latitude: 15.2165, longitude: 120.6965, accuracyMeters: 12, accuracyLevel: "LOW", archived: true, hasPhoto: false,
      imageUrl: null, tags: [], assessmentCount: 0, latestAssessment: null, createdAt: null, locationCapturedAt: null },
  ],
  total: 3, placed: 2, excludedCount: 1, excluded: [{ id: "t3", treeCode: "GEO-TAM-003", reason: "MISSING_COORDINATES" }], truncated: false,
};

const assessmentOf = (id: string, tree: string, status: string, className: string | null) => ({
  assessmentId: id, treeId: tree, treeCode: tree === "t1" ? "GEO-TAM-001" : "GEO-TAM-002", status, classId: null, className, confidence: className ? 0.9 : null,
  modelName: "dinov2_vits14", modelDisplayName: "DINOv2 ViT-S/14", modelVersion: 1, errorCode: className ? null : "AI_MODEL_NOT_READY", errorMessage: null,
  createdAt: "2026-10-08T07:20:00Z", completedAt: null, imageUrl: null, createdBy: "user@gmail.com", serverVersion: 3,
});

const EVIDENCE = {
  items: [
    { id: "assessment-a2", type: "LEAF_ASSESSMENT", at: "2026-10-09T00:00:00Z", imageUrl: null, treeId: "t2", treeCode: "GEO-TAM-002", latitude: 15.2165, longitude: 120.6965,
      accuracyMeters: 12, treeArchived: false, assessment: assessmentOf("a2", "t2", "FAILED", null) },
    { id: "assessment-a1", type: "LEAF_ASSESSMENT", at: "2026-10-08T07:20:00Z", imageUrl: null, treeId: "t1", treeCode: "GEO-TAM-001", latitude: 15.2178, longitude: 120.6942,
      accuracyMeters: 4, treeArchived: false, assessment: assessmentOf("a1", "t1", "COMPLETED", "Spotting") },
    { id: "photo-t1", type: "REGISTRATION_PHOTO", at: "2026-10-01T00:00:00Z", imageUrl: "/uploads/tree_images/t1.jpg", treeId: "t1", treeCode: "GEO-TAM-001",
      latitude: 15.2178, longitude: 120.6942, accuracyMeters: 4, treeArchived: false },
  ],
  placed: 3, excludedCount: 1, treeCount: 2, truncated: false,
};

type Responder = (url: URL) => { status?: number; body: unknown };

function mockApi(responder: Responder) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = new URL(String(input), "http://localhost");
    const { status = 200, body } = responder(url);
    return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  });
}

function defaultResponder(url: URL) {
  if (url.pathname === "/api/v1/admin/map/trees") return { body: TREES };
  if (url.pathname === "/api/v1/admin/map/evidence") return { body: EVIDENCE };
  if (url.pathname === "/api/v1/admin/tags") return { body: { items: [] } };
  if (url.pathname === "/api/v1/admin/trees") return { body: { items: [], total: 0, page: 1, pageSize: 25 } };
  if (url.pathname === "/api/v1/admin/assessments") return { body: { items: [], total: 0, page: 1, pageSize: 25 } };
  if (url.pathname === "/api/v1/admin/assessments/facets") return { body: { statuses: [], classNames: [] } };
  return { status: 404, body: { error: { code: "NOT_FOUND", message: "Not found." } } };
}

function Where() {
  const location = useLocation();
  return <p data-testid="location">{location.pathname + location.search}</p>;
}

function renderAt(path: string, routes: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>{routes}</Routes>
          <Where />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

const ALL_ROUTES = (
  <>
    <Route path="/trees" element={<TreesPage />} />
    <Route path="/trees/map" element={<TreeMapPage />} />
    <Route path="/trees/:treeId" element={<p>Tree detail page</p>} />
    <Route path="/assessments" element={<AssessmentsPage />} />
    <Route path="/field-evidence/map" element={<EvidenceMapPage />} />
  </>
);

afterEach(() => vi.restoreAllMocks());

describe("View Map actions", () => {
  it("Tree Records opens the internal tree map", async () => {
    mockApi(defaultResponder);
    renderAt("/trees", ALL_ROUTES);
    fireEvent.click(await screen.findByRole("button", { name: "View Map" }));
    expect(screen.getByTestId("location").textContent).toBe("/trees/map");
    expect(await screen.findByRole("heading", { name: "Tree map" })).toBeInTheDocument();
  });

  it("Leaf assessments opens the internal field evidence map", async () => {
    mockApi(defaultResponder);
    renderAt("/assessments", ALL_ROUTES);
    fireEvent.click(await screen.findByRole("button", { name: "View Map" }));
    expect(screen.getByTestId("location").textContent).toBe("/field-evidence/map?type=assessment");
    expect(await screen.findByRole("heading", { name: "Field evidence map" })).toBeInTheDocument();
  });
});

describe("Tree map", () => {
  it("draws one marker per tree with valid GPS and reports excluded records", async () => {
    mockApi(defaultResponder);
    renderAt("/trees/map", ALL_ROUTES);
    const map = await screen.findByTestId("leaflet-map");
    await waitFor(() => expect(within(map).getAllByRole("button")).toHaveLength(2));
    expect(within(map).getByRole("button", { name: "marker GEO-TAM-002 (archived)" })).toHaveAttribute("data-variant", "archived");
    expect(screen.getByTestId("map-count")).toHaveTextContent("2 trees on the map");
    expect(screen.getAllByText(/without valid GPS/).length).toBeGreaterThan(0);
  });

  it("selecting a marker shows that tree and links to its record", async () => {
    mockApi(defaultResponder);
    renderAt("/trees/map", ALL_ROUTES);
    fireEvent.click(await screen.findByRole("button", { name: "marker GEO-TAM-001" }));
    expect(await screen.findByRole("heading", { name: "GEO-TAM-001" })).toBeInTheDocument();
    expect(screen.getByText("15.217800")).toBeInTheDocument();
    expect(screen.getByText(/Spotting · 94%/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("link", { name: /View Tree Details/ }));
    expect(await screen.findByText("Tree detail page")).toBeInTheDocument();
    expect(screen.getByTestId("location").textContent).toBe("/trees/t1");
  });

  it("passes the archive filter to the API (active by default)", async () => {
    const fetchMock = mockApi(defaultResponder);
    renderAt("/trees/map", ALL_ROUTES);
    await screen.findByTestId("map-count");
    const first = fetchMock.mock.calls.map((c) => String(c[0])).find((u) => u.includes("/map/trees"));
    expect(first).toContain("status=active");
    fireEvent.click(screen.getByRole("radio", { name: "All" }));
    await waitFor(() => expect(fetchMock.mock.calls.some((c) => String(c[0]).includes("/map/trees?status=all"))).toBe(true));
  });

  it("shows an empty state and an error state with retry", async () => {
    let fail = true;
    mockApi((url) => {
      if (url.pathname === "/api/v1/admin/map/trees") {
        return fail ? { status: 500, body: { error: { code: "HTTP_ERROR", message: "Server error." } } } : { body: { ...TREES, items: [], placed: 0, total: 0, excludedCount: 0, excluded: [] } };
      }
      return defaultResponder(url);
    });
    renderAt("/trees/map", ALL_ROUTES);
    const retry = await screen.findByRole("button", { name: "Try again" });
    fail = false;
    fireEvent.click(retry);
    expect(await screen.findByText("No trees match these filters")).toBeInTheDocument();
  });

  it("builds markers only from the records it is given", () => {
    expect(treeMarkers(TREES.items as never).map((m) => [m.id, m.lat, m.lng])).toEqual([
      ["t1", 15.2178, 120.6942],
      ["t2", 15.2165, 120.6965],
    ]);
  });
});

describe("Field evidence map", () => {
  it("groups evidence by tree, one marker each, with the tree's position", async () => {
    mockApi(defaultResponder);
    renderAt("/field-evidence/map", ALL_ROUTES);
    const map = await screen.findByTestId("leaflet-map");
    await waitFor(() => expect(within(map).getAllByRole("button")).toHaveLength(2));
    expect(screen.getByTestId("map-count")).toHaveTextContent("3 items at 2 trees");
    expect(within(map).getByRole("button", { name: /GEO-TAM-002: 1 evidence item/ })).toHaveAttribute("data-variant", "evidence-failed");
  });

  it("selecting a marker lists that tree's evidence and opens the tree record", async () => {
    mockApi(defaultResponder);
    renderAt("/field-evidence/map", ALL_ROUTES);
    fireEvent.click(await screen.findByRole("button", { name: /GEO-TAM-001: 2 evidence items/ }));
    const list = await screen.findByRole("list", { name: "Evidence for GEO-TAM-001" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(2);
    expect(within(list).getByText("Registration photo")).toBeInTheDocument();
    expect(within(list).getByText(/Spotting/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("link", { name: /Open tree record/ }));
    expect(screen.getByTestId("location").textContent).toBe("/trees/t1");
  });

  it("sends the type filter to the API", async () => {
    const fetchMock = mockApi(defaultResponder);
    renderAt("/field-evidence/map", ALL_ROUTES);
    await screen.findByTestId("map-count");
    fireEvent.change(screen.getByRole("combobox", { name: "Evidence type" }), { target: { value: "registration" } });
    await waitFor(() => expect(fetchMock.mock.calls.some((c) => String(c[0]).includes("type=registration"))).toBe(true));
  });

  it("group helpers keep counts consistent with the records", () => {
    const groups = groupEvidence(EVIDENCE.items as never);
    expect(groups.map((g) => [g.treeId, g.items.length])).toEqual([["t2", 1], ["t1", 2]]);
    expect(evidenceMarkers(groups).map((m) => m.label)).toEqual(["1", "2"]);
  });
});
