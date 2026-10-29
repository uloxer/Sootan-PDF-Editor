import { afterEach, expect, it, vi } from "vitest";
import { requestPdfProcessing } from "@editor/runtime/processing";
import { buildPageLayoutFormData } from "@editor/hooks/tools/pageLayout/usePageLayoutOperation";
import { defaultParameters as pageDefaults } from "@editor/hooks/tools/pageLayout/usePageLayoutParameters";
import { buildSplitFormData } from "@editor/hooks/tools/split/useSplitOperation";
import { defaultParameters as splitDefaults } from "@editor/hooks/tools/split/useSplitParameters";
import { SPLIT_METHODS } from "@editor/constants/splitConstants";

afterEach(() => vi.unstubAllGlobals());
it.each([false, true])(
  "dispatches actual N-up/poster forms and names outputs (poster=%s)",
  async (poster) => {
    vi.stubGlobal("__SOOTAN_RUNTIME__", {
      kind: "offline-desktop",
      advancedOperations: ["layout-nup", "layout-poster"],
    });
    vi.stubGlobal("crypto", {
      randomUUID: () => "5fd10d6d-8ad2-4537-9a85-793420b72227",
    });
    const fetch = vi.fn(async (_url: string, _options?: RequestInit) => ({
      ok: true,
      headers: new Headers({
        "Content-Type": poster ? "application/zip" : "application/pdf",
      }),
      blob: async () => new Blob(["fixture"]),
    }));
    vi.stubGlobal("fetch", fetch);
    const file = new File(["PDF"], "Pages with spaces.pdf");
    const form = poster
      ? buildSplitFormData(
          {
            ...splitDefaults,
            method: SPLIT_METHODS.BY_POSTER,
            pageSize: "Letter",
            xFactor: "3",
            yFactor: "2",
            rightToLeft: true,
          },
          file,
        )
      : buildPageLayoutFormData(
          {
            ...pageDefaults,
            mode: "CUSTOM",
            rows: 3,
            cols: 2,
            orientation: "LANDSCAPE",
            arrangement: "BY_COLUMNS",
            readingDirection: "RTL",
            innerMargin: 5,
          },
          file,
        );
    const result = await requestPdfProcessing(
      `/api/v1/general/${poster ? "split-for-poster-print" : "multi-page-layout"}`,
      form,
    );
    const request = JSON.parse(String(fetch.mock.calls[1][1]?.body));
    expect(request.operation).toBe(poster ? "layout-poster" : "layout-nup");
    expect(request.options).toMatchObject(
      poster
        ? {
            pageSize: "Letter",
            xFactor: "3",
            yFactor: "2",
            rightToLeft: "true",
          }
        : {
            mode: "CUSTOM",
            rows: "3",
            cols: "2",
            orientation: "LANDSCAPE",
            arrangement: "BY_COLUMNS",
            readingDirection: "RTL",
            innerMargin: "5",
          },
    );
    expect(result.headers["content-disposition"]).toContain(
      poster ? "converted.zip" : "converted.pdf",
    );
    expect(fetch.mock.calls.at(-1)?.[0]).toMatch(/\/job$/);
    expect(fetch.mock.calls.at(-1)?.[1]?.method).toBe("DELETE");
  },
);
