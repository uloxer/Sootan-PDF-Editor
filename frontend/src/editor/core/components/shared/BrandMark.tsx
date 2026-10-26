import { Icon } from "@editor/ui/Icon";
import "@editor/components/shared/BrandMark.css";

interface BrandMarkProps {
  height?: string;
  className?: string;
}

export function BrandMark({ height = "1.6rem", className }: BrandMarkProps) {
  return (
    <Icon
      name="sootan-mark"
      size={height}
      className={`sui-brandmark${className ? ` ${className}` : ""}`}
      title="Sootan PDF Editor"
    />
  );
}
