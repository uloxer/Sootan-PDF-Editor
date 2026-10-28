import { afterEach, describe, expect, it, vi } from "vitest";
import { getRuntime, OFFLINE_APP_CONFIG } from "@editor/runtime/runtime";
import {
  fetchAppConfig,
  fetchEndpointsAvailability,
  fetchFooterInfo,
} from "@editor/api/config";
import apiClient from "@editor/services/apiClient";
import * as httpErrors from "@editor/services/httpErrorHandler";

afterEach(() => vi.unstubAllGlobals());

describe("runtime boot boundary", () => {
  it("keeps the server runtime explicit by default", () => {
    expect(getRuntime().serverOperations).toBe(true);
  });

  it("boots the same offline configuration without API requests", async () => {
    vi.stubGlobal("__SOOTAN_RUNTIME__", { kind: "offline-desktop" });
    const get = vi.spyOn(apiClient, "get");
    expect(await fetchAppConfig()).toEqual(OFFLINE_APP_CONFIG);
    expect(await fetchEndpointsAvailability()).toEqual({});
    expect(await fetchFooterInfo()).toEqual({});
    expect(get).not.toHaveBeenCalled();
    get.mockRestore();
  });

  it("cannot enable server operations from an offline seed", () => {
    vi.stubGlobal("__SOOTAN_RUNTIME__", {
      kind: "extension",
      serverOperations: true,
    });
    expect(getRuntime().serverOperations).toBe(false);
  });
  it("rejects offline API attempts without a misleading network-error toast", async () => {
    vi.stubGlobal("__SOOTAN_RUNTIME__", { kind: "offline-desktop" });
    const handler = vi.spyOn(httpErrors, "handleHttpError");
    await expect(
      apiClient.get("/api/v1/unused-offline-resource"),
    ).rejects.toMatchObject({ code: "ERR_CANCELED" });
    expect(handler).not.toHaveBeenCalled();
    handler.mockRestore();
  });
});
