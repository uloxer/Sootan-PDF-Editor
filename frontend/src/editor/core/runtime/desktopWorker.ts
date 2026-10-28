import { getPdfAccess } from "@editor/services/pdfAccessService";
import type { AxiosRequestConfig } from "axios";
import { getRuntime, WORKER_ENDPOINTS } from "@editor/runtime/runtime";
import { CorePdfError } from "@editor/runtime/corePdfOperations";

export const DESKTOP_ENDPOINTS = WORKER_ENDPOINTS;

/** Only an explicitly bootstrapped native capability can dispatch through the virtual-origin bridge. */
export function supportsWorkerEndpoint(endpoint: string): boolean {
  const runtime = getRuntime();
  return (
    runtime.kind === "offline-desktop" &&
    runtime.advancedOperations?.includes(DESKTOP_ENDPOINTS[endpoint]) === true
  );
}

/** Streams selected files to host-owned grants; DELETE cancels the process tree and releases every job file. */
export async function requestDesktopWorker(
  endpoint: string,
  form: FormData,
  config: AxiosRequestConfig = {},
) {
  const name = endpoint.split("/").at(-1) ?? "";
  if (!supportsWorkerEndpoint(name))
    throw new CorePdfError(
      "UNSUPPORTED_FEATURE",
      "This private worker capability is not connected.",
    );
  const input = form.get("fileInput");
  if (!(input instanceof File) || form.getAll("fileInput").length !== 1)
    throw new CorePdfError("INVALID_INPUT", "Select exactly one PDF.");
  const options: Record<string, string | Record<string, string>> = {};
  const metadata: Record<string, string> = {};
  for (const [key, value] of form) {
    if (typeof value !== "string") continue;
    const custom =
      name === "update-metadata" && /^allRequestParams\[([^\]]+)\]$/.exec(key);
    if (custom) metadata[custom[1]] = value;
    else options[key] = value;
  }
  if (Object.keys(metadata).length) options.allRequestParams = metadata;
  if (form.getAll("languages").length > 1)
    options.languages = form.getAll("languages").join("+");
  const trust = name === "validate-signature" ? form.get("certFile") : null;
  if (trust instanceof File && trust.size > 1024 * 1024)
    throw new CorePdfError(
      "INVALID_INPUT",
      "The selected trust certificate exceeds one MiB.",
    );
  if (
    name === "cert-sign" &&
    !["PKCS12", "PFX"].includes(String(options.certType))
  )
    throw new CorePdfError(
      "UNSUPPORTED_OPTION",
      "Select a local PKCS12/PFX signing key. PEM/JKS and device signing are not connected; remote signing is disabled.",
    );
  const watermark =
    name === "add-watermark"
      ? form.get("watermarkImage")
      : name === "add-stamp"
        ? form.get("stampImage")
        : null;
  if (
    watermark instanceof File &&
    (watermark.size > 32 * 1024 * 1024 ||
      ![
        "image/png",
        "image/jpeg",
        "image/gif",
        "image/bmp",
        "image/x-ms-bmp",
        "image/tiff",
      ].includes(watermark.type))
  )
    throw new CorePdfError(
      "INVALID_INPUT",
      "Select a PNG/JPEG/GIF/BMP/TIFF bitmap up to32MiB.",
    );
  if (name === "add-page-numbers") options.filename = input.name;
  if (DESKTOP_ENDPOINTS[name] === "document-inspect") {
    options.report =
      name === "get-info-on-pdf"
        ? "all-info"
        : name === "show-javascript"
          ? "javascript"
          : name;
    const access = getPdfAccess(input);
    if (access?.encrypted) options.sourcePassword = access.password;
  }
  if (DESKTOP_ENDPOINTS[name] === "document-inspect" || name === "add-stamp")
    options.filename = input.name;
  const id = crypto.randomUUID();
  const route = `/__sootan_worker/${id}`;
  const controller = new AbortController();
  const cancel = () => controller.abort();
  config.signal?.addEventListener?.("abort", cancel);
  config.cancelToken?.subscribe(cancel);
  try {
    if (config.signal?.aborted) controller.abort();
    config.cancelToken?.throwIfRequested();
    const upload = await fetch(`${route}/input`, {
      method: "POST",
      body: input,
      signal: controller.signal,
    });
    if (!upload.ok)
      throw new Error("Unable to copy the selected PDF into a private job.");
    const certificate = form.get("p12File");
    if (certificate instanceof File) {
      const uploaded = await fetch(`${route}/certificate`, {
        method: "POST",
        body: certificate,
        signal: controller.signal,
      });
      if (!uploaded.ok)
        throw new Error(
          "Unable to copy the selected certificate into a private job.",
        );
    }
    if (trust instanceof File) {
      const uploaded = await fetch(`${route}/trust`, {
        method: "POST",
        body: trust,
        signal: controller.signal,
      });
      if (!uploaded.ok)
        throw new Error("Unable to copy the selected local trust certificate.");
    }
    if (watermark instanceof File) {
      const uploaded = await fetch(`${route}/image`, {
        method: "POST",
        body: watermark,
        signal: controller.signal,
      });
      if (!uploaded.ok)
        throw new Error(
          "Unable to copy the selected bitmap into its private grant.",
        );
    }
    const response = await fetch(`${route}/run`, {
      method: "POST",
      body: JSON.stringify({ operation: DESKTOP_ENDPOINTS[name], options }),
      signal: controller.signal,
    });
    if (!response.ok) {
      const failure = await response.json().catch(() => null);
      if (failure?.code === "INSPECTION_ACCESS_DENIED")
        throw new CorePdfError(
          "UNSUPPORTED_FEATURE",
          "This report exports document content. Authenticate with a credential that permits extraction; basic and security inspection remain available.",
        );
      if (failure?.code === "UNSUPPORTED_DOCUMENT_STRUCTURE")
        throw new CorePdfError(
          "UNSUPPORTED_FEATURE",
          "This page-format operation cannot yet preserve this document's forms, bookmarks or annotations. The original has not been changed.",
        );
      throw new CorePdfError(
        "UNSUPPORTED_OPTION",
        "The private worker could not complete this job. Check document permissions, selected options and helper availability.",
      );
    }
    return {
      data: await response.blob(),
      status: 200,
      headers: {
        "content-type":
          response.headers.get("Content-Type") ?? "application/octet-stream",
      },
    };
  } finally {
    config.signal?.removeEventListener?.("abort", cancel);
    config.cancelToken?.unsubscribe(cancel);
    await fetch(`${route}/job`, { method: "DELETE" }).catch(() => {});
  }
}
