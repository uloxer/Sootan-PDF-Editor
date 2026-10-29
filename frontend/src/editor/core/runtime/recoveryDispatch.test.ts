import { afterEach, expect, it, vi } from "vitest";
import { requestPdfProcessing } from "@editor/runtime/processing";
import { buildCompressFormData } from "@editor/hooks/tools/compress/useCompressOperation";
import { defaultParameters } from "@editor/hooks/tools/compress/useCompressParameters";
import { buildRepairFormData } from "@editor/hooks/tools/repair/useRepairOperation";
afterEach(() => vi.unstubAllGlobals());
function setup() {
  vi.stubGlobal("__SOOTAN_RUNTIME__", {
    kind: "offline-desktop",
    advancedOperations: ["compress", "decompress", "repair"],
  });
  vi.stubGlobal("crypto", {
    randomUUID: () => "5fd10d6d-8ad2-4537-9a85-793420b72227",
  });
  const fetch = vi.fn(async (_url: string, _options?: RequestInit) => ({
    ok: true,
    headers: new Headers({ "Content-Type": "application/pdf" }),
    blob: async () => new Blob(["PDF"]),
  }));
  vi.stubGlobal("fetch", fetch);
  return fetch;
}
it.each(["compress-pdf", "decompress-pdf", "repair"])(
  "dispatches real recovery tool form (%s)",
  async (name) => {
    const fetch = setup();
    const file = new File(["PDF"], "Original.pdf");
    const form =
      name === "compress-pdf"
        ? buildCompressFormData(
            {
              ...defaultParameters,
              compressionMethod: "filesize",
              fileSizeValue: "2",
              fileSizeUnit: "MB",
              compressionLevel: 5,
            },
            file,
          )
        : buildRepairFormData({}, file);
    const result = await requestPdfProcessing(`/api/v1/misc/${name}`, form);
    const request = JSON.parse(String(fetch.mock.calls[1][1]?.body));
    expect(request.operation).toBe(
      name === "compress-pdf"
        ? "compress"
        : name === "decompress-pdf"
          ? "decompress"
          : "repair",
    );
    if (name === "compress-pdf")
      expect(request.options).toMatchObject({
        optimizeLevel: "5",
        expectedOutputSize: "2MB",
        lineArt: "false",
        linearize: "false",
      });
    expect(result.headers["content-disposition"]).toContain("converted.pdf");
    expect(fetch.mock.calls.at(-1)?.[1]?.method).toBe("DELETE");
  },
);
it("refuses unavailable line-art helper before creating a job", async () => {
  const fetch = setup();
  const form = buildCompressFormData(
    { ...defaultParameters, lineArt: true },
    new File(["PDF"], "Original.pdf"),
  );
  await expect(
    requestPdfProcessing("/api/v1/misc/compress-pdf", form),
  ).rejects.toThrow(/ImageMagick/);
  expect(fetch).not.toHaveBeenCalled();
});
