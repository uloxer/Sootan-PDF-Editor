import { describe, it, expect } from "vitest";
import { isDesktopFileGrant } from "@editor/components/layout/DesktopBridge";
describe("native file grants", () => {
  const origin = "http://127.0.0.1:12345";
  const token = "a".repeat(32);
  const grant = {
    name: "PDF with spaces.pdf",
    lastModified: 123456,
    token,
    url: `${origin}/__sootan_selected/${token}`,
  };
  it("accepts only explicit one-use same-origin PDF grants", () =>
    expect(isDesktopFileGrant(grant, origin)).toBe(true));
  it.each([
    "https://example.com",
    "file:///C:/",
    "http://localhost:12345",
    "http://127.0.0.1:9999",
  ])("rejects other origin %s", (other) =>
    expect(
      isDesktopFileGrant(
        { ...grant, url: `${other}/__sootan_selected/${token}` },
        origin,
      ),
    ).toBe(false),
  );
  it("rejects arbitrary filesystem paths, malformed tokens and non-PDF files", () => {
    expect(
      isDesktopFileGrant(
        { ...grant, url: `${origin}/configs/settings.yml` },
        origin,
      ),
    ).toBe(false);
    expect(isDesktopFileGrant({ ...grant, token: "../settings" }, origin)).toBe(
      false,
    );
    expect(isDesktopFileGrant({ ...grant, name: "script.html" }, origin)).toBe(
      false,
    );
  });
});
