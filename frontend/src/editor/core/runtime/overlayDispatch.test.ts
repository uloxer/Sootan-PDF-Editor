import { afterEach, expect, it, vi } from "vitest";
import { requestPdfProcessing } from "@editor/runtime/processing";
import { buildOverlayPdfsFormData } from "@editor/hooks/tools/overlayPdfs/useOverlayPdfsOperation";
import type { OverlayMode } from "@editor/hooks/tools/overlayPdfs/useOverlayPdfsParameters";
afterEach(() => vi.unstubAllGlobals());
it.each<OverlayMode>([
  "SequentialOverlay",
  "InterleavedOverlay",
  "FixedRepeatOverlay",
])("dispatches selected overlay PDFs in order (%s)", async (mode) => {
  vi.stubGlobal("__SOOTAN_RUNTIME__", {
    kind: "offline-desktop",
    advancedOperations: ["overlay"],
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
  const overlays = [
    new File(["A"], "First overlay.pdf", { type: "application/pdf" }),
    new File(["B"], "Second overlay.pdf", { type: "application/pdf" }),
  ];
  const form = buildOverlayPdfsFormData(
    {
      overlayFiles: overlays,
      overlayMode: mode,
      overlayPosition: 1,
      counts: [2, 1],
    },
    new File(["BASE"], "Original.pdf"),
  );
  const result = await requestPdfProcessing(
    "/api/v1/general/overlay-pdfs",
    form,
  );
  const call = fetch.mock.calls.find(
    ([, options]) =>
      options?.method === "POST" && typeof options.body === "string",
  );
  const request = JSON.parse(String(call?.[1]?.body));
  expect(request.operation).toBe("overlay");
  expect(request.options).toMatchObject({
    overlayMode: mode,
    overlayPosition: "1",
    selectedFiles: [
      { index: 0, name: overlays[0].name, contentType: "application/pdf" },
      { index: 1, name: overlays[1].name, contentType: "application/pdf" },
    ],
  });
  if (mode === "FixedRepeatOverlay")
    expect(request.options.counts).toEqual([2, 1]);
  expect(
    fetch.mock.calls.filter(([url]) => /attachment-[01]$/.test(String(url))),
  ).toHaveLength(2);
  expect(result.headers["content-disposition"]).toContain("converted.pdf");
  expect(fetch.mock.calls.at(-1)?.[1]?.method).toBe("DELETE");
});
