import { getRuntime, CORE_TOOL_IDS } from "@editor/runtime/runtime";
import { useMemo } from "react";

import type { ToolId } from "@editor/types/toolId";
import type { ToolRegistry } from "@editor/data/toolsTaxonomy";
import ToolRegistryContext, {
  ToolRegistryCatalog,
} from "@editor/contexts/ToolRegistryContext";
import { useTranslatedToolCatalog } from "@editor/data/useTranslatedToolRegistry";

interface ToolRegistryProviderProps {
  children: React.ReactNode;
}

export const ToolRegistryProvider: React.FC<ToolRegistryProviderProps> = ({
  children,
}) => {
  const catalog = useTranslatedToolCatalog();

  const contextValue = useMemo<ToolRegistryCatalog>(() => {
    const keep = <T extends Record<string, unknown>>(tools: T): T =>
      Object.fromEntries(
        Object.entries(tools).filter(([id]) => CORE_TOOL_IDS.has(id)),
      ) as T;
    const filtered =
      getRuntime().kind === "extension"
        ? {
            ...catalog,
            regularTools: keep(catalog.regularTools),
            superTools: keep(catalog.superTools),
            linkTools: keep(catalog.linkTools),
          }
        : catalog;
    const { regularTools, superTools, linkTools } = filtered;
    const allTools: ToolRegistry = {
      ...regularTools,
      ...superTools,
      ...linkTools,
    };

    const getToolById = (toolId: ToolId | null) => {
      if (!toolId) {
        return null;
      }
      return allTools[toolId] ?? null;
    };

    return {
      regularTools,
      superTools,
      linkTools,
      allTools,
      getToolById,
    };
  }, [catalog]);

  return (
    <ToolRegistryContext.Provider value={contextValue}>
      {children}
    </ToolRegistryContext.Provider>
  );
};
