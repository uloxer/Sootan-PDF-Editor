import type { PdfEngine } from "@embedpdf/models";

/** Tile widgets have one owner after access resolves; full-page print/export keeps native appearances. */
export function withViewerFormRendering(
  engine: PdfEngine<Blob>,
  interactiveForms: boolean,
): PdfEngine<Blob> {
  const render: PdfEngine<Blob>["renderPageRect"] = (
    document,
    page,
    rect,
    options,
  ) =>
    engine.renderPageRect(document, page, rect, {
      ...options,
      withForms: !interactiveForms || document.isEncrypted !== false,
    });
  return new Proxy(engine, {
    get(target, property) {
      if (property === "renderPageRect") return render;
      const value = Reflect.get(target, property, target);
      return typeof value === "function" ? value.bind(target) : value;
    },
  });
}
