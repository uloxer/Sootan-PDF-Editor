import { useEffect } from "react";
import { useFileHandler } from "@editor/hooks/useFileHandler";
import { useFileActions } from "@editor/contexts/FileContext";
import { useViewer } from "@editor/contexts/ViewerContext";
import {
  useNavigationActions,
  useNavigationGuard,
} from "@editor/contexts/NavigationContext";
import { alert } from "@editor/components/toast";

interface IntakeFile {
  name: string;
  token: string;
  lastModified: number;
  url: string;
}
interface NativeMessage {
  data: { type: string; files?: IntakeFile[] };
}
interface NativeWebView {
  postMessage: (message: object) => void;
  addEventListener: (
    type: "message",
    listener: (event: NativeMessage) => void,
  ) => void;
  removeEventListener: (
    type: "message",
    listener: (event: NativeMessage) => void,
  ) => void;
}

/** Only one-use, same-origin native file grants are accepted; no disk path API exists. */
export function isDesktopFileGrant(file: IntakeFile, origin: string): boolean {
  try {
    const url = new URL(file.url);
    return (
      /^[a-f0-9]{32}$/.test(file.token) &&
      url.origin === origin &&
      url.pathname === `/__sootan_selected/${file.token}` &&
      file.name.toLowerCase().endsWith(".pdf")
    );
  } catch {
    return false;
  }
}

export function DesktopBridge() {
  const { addFiles } = useFileHandler();
  const { actions } = useFileActions();
  const { setActiveFileId } = useViewer();
  const { actions: navigation } = useNavigationActions();
  const { requestNavigation } = useNavigationGuard();
  useEffect(() => {
    const webview = (
      window as unknown as { chrome?: { webview?: NativeWebView } }
    ).chrome?.webview;
    if (!webview) return;
    const listener = (event: NativeMessage) => {
      if (event.data?.type === "close") {
        requestNavigation(() =>
          webview.postMessage({ type: "close-approved" }),
        );
        return;
      }
      if (event.data?.type !== "files" || !Array.isArray(event.data.files))
        return;
      const grants = event.data.files;
      if (
        !grants.every((file) =>
          isDesktopFileGrant(file, window.location.origin),
        )
      )
        return;
      requestNavigation(() => {
        void (async () => {
          const files: File[] = [];
          for (const grant of grants) {
            try {
              const response = await fetch(grant.url);
              if (!response.ok) throw new Error("Unable to read selected PDF");
              files.push(
                new File([await response.blob()], grant.name, {
                  type: "application/pdf",
                  lastModified: grant.lastModified,
                }),
              );
            } finally {
              webview.postMessage({ type: "release", token: grant.token });
            }
          }
          const added = await addFiles(files, { selectFiles: false });
          const last = added.at(-1);
          if (last) {
            setActiveFileId(last.fileId);
            actions.setSelectedFiles([last.fileId]);
            navigation.setWorkbench("viewer");
          }
        })().catch((error) =>
          alert({
            alertType: "error",
            title: "Unable to open PDF",
            body: String(error),
          }),
        );
      });
    };
    const openShortcut = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "o") {
        event.preventDefault();
        webview.postMessage({ type: "open" });
      }
    };
    window.addEventListener("keydown", openShortcut);
    webview.addEventListener("message", listener);
    webview.postMessage({ type: "ready" });
    return () => {
      window.removeEventListener("keydown", openShortcut);
      webview.removeEventListener("message", listener);
    };
  }, [addFiles, actions, setActiveFileId, navigation, requestNavigation]);
  return null;
}
