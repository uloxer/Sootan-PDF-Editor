import { afterEach, expect, it, vi } from "vitest";
import { supportsLocalConversion } from "@editor/runtime/conversions";
import { requestDesktopWorker } from "@editor/runtime/desktopWorker";
import { convertProcessor } from "@editor/hooks/tools/convert/useConvertOperation";
import { defaultParameters } from "@editor/hooks/tools/convert/useConvertParameters";
afterEach(() => vi.unstubAllGlobals());
function native() {
  vi.stubGlobal("__SOOTAN_RUNTIME__", {
    kind: "offline-desktop",
    advancedOperations: [
      "pdf-text-export",
      "pdf-image-export",
      "image-pdf-create",
      "image-extract",
    ],
  });
  vi.stubGlobal("crypto", {
    randomUUID: () => "5fd10d6d-8ad2-4537-9a85-793420b72227",
  });
}
it("advertises exact modes instead of all branches sharing the same endpoint", () => {
  native();
  expect(supportsLocalConversion("pdf", "txt")).toBe(true);
  expect(supportsLocalConversion("pdf", "rtf")).toBe(false);
  expect(supportsLocalConversion("pdf", "webp")).toBe(false);
  expect(supportsLocalConversion("tiff", "pdf")).toBe(true);
  expect(supportsLocalConversion("svg", "pdf")).toBe(false);
});
it("dispatches the actual Convert processor and retains ZIP filename/MIME", async () => {
  native();
  const fetch = vi.fn(async (_url: string, _options?: RequestInit) => ({
    ok: true,
    headers: new Headers({ "Content-Type": "application/zip" }),
    blob: async () => new Blob(["ZIP"], { type: "application/zip" }),
  }));
  vi.stubGlobal("fetch", fetch);
  const result = await convertProcessor(
    { ...defaultParameters, fromExtension: "pdf", toExtension: "png" },
    [new File(["PDF"], "Swona.pdf")],
  );
  expect(result.files[0].name).toBe("converted.zip");
  expect(result.files[0].type).toBe("application/zip");
  expect(JSON.parse(String(fetch.mock.calls[1][1]?.body))).toMatchObject({
    operation: "pdf-image-export",
    options: { dpi: "300", singleOrMultiple: "multiple" },
  });
});
it("streams multiple selected image grants without requiring a PDF", async () => {
  native();
  const fetch = vi.fn(async (_url: string, _options?: RequestInit) => ({
    ok: true,
    headers: new Headers({ "Content-Type": "application/pdf" }),
    blob: async () => new Blob(["PDF"]),
  }));
  vi.stubGlobal("fetch", fetch);
  const form = new FormData();
  form.append(
    "fileInput",
    new File(["PNG"], "Hill.png", { type: "image/png" }),
  );
  form.append(
    "fileInput",
    new File(["TIFF"], "Cliff.tiff", { type: "image/tiff" }),
  );
  await requestDesktopWorker("/api/v1/convert/img/pdf", form);
  expect(fetch.mock.calls.map((call) => call[0])).toEqual([
    expect.stringMatching(/\/input$/),
    expect.stringMatching(/\/attachment-0$/),
    expect.stringMatching(/\/attachment-1$/),
    expect.stringMatching(/\/run$/),
    expect.stringMatching(/\/job$/),
  ]);
});
it("rejects unconnected explicit output modes before copying selected bytes", async () => {
  native();
  const fetch = vi.fn();
  vi.stubGlobal("fetch", fetch);
  await expect(
    convertProcessor(
      { ...defaultParameters, fromExtension: "pdf", toExtension: "rtf" },
      [new File(["PDF"], "Swona.pdf")],
    ),
  ).rejects.toThrow(/not connected/);
  expect(fetch).not.toHaveBeenCalled();
});
