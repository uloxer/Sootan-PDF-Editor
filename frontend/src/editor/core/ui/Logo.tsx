import type { CSSProperties } from "react";
import { Icon } from "@editor/ui/Icon";
// The mark takes its two fills from the brand mark's path classes.
import "@editor/components/shared/BrandMark.css";
import "@editor/ui/Logo.css";

/** iconOnly = mark; textOnly = "Stirling" wordmark; iconAndText = both. */
export type LogoVariant = "iconOnly" | "iconAndText" | "textOnly";

interface LogoProps {
  variant?: LogoVariant;
  /** Layout for iconAndText: mark left of text, or stacked above it. */
  orientation?: "horizontal" | "vertical";
  /** Height of the mark (CSS length). */
  iconHeight?: string;
  /** Height of the wordmark (CSS length). */
  textHeight?: string;
  /** Gap between mark and wordmark. */
  gap?: string;
  className?: string;
  style?: CSSProperties;
  alt?: string;
}

/**
 * Shared brand lockup used across editor + processor. The mark is theme-
 * agnostic; the wordmark swaps light/dark via CSS so it tracks the active
 * colour scheme in both the editor (data-mantine-color-scheme) and the portal
 * (data-theme).
 */
export function Logo({
  variant = "iconAndText",
  orientation = "horizontal",
  iconHeight = "1.75rem",
  textHeight = "1rem",
  gap = "0.5rem",
  className,
  style,
  alt = "Sootan PDF Editor",
}: LogoProps) {
  const showIcon = variant === "iconOnly" || variant === "iconAndText";
  const showText = variant === "textOnly" || variant === "iconAndText";

  const cls = [
    "sui-logo",
    orientation === "vertical" ? "sui-logo--vertical" : "",
    className ?? "",
  ]
    .filter(Boolean)
    .join(" ");

  // Layout set inline so a consumer's className can't restack the lockup.
  const layoutStyle: CSSProperties = {
    display: orientation === "vertical" ? "flex" : "inline-flex",
    flexDirection: orientation === "vertical" ? "column" : "row",
    alignItems: "center",
    gap,
  };

  return (
    <span className={cls} style={{ ...layoutStyle, ...style }}>
      {showIcon && (
        <Icon
          name="sootan-mark"
          className="sui-logo__mark"
          size={iconHeight}
          title={showText ? undefined : alt}
        />
      )}
      {showText && (
        <span
          aria-label={alt}
          style={{ fontSize: textHeight, fontWeight: 600 }}
        >
          Sootan PDF Editor
        </span>
      )}
    </span>
  );
}
