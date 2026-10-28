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
  const name =
    (
      {
        "/api/v1/convert/pdf/text": "pdf-to-text",
        "/api/v1/convert/pdf/word": "pdf-to-word",
        "/api/v1/convert/pdf/presentation": "pdf-to-presentation",
        "/api/v1/convert/pdf/xlsx": "pdf-to-xlsx",
        "/api/v1/convert/file/pdf": "file-to-pdf",
        "/api/v1/convert/pdf/img": "pdf-to-img",
        "/api/v1/convert/img/pdf": "img-to-pdf",
      } as Record<string, string>
    )[endpoint] ??
    endpoint.split("/").at(-1) ??
    "";
  if (!supportsWorkerEndpoint(name))
    throw new CorePdfError(
      "UNSUPPORTED_FEATURE",
      "This private worker capability is not connected.",
    );
  const portfolio =
    name === "create-portfolio" ||
    name === "img-to-pdf" ||
    name === "file-to-pdf";
  const selected = form
    .getAll(
      name === "img-to-pdf" || name === "file-to-pdf"
        ? "fileInput"
        : portfolio
          ? "files"
          : "attachments",
    )
    .filter((file): file is File => file instanceof File);
  const input = portfolio ? selected[0] : form.get("fileInput");
  if (
    !(input instanceof File) ||
    (!portfolio && form.getAll("fileInput").length !== 1)
  )
    throw new CorePdfError("INVALID_INPUT", "Select exactly one PDF.");
  const options: Record<
    string,
    | string
    | Record<string, string>
    | { index: number; name: string; contentType: string }[]
  > = {};
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
  if (DESKTOP_ENDPOINTS[name] === "attachments") {
    const actions: Record<string, string> = {
      "list-attachments": "list",
      "extract-attachments": "extract",
      "extract-single-attachment": "single",
      "add-attachments": "add",
      "rename-attachment": "rename",
      "delete-attachment": "delete",
      "batch-process-attachments": "batch",
    };
    options.action = actions[name];
    if (options.convertToPdfA3b === "true")
      throw new CorePdfError(
        "UNSUPPORTED_OPTION",
        "PDF/A-3b conversion is not connected to the standalone worker. Turn off PDF/A conversion to preserve ordinary attachments.",
      );
  }
  if (
    selected.length > 100 ||
    selected.some((file) => file.size === 0 || file.size > 50 * 1024 * 1024) ||
    selected.reduce((total, file) => total + file.size, 0) > 200 * 1024 * 1024
  )
    throw new CorePdfError(
      "INVALID_INPUT",
      "Select up to100 nonempty attachment files,50MiB each and200MiB total.",
    );
  if (selected.length)
    options.selectedFiles = selected.map((file, index) => ({
      index,
      name: file.name,
      contentType: file.type,
    }));
  if (["attachments", "portfolio-flatten"].includes(DESKTOP_ENDPOINTS[name])) {
    const access = getPdfAccess(input);
    if (access?.encrypted) options.sourcePassword = access.password;
  }
  if (["pdf-to-text", "pdf-to-img", "extract-images"].includes(name)) {
    const access = getPdfAccess(input);
    if (access?.encrypted) options.sourcePassword = access.password;
    options.filename = input.name.replace(/\.[^.]+$/, "");
  }
  if (name === "pdf-to-text") {
    delete options.filename;
    if (!["txt", "rtf"].includes(String(options.outputFormat)))
      throw new CorePdfError(
        "UNSUPPORTED_OPTION",
        "Choose TXT or RTF for this text conversion endpoint.",
      );
  }
  if (
    name === "pdf-to-img" &&
    !["png", "jpg", "jpeg", "tiff", "bmp", "gif"].includes(
      String(options.imageFormat),
    )
  )
    throw new CorePdfError(
      "UNSUPPORTED_OPTION",
      "Select PNG, JPEG, TIFF, BMP or GIF. WebP export is not connected.",
    );
  if (name === "img-to-pdf")
    for (const file of selected)
      if (
        !["png", "jpg", "jpeg", "tiff", "tif", "bmp", "gif"].includes(
          file.name.split(".").at(-1)?.toLowerCase() ?? "",
        ) &&
        ![
          "image/png",
          "image/jpeg",
          "image/tiff",
          "image/x-tiff",
          "image/bmp",
          "image/gif",
        ].includes(file.type)
      )
        throw new CorePdfError(
          "UNSUPPORTED_OPTION",
          "Select a local PNG, JPEG, TIFF, BMP or GIF bitmap.",
        );
  const operation =
    name === "pdf-to-text" &&
    (options.outputFormat === "rtf" ||
      options.useStirlingOfficeConvert === "true")
      ? "office-export"
      : DESKTOP_ENDPOINTS[name];
  if (operation === "office-export") {
    if (!getRuntime().advancedOperations?.includes("office-export"))
      throw new CorePdfError(
        "UNSUPPORTED_FEATURE",
        "The local Office converter is unavailable.",
      );
    if (name === "pdf-to-xlsx" && !options.outputFormat)
      options.outputFormat = "xlsx";
    if (
      ![
        "txt",
        "docx",
        "doc",
        "odt",
        "rtf",
        "pptx",
        "ppt",
        "odp",
        "xlsx",
        "ods",
      ].includes(String(options.outputFormat))
    )
      throw new CorePdfError(
        "UNSUPPORTED_OPTION",
        "Select an available local Office output format.",
      );
    if (options.useStirlingOfficeConvert === "false")
      throw new CorePdfError(
        "UNSUPPORTED_OPTION",
        "The legacy LibreOffice engine is not bundled. Use the in-process Office converter.",
      );
    const access = getPdfAccess(input);
    if (access?.encrypted) options.sourcePassword = access.password;
  }
  if (operation === "office-import") {
    if (
      selected.length !== 1 ||
      !["docx", "pptx", "xlsx", "ppt", "txt", "csv", "tsv"].includes(
        input.name.split(".").at(-1)?.toLowerCase() ?? "",
      )
    )
      throw new CorePdfError(
        "UNSUPPORTED_OPTION",
        "Select one DOCX, PPTX, XLSX, PPT, TXT, CSV or TSV document.",
      );
    if (options.useStirlingOfficeConvert === "false")
      throw new CorePdfError(
        "UNSUPPORTED_OPTION",
        "The legacy LibreOffice engine is not bundled. Use the in-process Office converter.",
      );
  }
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
    for (let index = 0; index < selected.length; index++) {
      const grant = await fetch(`${route}/attachment-${index}`, {
        method: "POST",
        body: selected[index],
        signal: controller.signal,
      });
      if (!grant.ok)
        throw new Error(
          "Unable to copy the selected attachment into its private grant.",
        );
    }
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
      body: JSON.stringify({ operation, options }),
      signal: controller.signal,
    });
    if (!response.ok) {
      const failure = await response.json().catch(() => null);
      if (failure?.code === "OFFICE_EXTERNAL_RESOURCE")
        throw new CorePdfError(
          "UNSUPPORTED_FEATURE",
          "This Office document contains external relationships. Embed linked resources or remove external links before converting; no remote content was fetched and the original is unchanged.",
        );
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
        ...([
          "pdf-to-word",
          "pdf-to-presentation",
          "pdf-to-xlsx",
          "file-to-pdf",
          "pdf-to-text",
          "pdf-to-img",
          "img-to-pdf",
        ].includes(name)
          ? {
              "content-disposition": `attachment; filename="converted.${response.headers.get("Content-Type")?.includes("zip") ? "zip" : operation === "office-export" ? String(options.outputFormat) : name === "file-to-pdf" ? "pdf" : name === "pdf-to-text" ? "txt" : name === "img-to-pdf" ? "pdf" : String(options.imageFormat)}"`,
            }
          : {}),
      },
    };
  } finally {
    config.signal?.removeEventListener?.("abort", cancel);
    config.cancelToken?.unsubscribe(cancel);
    await fetch(`${route}/job`, { method: "DELETE" }).catch(() => {});
  }
}
