import { describe, expect, it, vi } from "vitest";
import type {
  PdfDocumentObject,
  PdfEngine,
  PdfPageObject,
} from "@embedpdf/models";
import { withViewerFormRendering } from "@editor/components/viewer/viewerFormRendering";

describe("viewer widget rendering ownership", () => {
  it("uses verified access per tile across initial load, edits and zoom without changing print", () => {
    const tile = vi.fn(),
      print = vi.fn();
    const engine = withViewerFormRendering(
      { renderPageRect: tile, renderPage: print } as unknown as PdfEngine<Blob>,
      true,
    );
    const page = {} as PdfPageObject;
    const rect = { origin: { x: 0, y: 0 }, size: { width: 100, height: 100 } };
    for (const scaleFactor of [1, 2, 0.5]) {
      engine.renderPageRect(
        { isEncrypted: false } as PdfDocumentObject,
        page,
        rect,
        { scaleFactor },
      );
      expect(tile.mock.lastCall?.[3]).toMatchObject({
        withForms: false,
        scaleFactor,
      });
    }
    engine.renderPageRect(
      { isEncrypted: true } as PdfDocumentObject,
      page,
      rect,
    );
    expect(tile.mock.lastCall?.[3]).toMatchObject({ withForms: true });
    engine.renderPageRect(
      { isEncrypted: false } as PdfDocumentObject,
      page,
      rect,
      { withForms: true },
    );
    expect(tile.mock.lastCall?.[3]).toMatchObject({ withForms: false });
    engine.renderPage({ isEncrypted: false } as PdfDocumentObject, page, {
      withForms: true,
    });
    expect(print.mock.lastCall?.[2]).toEqual({ withForms: true });
  });

  it("retains native appearances when HTML forms are disabled", () => {
    const tile = vi.fn();
    const engine = withViewerFormRendering(
      { renderPageRect: tile } as unknown as PdfEngine<Blob>,
      false,
    );
    engine.renderPageRect(
      { isEncrypted: false } as PdfDocumentObject,
      {} as PdfPageObject,
      { origin: { x: 0, y: 0 }, size: { width: 10, height: 10 } },
    );
    expect(tile.mock.lastCall?.[3]).toEqual({ withForms: true });
  });
});
