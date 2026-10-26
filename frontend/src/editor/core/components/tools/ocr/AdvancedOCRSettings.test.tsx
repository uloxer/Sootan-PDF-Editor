import { render, screen } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { describe, expect, it, vi } from "vitest";
import AdvancedOCRSettings from "@editor/components/tools/ocr/AdvancedOCRSettings";

vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: (_key: string, fallback: string) => fallback }),
}));
vi.mock("@editor/contexts/AppConfigContext", () => ({
  useAppConfig: () => ({ config: { ocrCleanAvailable: false } }),
}));

describe("Windows OCR capabilities", () => {
  it("disables unavailable cleaning while leaving supported OCR options available", () => {
    render(
      <MantineProvider>
        <AdvancedOCRSettings advancedOptions={[]} onParameterChange={vi.fn()} />
      </MantineProvider>,
    );
    expect(
      screen.getByRole("checkbox", { name: "Clean input file" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("checkbox", { name: "Clean final output" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("checkbox", { name: "Deskew pages" }),
    ).toBeEnabled();
    expect(
      screen.getByRole("checkbox", { name: "Create a text file" }),
    ).toBeEnabled();
  });
});
