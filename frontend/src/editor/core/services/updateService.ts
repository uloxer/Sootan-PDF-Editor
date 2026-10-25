export interface UpdateSummary {
  latest_version: string | null;
  latest_stable_version?: string | null;
  max_priority: "urgent" | "normal" | "minor" | "low";
  recommended_action?: string;
  any_breaking: boolean;
  migration_guides?: Array<{
    version: string;
    notes: string;
    url: string;
  }>;
}

export interface VersionUpdate {
  version: string;
  priority: "urgent" | "normal" | "minor" | "low";
  announcement: {
    title: string;
    message: string;
  };
  compatibility: {
    breaking_changes: boolean;
    breaking_description?: string;
    migration_guide_url?: string;
  };
}

export interface FullUpdateInfo {
  latest_version: string;
  latest_stable_version?: string;
  new_versions: VersionUpdate[];
}

export interface MachineInfo {
  machineType: string;
  activeSecurity: boolean;
  licenseType: string;
}

export class UpdateService {
  getDownloadUrl(_machine: MachineInfo): string | null {
    return null;
  }
  compareVersions(a: string, b: string): number {
    return a.localeCompare(b, undefined, { numeric: true });
  }
  async getUpdateSummary(
    _version: string,
    _machine: MachineInfo,
  ): Promise<UpdateSummary | null> {
    return null;
  }
  async getFullUpdateInfo(
    _version: string,
    _machine: MachineInfo,
  ): Promise<FullUpdateInfo | null> {
    return null;
  }
  async getLatestVersionFromGitHub(): Promise<string | null> {
    return null;
  }
}
export const updateService = new UpdateService();
