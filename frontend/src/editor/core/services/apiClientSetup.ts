import type { AxiosInstance } from "axios";

export function setupApiInterceptors(_client: AxiosInstance): void {}

/** Auth headers for raw fetch() calls — empty in core; proprietary/SaaS override. */
export async function getAuthHeaders(): Promise<Record<string, string>> {
  return {};
}
