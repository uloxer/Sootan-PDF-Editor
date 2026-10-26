import React from "react";
import { useAppConfig } from "@editor/contexts/AppConfigContext";
import { Stack, Text, Checkbox } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { OCRParameters } from "@editor/hooks/tools/ocr/useOCRParameters";

export interface AdvancedOCRParameters {
  advancedOptions: string[];
}

interface AdvancedOption {
  value: string;
  label: string;
  isSpecial: boolean;
}

interface AdvancedOCRSettingsProps {
  advancedOptions: string[];
  ocrRenderType?: string;
  onParameterChange: <K extends keyof OCRParameters>(
    key: K,
    value: OCRParameters[K],
  ) => void;
  disabled?: boolean;
}

const AdvancedOCRSettings: React.FC<AdvancedOCRSettingsProps> = ({
  advancedOptions,
  ocrRenderType = "auto",
  onParameterChange,
  disabled = false,
}) => {
  const { t } = useTranslation();
  const { config } = useAppConfig();

  // Define the advanced options available
  const advancedOptionsData: AdvancedOption[] = [
    {
      value: "compatibilityMode",
      label: t("ocr.settings.compatibilityMode.label", "Compatibility Mode"),
      isSpecial: true,
    },
    {
      value: "sidecar",
      label: t("ocr.settings.advancedOptions.sidecar", "Create a text file"),
      isSpecial: false,
    },
    {
      value: "deskew",
      label: t("ocr.settings.advancedOptions.deskew", "Deskew pages"),
      isSpecial: false,
    },
    {
      value: "clean",
      label: t("ocr.settings.advancedOptions.clean", "Clean input file"),
      isSpecial: false,
    },
    {
      value: "cleanFinal",
      label: t("ocr.settings.advancedOptions.cleanFinal", "Clean final output"),
      isSpecial: false,
    },
  ];

  // Handle individual checkbox changes
  const handleCheckboxChange = (optionValue: string, checked: boolean) => {
    const option = advancedOptionsData.find((opt) => opt.value === optionValue);

    if (option?.isSpecial) {
      // Handle special options (like compatibility mode) differently
      if (optionValue === "compatibilityMode") {
        onParameterChange("ocrRenderType", checked ? "sandwich" : "auto");
      }
    } else {
      // Handle regular advanced options
      const newOptions = checked
        ? [...advancedOptions, optionValue]
        : advancedOptions.filter((option) => option !== optionValue);
      onParameterChange("additionalOptions", newOptions);
    }
  };

  // Check if a special option is selected
  const isSpecialOptionSelected = (optionValue: string) => {
    if (optionValue === "compatibilityMode") {
      return ocrRenderType === "sandwich";
    }
    return false;
  };

  return (
    <Stack gap="md">
      <div>
        <Text size="sm" fw={500} mb="md">
          {t("ocr.settings.advancedOptions.label", "Processing Options")}
        </Text>

        <Stack gap="sm">
          {advancedOptionsData.map((option) => (
            <Checkbox
              key={option.value}
              checked={
                option.isSpecial
                  ? isSpecialOptionSelected(option.value)
                  : advancedOptions.includes(option.value)
              }
              onChange={(event) =>
                handleCheckboxChange(option.value, event.currentTarget.checked)
              }
              label={option.label}
              description={
                config?.ocrCleanAvailable === false &&
                ["clean", "cleanFinal"].includes(option.value)
                  ? "Requires optional unpaper (not included)"
                  : undefined
              }
              disabled={
                disabled ||
                (config?.ocrCleanAvailable === false &&
                  ["clean", "cleanFinal"].includes(option.value))
              }
              size="sm"
            />
          ))}
        </Stack>
      </div>
    </Stack>
  );
};

export default AdvancedOCRSettings;
