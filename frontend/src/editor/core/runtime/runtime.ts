import type { AppConfig } from "@editor/types/appConfig";

export interface RuntimeCapabilities {
  kind: "server" | "offline-desktop" | "extension";
  serverOperations: boolean;
  nativeFileDialogs: boolean;
  renderer: "wasm";
  nativeWindowControls?: boolean;
  advancedOperations?: string[];
}

const SERVER: RuntimeCapabilities = {
  kind: "server",
  serverOperations: true,
  nativeFileDialogs: false,
  renderer: "wasm",
};

/** Host bootstrap is explicit; a failed server connection never changes runtime mode. */
export function getRuntime(): RuntimeCapabilities {
  const seed = (
    window as unknown as { __SOOTAN_RUNTIME__?: RuntimeCapabilities }
  ).__SOOTAN_RUNTIME__;
  return seed?.kind === "offline-desktop" || seed?.kind === "extension"
    ? { ...seed, serverOperations: false, renderer: "wasm" }
    : seed?.kind === "server"
      ? {
          ...SERVER,
          nativeFileDialogs: seed.nativeFileDialogs === true,
          nativeWindowControls: seed.nativeWindowControls === true,
        }
      : SERVER;
}

export const OFFLINE_APP_CONFIG: AppConfig = {
  ocrCleanAvailable: false,
  ocrRemoveImagesAvailable: false,
  enableLogin: false,
  appNameNavbar: "Sootan PDF Editor",
  enableAnalytics: false,
  enableMobileScanner: false,
  enableMobileSignature: false,
  enableEasterEggs: false,
  storageEnabled: false,
  accountLinkAvailable: false,
  premiumEnabled: false,
  defaultStartupView: "tools",
  defaultToolPanelMode: "sidebar",
  honorOwnerPasswords: true,
  dependenciesReady: true,
};

export const OFFLINE_OPERATION_REASON =
  "This operation requires a processing adapter that is not available in this offline runtime.";

export function supportsLocalEndpoint(endpoint: string): boolean {
  return (
    CORE_ENDPOINTS.includes(endpoint) ||
    (getRuntime().kind === "offline-desktop" &&
      getRuntime().advancedOperations?.includes(WORKER_ENDPOINTS[endpoint]) ===
        true)
  );
}

export const WORKER_ENDPOINTS: Record<string, string> = {
  "pdf-to-text": "pdf-text-export",
  "pdf-to-img": "pdf-image-export",
  "img-to-pdf": "image-pdf-create",
  "extract-images": "image-extract",
  ...Object.fromEntries(
    [
      "list-attachments",
      "extract-attachments",
      "extract-single-attachment",
      "add-attachments",
      "rename-attachment",
      "delete-attachment",
      "batch-process-attachments",
    ].map((endpoint) => [endpoint, "attachments"]),
  ),
  "create-portfolio": "portfolio-create",
  "flatten-portfolio": "portfolio-flatten",
  "add-stamp": "stamp",
  ...Object.fromEntries(
    [
      "page-count",
      "basic-info",
      "document-properties",
      "page-dimensions",
      "form-fields",
      "annotation-info",
      "font-info",
      "security-info",
      "get-info-on-pdf",
      "show-javascript",
      "verify-pdf",
    ].map((endpoint) => [endpoint, "document-inspect"]),
  ),
  crop: "page-crop",
  "scale-pages": "page-scale",
  "add-page-numbers": "page-numbers",
  "add-watermark": "watermark",
  "update-metadata": "metadata-update",
  "unlock-pdf-forms": "forms-unlock",
  flatten: "flatten",
  "sanitize-pdf": "sanitize",
  "add-password": "password-protect",
  "remove-password": "decrypt",
  "ocr-pdf": "ocr",
  "cert-sign": "cert-sign-pkcs12",
  "validate-signature": "signature-integrity",
  "remove-cert-sign": "signature-remove",
};

export const CORE_ENDPOINTS: string[] = [
  "view-pdf",
  "sign",
  "form-fill",
  "multi-tool",
  "merge-pdfs",
  "split-pages",
  "rearrange-pages",
  "remove-pages",
  "rotate-pdf",
];
export const CORE_TOOL_IDS = new Set([
  "read",
  "annotate",
  "formFill",
  "sign",
  "merge",
  "split",
  "extractPages",
  "reorganizePages",
  "removePages",
  "rotate",
  "multiTool",
]);
