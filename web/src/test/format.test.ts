import { formatCoordinate, formatMeters, formatPercent, humanize } from "@/lib/format";

describe("format helpers", () => {
  it("formats percentages from fractions and keeps missing values explicit", () => {
    expect(formatPercent(0.9375)).toBe("93.8%");
    expect(formatPercent(null)).toBe("—");
  });
  it("formats coordinates and accuracy for field records", () => {
    expect(formatCoordinate(15.1449)).toBe("15.144900");
    expect(formatMeters(4.25)).toBe("4.3 m");
  });
  it("humanizes enum values", () => {
    expect(humanize("SUPER_ADMIN")).toBe("Super Admin");
    expect(humanize("model.retrain_requested")).toBe("Model Retrain Requested");
  });
});
