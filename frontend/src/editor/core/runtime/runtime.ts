import type { AppConfig } from "@editor/types/appConfig";

export interface RuntimeCapabilities {
  kind: "server" | "offline-desktop" | "extension";
  serverOperations: boolean;
  nativeFileDialogs: boolean;
  renderer: "wasm";
  nativeWindowControls?: boolean;
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
  return CORE_ENDPOINTS.includes(endpoint);
}

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
