import { PDFDocument, PDFDict, PDFName, degrees } from "@cantoo/pdf-lib";

export type CorePdfErrorCode =
  | "INVALID_INPUT"
  | "UNSUPPORTED_FEATURE"
  | "UNSUPPORTED_OPTION"
  | "CANCELLED";
export class CorePdfError extends Error {
  constructor(
    public readonly code: CorePdfErrorCode,
    message: string,
  ) {
    super(message);
    this.name = "CorePdfError";
  }
}
export interface CorePage {
  source: number;
  page: number;
  rotation?: number;
}
export type CorePdfOperation =
  | { kind: "merge"; files: File[] }
  | { kind: "rotate"; files: File[]; angle: number }
  | { kind: "select"; files: File[]; pages: number[] }
  | { kind: "split"; files: File[]; groups: number[][] }
  | { kind: "compose"; files: File[]; pages: CorePage[] };
export interface CorePdfContext {
  signal?: AbortSignal;
  progress?: (done: number, total: number) => void;
}
export interface CorePdfResult {
  files: File[];
  warnings: string[];
}

function check(context: CorePdfContext) {
  if (context.signal?.aborted)
    throw new CorePdfError("CANCELLED", "PDF operation cancelled.");
}
async function load(file: File) {
  try {
    return await PDFDocument.load(new Uint8Array(await file.arrayBuffer()), {
      updateMetadata: false,
    });
  } catch (cause) {
    const error = new CorePdfError(
      "UNSUPPORTED_FEATURE",
      "This PDF is encrypted, damaged, or unsupported by the local writer. The original has not been changed.",
    );
    error.cause = cause;
    throw error;
  }
}
function signed(doc: PDFDocument) {
  return doc.context
    .enumerateIndirectObjects()
    .some(
      ([, object]) =>
        object instanceof PDFDict &&
        object.get(PDFName.of("FT"))?.toString() === "/Sig",
    );
}
function assertWritable(doc: PDFDocument, importing: boolean) {
  if (signed(doc))
    throw new CorePdfError(
      "UNSUPPORTED_FEATURE",
      "Local page operations cannot preserve digital signatures. Use an unsigned copy.",
    );
  if (
    importing &&
    (doc.catalog.has(PDFName.of("AcroForm")) ||
      doc.catalog.has(PDFName.of("Outlines")))
  ) {
    throw new CorePdfError(
      "UNSUPPORTED_FEATURE",
      "This page composition would not preserve interactive forms or bookmarks. Use full-document rotation/reordering, or the advanced worker when available.",
    );
  }
}
function validatePages(pages: number[], count: number) {
  if (
    !pages.length ||
    pages.some((p) => !Number.isInteger(p) || p < 1 || p > count)
  )
    throw new CorePdfError(
      "INVALID_INPUT",
      "Select valid, nonempty page numbers within the document.",
    );
}
function permutation(pages: number[], count: number) {
  return pages.length === count && new Set(pages).size === count;
}
async function output(doc: PDFDocument, name: string) {
  const bytes = await doc.save({ updateFieldAppearances: false });
  return new File([new Uint8Array(bytes).buffer], name, {
    type: "application/pdf",
  });
}
function reorder(doc: PDFDocument, pages: number[]) {
  const originals = doc.getPages();
  originals.forEach((page) => page.node.normalize());
  for (let i = originals.length - 1; i >= 0; i--) doc.removePage(i);
  // removePage also unregisters the leaf; retain its reference for forms and destinations.
  originals.forEach((page) => doc.context.assign(page.ref, page.node));
  for (const page of pages) doc.addPage(originals[page - 1]);
}

