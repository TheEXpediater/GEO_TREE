import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { writeSession } from "@/api/client";
import { AuthProvider } from "@/auth/AuthContext";
import { ToastProvider } from "@/components/ui/Toast";
import { MobileSettingsPage } from "@/features/mobile/MobileSettingsPage";

const SHA = "98faaf9fe29bc07ec9d2d40b0d8ce94a24c3f018300dbe37bf1b2e304fd36e67";
const BUNDLED = {
  backbone: "dinov2_vits14", backboneDisplayName: "DINOv2 ViT-S/14", embeddingModel: "dinov2_vits14|336px|v1", embeddingDimension: 384,
  modelVersion: 3, classes: ["Healthy", "Spotting", "Powdery Mildew"], confidenceThreshold: 0.6, headSha256: "4ddbdc65", onnxSha256: "58f899dc",
  onnxBytes: 87098063, integrityOk: true,
};
const APK = {
  fileName: "GEO_Tree.apk", bytes: 215260791, sha256: SHA, packageName: "com.geotree.app", versionName: "0.8.1", versionCode: 12, minSdk: 26, targetSdk: 35,
  debuggable: false, signing: { schemes: ["v2"], certificateSha256: ["071b9904"], note: null }, bundledModel: BUNDLED,
};
const CHECKS = [
  { id: "package", label: "Package is com.geotree.app", ok: true },
  { id: "signer_pinned", label: "Signing certificate matches the established release key", ok: null },
];
const BASELINE = {
  id: "r1", status: "PUBLISHED", baseline: true, versionName: "0.8.1", versionCode: 12, packageName: "com.geotree.app", apkFileName: "GEO_Tree.apk",
  apkBytes: 215260791, apkSha256: SHA, signerSha256: ["071b9904"], signatureSchemes: ["v2"], minSdk: 26, targetSdk: 35, debuggable: false,
  model: { modelVersion: 3, backbone: "dinov2_vits14", classes: BUNDLED.classes, headSha256: "4ddbdc65", onnxSha256: "58f899dc" }, dataset: null,
  notes: "Already served", downloadUrl: "https://geotree.example.org/download/GEO_Tree.apk", registeredAt: "2026-10-24T10:00:00Z",
  originalPublishedAt: null, registeredBy: { email: "super@example.com", role: "SUPER_ADMIN" }, events: [],
};

function overview(health: string, release: typeof BASELINE | null) {
  return {
    health, healthReason: health === "HEALTHY" ? "The served APK matches the current release record." : "An APK is served but no release record describes it yet.",
    download: { configured: true, pageUrl: "https://geotree.example.org/download", apkUrl: "https://geotree.example.org/download/GEO_Tree.apk", https: true, distributionConfigured: true },
    servedApk: APK, servedApkError: null, checks: CHECKS, pinnedSignerSha256: null, currentRelease: release, servedRelease: release,
    qr: { url: "https://geotree.example.org/download", pngDataUri: "data:image/png;base64,AAAA" },
  };
}

const MODEL = {
  pipeline: "One frozen DINOv2 feature extractor and one lightweight classifier head with 3 classes (not one model per class).",
  server: null, serverAiRuntime: false, bundled: { ...BUNDLED, apkVersionName: "0.8.1", apkVersionCode: 12, inference: "on-device (offline, ONNX Runtime)" },
  bundledError: null, comparison: null, latestJob: null, updateNote: "Training a new server model does not change the model inside installed APKs.",
  evaluations: [{
    id: "e1", modelVersion: 3, headSha256: "4ddbdc65", type: "locked_test", label: "Locked held-out test (never trained on)", samples: 37, accuracy: 34 / 37,
    balancedAccuracy: 0.89, macroF1: 0.878, perClass: [], confusionMatrix: null, details: { correct: 34 },
    limitations: "Test photos come from the same sessions as training; may be optimistic.", sourceFile: "evaluation_v3.json", sourceSha256: "abc",
    evaluatedAt: null, recordedAt: "2026-10-24T10:00:00Z",
  }],
};

const ARCHIVE = {
  id: "a".repeat(32), fileName: "data-sets.zip", bytes: 210159482, sha256: "82775506a025599e", status: "READY", origin: "upload", uploadedAt: "2026-10-24T10:00:00Z",
  uploadedBy: { email: "admin@gmail.com", role: "ADMIN" }, validatedAt: "2026-10-24T10:01:00Z", error: null, split: null, activation: null,
  summary: {
    entries: 184, imageFiles: 184, accepted: 181, statusCounts: { accepted: 181, duplicate: 1, conflict: 2 }, formats: { jpg: 91, heic: 93 },
    byClass: { Healthy: { accepted: 34 }, Spotting: { accepted: 39, conflict: 1 }, "Powdery Mildew": { accepted: 108, duplicate: 1, conflict: 1 } },
    acceptedByClass: { Healthy: 34, Spotting: 39, "Powdery Mildew": 108 }, acceptedBySource: { Annah: { Healthy: 14 } },
    remappedLabels: [{ from: "Mold", to: "Powdery Mildew", count: 50 }], unknownLabels: [], conflictFamilies: 1, alreadyInActiveDataset: 0,
    uncompressedBytes: 211084501, compressedBytes: 210102300, decodeChecked: true, heicDecodeChecked: true, labelPolicy: "Mold -> Powdery Mildew is owner-authorized.",
  },
};

const DATASET = {
  activeTrainingImages: { total: 144, byClass: { Healthy: 27, "Powdery Mildew": 86, Spotting: 31 }, bySource: {}, note: "Images the server trains from." },
  activeArchive: null, consistency: null, activeModel: null, leafAssessmentPhotos: 6, leafAssessmentNote: "Field photos are separate.",
  archives: [ARCHIVE], archivesTotal: 1, uploadsEnabled: true, maxUploadBytes: 600 * 1024 * 1024, decodeChecked: true,
};

