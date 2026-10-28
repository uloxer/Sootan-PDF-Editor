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
    const response = await fetch(`${route}/run`, {
      method: "POST",
      body: JSON.stringify({ operation: DESKTOP_ENDPOINTS[name], options }),
      signal: controller.signal,
    });
    if (!response.ok)
      throw new CorePdfError(
        "UNSUPPORTED_OPTION",
        "The private worker could not complete this job. Check document permissions, selected options and helper availability.",
      );
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
