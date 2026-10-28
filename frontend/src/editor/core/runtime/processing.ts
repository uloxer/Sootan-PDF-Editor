import type { AxiosRequestConfig } from "axios";
import apiClient from "@editor/services/apiClient";
import { getRuntime } from "@editor/runtime/runtime";
import {
  CorePdfError,
  localPageSelection,
  runCorePdfOperation,
} from "@editor/runtime/corePdfOperations";
import { PDFDocument } from "@cantoo/pdf-lib";
import JSZip from "jszip";
import {
  requestDesktopWorker,
  supportsWorkerEndpoint,
} from "@editor/runtime/desktopWorker";

/** Existing tool request shapes dispatch locally offline; the server transport remains unchanged. */
export async function requestPdfProcessing(
  endpoint: string,
  form: FormData,
  options: AxiosRequestConfig = {},
): Promise<{ data: Blob; status: number; headers: Record<string, string> }> {
  if (getRuntime().serverOperations)
    return apiClient.post(endpoint, form, options);
  if (supportsWorkerEndpoint(endpoint.split("/").at(-1) ?? ""))
    return requestDesktopWorker(endpoint, form, options);
  options.cancelToken?.throwIfRequested();
  const context = { signal: options.signal as AbortSignal | undefined };
  const files = form
    .getAll("fileInput")
    .filter((f): f is File => f instanceof File);
  const value = (key: string) => String(form.get(key) ?? "");
  const unsupported = (message: string): never => {
    throw new CorePdfError("UNSUPPORTED_OPTION", message);
  };
  let result;
  if (endpoint.endsWith("/merge-pdfs")) {
    if (value("generateToc") === "true" || value("removeCertSign") === "true")
      unsupported(
        "Local merge does not generate a table of contents or remove digital signatures.",
      );
    result = await runCorePdfOperation({ kind: "merge", files }, context);
  } else if (endpoint.endsWith("/rotate-pdf")) {
    result = await runCorePdfOperation(
      { kind: "rotate", files, angle: Number(value("angle")) },
      context,
    );
  } else {
    if (files.length !== 1)
      unsupported("Select exactly one PDF for this page operation.");
    let doc;
    try {
      doc = await PDFDocument.load(await files[0].arrayBuffer(), {
        updateMetadata: false,
      });
    } catch {
      unsupported(
        "Local page operations cannot rewrite an encrypted or damaged PDF.",
      );
    }
    if (!doc) throw new CorePdfError("INVALID_INPUT", "Select a readable PDF.");
    const count = doc.getPageCount(),
      all = Array.from({ length: count }, (_, i) => i + 1);
    if (endpoint.endsWith("/rearrange-pages")) {
      const mode = value("customMode");
      let pages: number[];
      if (!mode || mode === "DUPLICATE")
        pages = localPageSelection(value("pageNumbers"), count);
      else if (mode === "REVERSE_ORDER") pages = all.reverse();
      else if (mode === "REMOVE_FIRST") pages = all.slice(1);
      else if (mode === "REMOVE_LAST") pages = all.slice(0, -1);
      else if (mode === "REMOVE_FIRST_AND_LAST") pages = all.slice(1, -1);
      else
        unsupported(
          "This rearrangement mode is not available in the local core.",
        );
      result = await runCorePdfOperation(
        { kind: "select", files, pages: pages! },
        context,
      );
    } else if (endpoint.endsWith("/remove-pages")) {
      const removed = new Set(localPageSelection(value("pageNumbers"), count));
      result = await runCorePdfOperation(
        { kind: "select", files, pages: all.filter((p) => !removed.has(p)) },
        context,
      );
    } else if (endpoint.endsWith("/split-pages")) {
      const cuts = [
        ...new Set(localPageSelection(value("pageNumbers"), count)),
      ].sort((a, b) => a - b);
      const groups: number[][] = [];
      let previous = 0;
      for (const cut of [...cuts.filter((c) => c < count), count]) {
        groups.push(all.slice(previous, cut));
        previous = cut;
      }
      result = await runCorePdfOperation(
        { kind: "split", files, groups },
        context,
      );
    } else
      unsupported(
        "This operation requires the advanced desktop worker and is unavailable in the extension.",
      );
  }
  options.cancelToken?.throwIfRequested();
  if (!result)
    throw new CorePdfError(
      "UNSUPPORTED_FEATURE",
      "This local operation is unavailable.",
    );
  let data: Blob;
  if (endpoint.endsWith("/split-pages") || result.files.length > 1) {
    const zip = new JSZip();
    for (const file of result.files)
      zip.file(file.name, await file.arrayBuffer());
    data = await zip.generateAsync({ type: "blob" });
  } else data = result.files[0];
  return { data, status: 200, headers: { "content-type": data.type } };
}
