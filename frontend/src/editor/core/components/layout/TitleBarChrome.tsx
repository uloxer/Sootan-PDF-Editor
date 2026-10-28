import { useEffect, useState, type ReactNode } from "react";
import { getRuntime } from "@editor/runtime/runtime";
import { Icon } from "@editor/ui/Icon";
import "@editor/components/layout/TitleBarChrome.css";

type WindowAction =
  | "state"
  | "minimize"
  | "maximize"
  | "close"
  | "drag"
  | "resize-n"
  | "resize-s"
  | "resize-e"
  | "resize-w"
  | "resize-ne"
  | "resize-nw"
  | "resize-se"
  | "resize-sw";
interface ChromeBridge {
  postMessage: (message: object) => void;
  addEventListener: (
    type: "message",
    listener: (event: { data: { type?: string; maximized?: boolean } }) => void,
  ) => void;
  removeEventListener: ChromeBridge["addEventListener"];
}
function bridge() {
  return (window as unknown as { chrome?: { webview?: ChromeBridge } }).chrome
    ?.webview;
}
function send(action: WindowAction) {
  bridge()?.postMessage({ type: "window", action });
}

/** Native window commands exist only in the trusted desktop host; browser chrome is untouched. */
export function TitleBarChrome({ children }: { children: ReactNode }) {
  const [maximized, setMaximized] = useState(false);
  const enabled = getRuntime().nativeWindowControls === true;
  useEffect(() => {
    if (!enabled) return;
    const listener = (event: {
      data: { type?: string; maximized?: boolean };
    }) => {
      if (event.data.type === "window-state")
        setMaximized(event.data.maximized === true);
    };
    bridge()?.addEventListener("message", listener);
    send("state");
    return () => bridge()?.removeEventListener("message", listener);
  }, [enabled]);
  if (!enabled) return <>{children}</>;
  return (
    <div className="desktop-chrome">
      <header className="desktop-chrome__bar" aria-label="Window controls">
        <div
          className="desktop-chrome__drag"
          onPointerDown={(event) => {
            if (event.button === 0) send("drag");
          }}
        >
          <Icon name="sootan-mark" size="1rem" />
          <span>Sootan PDF Editor</span>
        </div>
        <button
          type="button"
          aria-label="Minimize window"
          onClick={() => send("minimize")}
        >
          <Icon name="minus" size="1rem" />
        </button>
        <button
          type="button"
          aria-label={maximized ? "Restore window" : "Maximize window"}
          onClick={() => send("maximize")}
        >
          <Icon name={maximized ? "copy" : "square"} size="1rem" />
        </button>
        <button
          type="button"
          aria-label="Close window"
          onClick={() => send("close")}
        >
          <Icon name="x" size="1rem" />
        </button>
      </header>
      {children}
      {!maximized &&
        (["n", "s", "e", "w", "ne", "nw", "se", "sw"] as const).map((edge) => (
          <div
            key={edge}
            className={`desktop-chrome__resize desktop-chrome__resize--${edge}`}
            onPointerDown={(event) => {
              if (event.button === 0) send(`resize-${edge}`);
            }}
          />
        ))}
    </div>
  );
}
