import type { AppConfig } from "@editor/types/appConfig";

export interface RuntimeCapabilities {
  kind: "server" | "offline-desktop" | "extension";
  serverOperations: boolean;
  nativeFileDialogs: boolean;
  renderer: "wasm";
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
  return ["view-pdf", "sign", "form-fill"].includes(endpoint);
}
