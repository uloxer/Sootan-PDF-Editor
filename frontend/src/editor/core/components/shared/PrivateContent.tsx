import React from "react";

interface PrivateContentProps extends React.HTMLAttributes<HTMLSpanElement> {
  children: React.ReactNode;
}

export const PrivateContent: React.FC<PrivateContentProps> = ({
  children,
  className = "",
  style,
  ...props
}) => {
  const combinedClassName = className;
  const combinedStyle = { display: "contents" as const, ...style };

  return (
    <span className={combinedClassName} style={combinedStyle} {...props}>
      {children}
    </span>
  );
};
