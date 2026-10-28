import { getRuntime } from "@editor/runtime/runtime";
import { supportsWorkerEndpoint } from "@editor/runtime/desktopWorker";
const BITMAPS = ["png", "jpg", "jpeg", "tiff", "tif", "bmp", "gif"];
/** Exact conversion pairs available from the explicitly bootstrapped local worker. */
export function supportsLocalConversion(from: string, to: string): boolean {
  if (getRuntime().serverOperations) return true;
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
