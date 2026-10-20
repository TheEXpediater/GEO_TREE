import { ApiError, buildUrl, readSession, request, writeSession } from "@/api/client";

describe("api client", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    writeSession(null);
  });

  it("drops empty query values", () => {
    expect(buildUrl("/api/v1/admin/trees", { q: "", page: 2, hasImage: undefined, status: "active" })).toBe("/api/v1/admin/trees?page=2&status=active");
  });

  it("parses the structured error body", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ error: { code: "ADMIN_REQUIRED", message: "Administrator access is required.", details: {} } }), { status: 403 }),
    );
    await expect(request("/api/v1/admin/users")).rejects.toMatchObject({ status: 403, code: "ADMIN_REQUIRED" });
  });

  it("sends the bearer token of a valid session and ignores an expired one", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}", { status: 200 }));
    writeSession({ token: "abc", expiresAt: new Date(Date.now() + 60_000).toISOString(), user: { email: "a@b.co", display_name: "A", role: "ADMIN" } });
    await request("/api/v1/auth/me");
    expect((fetchMock.mock.calls[0][1]?.headers as Record<string, string>).Authorization).toBe("Bearer abc");
    writeSession({ token: "old", expiresAt: new Date(Date.now() - 1000).toISOString(), user: { email: "a@b.co", display_name: "A", role: "ADMIN" } });
    expect(readSession()).toBeNull();
  });

  it("reports network failures as ApiError", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));
    await expect(request("/api/v1/health")).rejects.toBeInstanceOf(ApiError);
  });
});
