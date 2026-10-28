import { buildChangeMetadataFormData } from "@editor/hooks/tools/changeMetadata/useChangeMetadataOperation";
import { defaultParameters } from "@editor/hooks/tools/changeMetadata/useChangeMetadataParameters";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  requestDesktopWorker,
  supportsWorkerEndpoint,
} from "@editor/runtime/desktopWorker";

afterEach(() => vi.unstubAllGlobals());
function request() {
  const form = new FormData();
  form.set(
    "fileInput",
    new File(["synthetic selected PDF"], "Selected path with spaces.pdf", {
      type: "application/pdf",
    }),
  );
  form.set("deskew", "true");
  return form;
}
function native() {
  vi.stubGlobal("crypto", {
    randomUUID: () => "5fd10d6d-8ad2-4537-9a85-793420b72227",
  });
  vi.stubGlobal("__SOOTAN_RUNTIME__", {
    kind: "offline-desktop",
    advancedOperations: ["ocr", "cert-sign-pkcs12", "signature-integrity"],
  });
}

describe("private desktop worker boundary", () => {
  it("never exposes native operations to extension mode even with a forged capability list", async () => {
    vi.stubGlobal("__SOOTAN_RUNTIME__", {
      kind: "extension",
      advancedOperations: ["ocr"],
    });
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    expect(supportsWorkerEndpoint("ocr-pdf")).toBe(false);
    await expect(
      requestDesktopWorker("/api/v1/misc/ocr-pdf", request()),
    ).rejects.toMatchObject({ code: "UNSUPPORTED_FEATURE" });
    expect(fetch).not.toHaveBeenCalled();
  });
  it("streams the selected File and releases its private grant after output", async () => {
    native();
    const output = new Blob(["result"], { type: "application/pdf" });
    const fetch = vi.fn(async (url: string, _options?: RequestInit) => ({
      ok: true,
      headers: new Headers({ "Content-Type": "application/pdf" }),
      blob: async () => output,
      url,
    }));
    vi.stubGlobal("fetch", fetch);
    const form = request();
    expect(
      (await requestDesktopWorker("/api/v1/misc/ocr-pdf", form)).data,
    ).toBe(output);
    expect(fetch.mock.calls[0][1]).toMatchObject({
      body: form.get("fileInput"),
      method: "POST",
    });
    expect(fetch.mock.lastCall?.[0]).toMatch(
      /^\/__sootan_worker\/[\w-]+\/job$/,
    );
    expect(fetch.mock.lastCall?.[1]).toEqual({ method: "DELETE" });
  });
  it("cleans up after a failed worker without retaining password-bearing request metadata in the error", async () => {
    native();
    const fetch = vi.fn(async (url: string, _options?: RequestInit) => ({
      ok: !url.endsWith("/run"),
    }));
    vi.stubGlobal("fetch", fetch);
    await expect(
      requestDesktopWorker("/api/v1/misc/ocr-pdf", request()),
    ).rejects.toMatchObject({ code: "UNSUPPORTED_OPTION" });
    expect(fetch.mock.lastCall?.[1]).toEqual({ method: "DELETE" });
  });
  it("rejects unconnected signing modes and oversized trust grants before file copying", async () => {
    native();
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    const form = request();
    form.set("certType", "SERVER");
    await expect(
      requestDesktopWorker("/api/v1/security/cert-sign", form),
    ).rejects.toMatchObject({ code: "UNSUPPORTED_OPTION" });
    form.set(
      "certFile",
      new File([new Uint8Array(1024 * 1024 + 1)], "selected.pem"),
    );
    await expect(
      requestDesktopWorker("/api/v1/security/validate-signature", form),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(fetch).not.toHaveBeenCalled();
  });
  it("streams a selected local trust anchor in its own bounded grant", async () => {
    native();
    const fetch = vi.fn(async (_url: string, _options?: RequestInit) => ({
      ok: true,
      headers: new Headers(),
      blob: async () => new Blob(["[]"]),
    }));
    vi.stubGlobal("fetch", fetch);
    const form = request();
    form.delete("deskew");
    const certificate = new File(
      ["synthetic certificate"],
      "Local root with spaces.cer",
    );
    form.set("certFile", certificate);
    await requestDesktopWorker("/api/v1/security/validate-signature", form);
    expect(fetch.mock.calls[1][0]).toMatch(/\/trust$/);
    expect(fetch.mock.calls[1][1]?.body).toBe(certificate);
    expect(JSON.parse(String(fetch.mock.calls[2][1]?.body))).toEqual({
      operation: "signature-integrity",
      options: {},
    });
  });
  it("dispatches the real metadata hook's paired custom map without losing scalar options", async () => {
    native();
    vi.stubGlobal("__SOOTAN_RUNTIME__", {
      kind: "offline-desktop",
      advancedOperations: ["metadata-update", "forms-unlock", "flatten"],
    });
    const fetch = vi.fn(async (_url: string, _options?: RequestInit) => ({
      ok: true,
      headers: new Headers(),
      blob: async () => new Blob(["pdf"]),
    }));
    vi.stubGlobal("fetch", fetch);
    const form = buildChangeMetadataFormData(
      {
        ...defaultParameters,
        title: "Swona",
        customMetadata: [{ id: "test", key: "Island", value: "Swona" }],
      },
      request().get("fileInput") as File,
    );
    await requestDesktopWorker("/api/v1/misc/update-metadata", form);
    const message = JSON.parse(String(fetch.mock.calls[1][1]?.body));
    expect(message.operation).toBe("metadata-update");
    expect(message.options).toMatchObject({
      title: "Swona",
      allRequestParams: { customKey1: "Island", customValue1: "Swona" },
    });
    expect(supportsWorkerEndpoint("flatten")).toBe(true);
    expect(supportsWorkerEndpoint("unlock-pdf-forms")).toBe(true);
    expect(supportsWorkerEndpoint("add-watermark")).toBe(false);
  });
  it("cancels an active response and still releases its native job", async () => {
    native();
    const controller = new AbortController();
    const fetch = vi.fn(async (url: string, options?: RequestInit) => {
      if (!url.endsWith("/run")) return { ok: true };
      return new Promise<never>((_resolve, reject) =>
        options?.signal?.addEventListener("abort", () =>
          reject(new DOMException("Cancelled", "AbortError")),
        ),
      );
    });
    vi.stubGlobal("fetch", fetch);
    const result = requestDesktopWorker("/api/v1/misc/ocr-pdf", request(), {
      signal: controller.signal,
    });
    const failed = expect(result).rejects.toMatchObject({ name: "AbortError" });
    await vi.waitFor(() =>
      expect(fetch.mock.calls.some(([url]) => url.endsWith("/run"))).toBe(true),
    );
    controller.abort();
    await failed;
    expect(fetch.mock.lastCall?.[1]).toEqual({ method: "DELETE" });
  });
});