function mockApi(routes: Record<string, unknown>) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = new URL(String(input), "http://localhost");
    const key = `${init?.method ?? "GET"} ${url.pathname}`;
    const body = routes[key] ?? routes[url.pathname];
    if (body === undefined) return new Response(JSON.stringify({ error: { code: "NOT_FOUND", message: "Not found." } }), { status: 404 });
    return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
  });
}

function renderPage(role: string, path = "/mobile") {
  writeSession({ token: "t", expiresAt: new Date(Date.now() + 3_600_000).toISOString(), user: { email: `${role}@example.com`, display_name: role, role } });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <ToastProvider>
          <MemoryRouter initialEntries={[path]}>
            <MobileSettingsPage />
          </MemoryRouter>
        </ToastProvider>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

const ME = (role: string) => ({ email: `${role}@example.com`, display_name: role, role });

afterEach(() => {
  vi.restoreAllMocks();
  writeSession(null);
});

describe("Mobile Settings", () => {
  it("shows the served APK, QR target and lets a Super Admin register an unregistered APK", async () => {
    const fetch = mockApi({
      "/api/v1/auth/me": ME("SUPER_ADMIN"),
      "/api/v1/admin/mobile/overview": overview("UNREGISTERED", null),
      "/api/v1/admin/mobile/releases": { items: [], total: 0, page: 1, pageSize: 10 },
      "/api/v1/admin/mobile/dataset": DATASET,
      "POST /api/v1/admin/mobile/releases": BASELINE,
    });
    renderPage("SUPER_ADMIN");
    expect(await screen.findByText("Served APK is not registered")).toBeInTheDocument();
    expect(screen.getByText(SHA)).toBeInTheDocument();
    expect(screen.getByAltText("QR code for https://geotree.example.org/download")).toBeInTheDocument();
    expect(screen.getByText(/not configured on this server/)).toBeInTheDocument();
    expect(await screen.findByText("No releases recorded yet.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Register served APK" }));
    expect(await screen.findByRole("checkbox")).toBeChecked(); // first record defaults to baseline
    fireEvent.click(screen.getByRole("button", { name: "Register release" }));
    await waitFor(() => {
      const post = fetch.mock.calls.find(([, init]) => init?.method === "POST");
      expect(post && JSON.parse(String(post[1]?.body))).toEqual({ notes: "", datasetArchiveId: null, baseline: true });
    });
  });

  it("hides registration from Admins and shows a baseline's unknown publication date", async () => {
    mockApi({
      "/api/v1/auth/me": ME("ADMIN"),
      "/api/v1/admin/mobile/overview": overview("UNREGISTERED", null),
      "/api/v1/admin/mobile/releases": { items: [BASELINE], total: 1, page: 1, pageSize: 10 },
    });
    renderPage("ADMIN");
    expect(await screen.findByText("A Super Admin can register the served APK.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Register served APK" })).not.toBeInTheDocument();
    expect(await screen.findByText("Baseline")).toBeInTheDocument();
    expect(screen.getByText("Unknown")).toBeInTheDocument();
  });

  it("labels every model metric with its evaluation type, sample count and limitations", async () => {
    mockApi({ "/api/v1/auth/me": ME("ADMIN"), "/api/v1/admin/mobile/model": MODEL });
    renderPage("ADMIN", "/mobile?tab=model");
    expect(await screen.findByText("One classifier, not one model per class")).toBeInTheDocument();
    expect(screen.getByText(/Locked held-out test \(never trained on\) · model v3/)).toBeInTheDocument();
    expect(screen.getByText("91.9% (34/37)")).toBeInTheDocument();
    expect(screen.getByText(/same sessions as training; may be optimistic/)).toBeInTheDocument();
    expect(screen.getByText("Healthy, Spotting, Powdery Mildew")).toBeInTheDocument();
    expect(screen.getByText(/Record only: this server does not run DINOv2/)).toBeInTheDocument();
    expect(screen.getByText("No active model is recorded in this server's database.")).toBeInTheDocument();
  });

  it("shows dataset counts separately and an archive's validation report", async () => {
    mockApi({ "/api/v1/auth/me": ME("ADMIN"), "/api/v1/admin/mobile/dataset": DATASET });
    renderPage("ADMIN", "/mobile?tab=dataset");
    expect(await screen.findByText("Activating a new dataset is a separate, controlled step")).toBeInTheDocument();
    expect(screen.getByText("181 / 184")).toBeInTheDocument();
    expect(screen.getByText("1 / 2")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Report" }));
    expect(await screen.findByText("Mold → Powdery Mildew: 50")).toBeInTheDocument();
    expect(screen.getByText("Conflict: 2")).toBeInTheDocument();
    expect(screen.getByText("Every image fully decoded")).toBeInTheDocument();
  });

  it("flags a served APK that differs from the current release", async () => {
    mockApi({
      "/api/v1/auth/me": ME("ADMIN"),
      "/api/v1/admin/mobile/overview": { ...overview("UNHEALTHY", BASELINE), healthReason: "The served APK is not the current release record (its SHA-256 differs).", servedRelease: null },
      "/api/v1/admin/mobile/releases": { items: [BASELINE], total: 1, page: 1, pageSize: 10 },
    });
    renderPage("ADMIN");
    expect(await screen.findByText("Download does not match the release record")).toBeInTheDocument();
    expect(screen.getByText("Not registered")).toBeInTheDocument();
  });
});
