import { getRuntime } from "@editor/runtime/runtime";
import { supportsWorkerEndpoint } from "@editor/runtime/desktopWorker";
const BITMAPS = ["png", "jpg", "jpeg", "tiff", "tif", "bmp", "gif"];
/** Exact conversion pairs available from the explicitly bootstrapped local worker. */
export function supportsLocalConversion(from: string, to: string): boolean {
  if (getRuntime().serverOperations) return true;
  if (from === "pdf" && to === "json")
    return supportsWorkerEndpoint("pdf-to-text-editor");
  if (from === "json" && to === "pdf")
    return supportsWorkerEndpoint("text-editor-to-pdf");
  if (from === "svg" && to === "pdf")
    return supportsWorkerEndpoint("svg-to-pdf");
  if (from === "pdf" && to === "csv")
    return supportsWorkerEndpoint("pdf-to-csv");
  if (
    from === "pdf" &&
    ["docx", "doc", "odt", "rtf", "pptx", "ppt", "odp", "xlsx", "ods"].includes(
      to,
    )
  )
    return getRuntime().advancedOperations?.includes("office-export") === true;
  if (
    ["docx", "pptx", "xlsx", "ppt", "txt", "csv", "tsv"].includes(from) &&
    to === "pdf"
  )
    return supportsWorkerEndpoint("file-to-pdf");
  if (from === "pdf" && to === "txt")
    return supportsWorkerEndpoint("pdf-to-text");
  if (from === "pdf" && BITMAPS.includes(to))
    return supportsWorkerEndpoint("pdf-to-img");
  return (
    (from === "image" || BITMAPS.includes(from)) &&
    to === "pdf" &&
    supportsWorkerEndpoint("img-to-pdf")
  );
}