/** Writes PDF objects without rasterization; unsupported preservation cases fail before output. Pages are one-based. */
export async function runCorePdfOperation(
  operation: CorePdfOperation,
  context: CorePdfContext = {},
): Promise<CorePdfResult> {
  check(context);
  if (!operation.files.length)
    throw new CorePdfError("INVALID_INPUT", "Select PDF files first.");
  const documents = await Promise.all(operation.files.map(load));
  const files: File[] = [];
  if (operation.kind === "rotate") {
    if (!Number.isFinite(operation.angle) || operation.angle % 90)
      throw new CorePdfError(
        "INVALID_INPUT",
        "Rotation must be a multiple of 90 degrees.",
      );
    for (let i = 0; i < documents.length; i++) {
      check(context);
      assertWritable(documents[i], false);
      for (const page of documents[i].getPages())
        page.setRotation(
          degrees(
            (((page.getRotation().angle + operation.angle) % 360) + 360) % 360,
          ),
        );
      files.push(await output(documents[i], operation.files[i].name));
      context.progress?.(i + 1, documents.length);
    }
  } else if (operation.kind === "merge") {
    documents.forEach((doc) => assertWritable(doc, true));
    const dest = documents[0];
    for (let i = 1; i < documents.length; i++) {
      check(context);
      const pages = await dest.copyPages(
        documents[i],
        documents[i].getPageIndices(),
      );
      pages.forEach((page) => dest.addPage(page));
      context.progress?.(i, documents.length);
    }
    files.push(await output(dest, "merged.pdf"));
  } else if (operation.kind === "compose") {
    if (!operation.pages.length)
      throw new CorePdfError("INVALID_INPUT", "A PDF must contain pages.");
    operation.pages.forEach((p) => {
      if (!documents[p.source])
        throw new CorePdfError("INVALID_INPUT", "Unknown page source.");
      validatePages([p.page], documents[p.source].getPageCount());
      if (
        p.rotation !== undefined &&
        (!Number.isFinite(p.rotation) || p.rotation % 90)
      )
        throw new CorePdfError("INVALID_INPUT", "Invalid page rotation.");
    });
    const same =
      documents.length === 1 &&
      operation.pages.every((p) => p.source === 0) &&
      permutation(
        operation.pages.map((p) => p.page),
        documents[0].getPageCount(),
      );
    documents.forEach((doc) => assertWritable(doc, !same));
    const dest = same ? documents[0] : await PDFDocument.create();
    if (same)
      reorder(
        dest,
        operation.pages.map((p) => p.page),
      );
    else
      for (const page of operation.pages) {
        check(context);
        const [copy] = await dest.copyPages(documents[page.source], [
          page.page - 1,
        ]);
        dest.addPage(copy);
      }
    operation.pages.forEach((p, i) => {
      if (p.rotation !== undefined)
        dest.getPage(i).setRotation(degrees(((p.rotation % 360) + 360) % 360));
    });
    files.push(await output(dest, "edited.pdf"));
  } else {
    if (documents.length !== 1)
      throw new CorePdfError("INVALID_INPUT", "This operation takes one PDF.");
    const source = documents[0],
      count = source.getPageCount();
    const groups =
      operation.kind === "split" ? operation.groups : [operation.pages];
    for (let i = 0; i < groups.length; i++) {
      check(context);
      const pages = groups[i];
      validatePages(pages, count);
      const complete = permutation(pages, count);
      assertWritable(source, !complete);
      const doc = await load(operation.files[0]);
      if (complete) reorder(doc, pages);
      else {
        const dest = await PDFDocument.create();
        const copies = await dest.copyPages(
          doc,
          pages.map((p) => p - 1),
        );
        copies.forEach((p) => dest.addPage(p));
        files.push(await output(dest, `pages_${i + 1}.pdf`));
        context.progress?.(i + 1, groups.length);
        continue;
      }
      files.push(await output(doc, `pages_${i + 1}.pdf`));
      context.progress?.(i + 1, groups.length);
    }
  }
  check(context);
  return { files, warnings: [] };
}

/** Strict CSV/ranges preserve requested order; no clamping, silent fallback, or empty exports. */
export function localPageSelection(
  expression: string,
  count: number,
): number[] {
  const text = expression.trim().toLowerCase();
  if (text === "all") return Array.from({ length: count }, (_, i) => i + 1);
  if (text === "odd" || text === "even")
    return Array.from({ length: count }, (_, i) => i + 1).filter(
      (p) => p % 2 === (text === "odd" ? 1 : 0),
    );
  const pages = text.split(",").flatMap((token) => {
    if (/^\d+$/.test(token)) return [Number(token)];
    const range = /^(\d+)-(\d+)$/.exec(token);
    if (!range)
      throw new CorePdfError(
        "INVALID_INPUT",
        "Use page numbers, inclusive ranges, all, odd, or even.",
      );
    const a = Number(range[1]),
      b = Number(range[2]);
    if (Math.abs(b - a) > count)
      throw new CorePdfError(
        "INVALID_INPUT",
        "Page range is outside the document.",
      );
    return Array.from(
      { length: Math.abs(b - a) + 1 },
      (_, i) => a + i * (b < a ? -1 : 1),
    );
  });
  validatePages(pages, count);
  return pages;
}
