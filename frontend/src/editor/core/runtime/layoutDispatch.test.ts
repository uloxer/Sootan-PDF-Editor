import { afterEach, expect, it, vi } from "vitest";
import { requestPdfProcessing } from "@editor/runtime/processing";
import { buildBookletImpositionFormData } from "@editor/hooks/tools/bookletImposition/useBookletImpositionOperation";
import { defaultParameters } from "@editor/hooks/tools/bookletImposition/useBookletImpositionParameters";
import { buildSingleLargePageFormData } from "@editor/hooks/tools/singleLargePage/useSingleLargePageOperation";

afterEach(() => vi.unstubAllGlobals());
it.each(["booklet-imposition", "pdf-to-single-page"])(
  "dispatches the real %s form through a private layout job",
  async (endpoint) => {
    vi.stubGlobal("__SOOTAN_RUNTIME__", {
      kind: "offline-desktop",
      advancedOperations: ["layout-booklet", "layout-single-page"],
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
    const file = new File(["PDF"], "Pages with spaces.pdf");
    const form =
      endpoint === "booklet-imposition"
        ? buildBookletImpositionFormData(defaultParameters, file)
        : buildSingleLargePageFormData({}, file);
    const result = await requestPdfProcessing(
      `/api/v1/general/${endpoint}`,
      form,
    );
    const request = JSON.parse(String(fetch.mock.calls[1][1]?.body));
    expect(request.operation).toBe(
      endpoint === "booklet-imposition"
        ? "layout-booklet"
        : "layout-single-page",
    );
    if (endpoint === "booklet-imposition")
      expect(request.options).toMatchObject({
        pagesPerSheet: "2",
        spineLocation: "LEFT",
        duplexPass: "BOTH",
      });
    expect(result.headers["content-disposition"]).toContain("converted.pdf");
    expect(fetch.mock.calls.at(-1)?.[0]).toMatch(/\/job$/);
    expect(fetch.mock.calls.at(-1)?.[1]?.method).toBe("DELETE");
  },
);
